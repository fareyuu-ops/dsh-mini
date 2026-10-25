package com.whale.mini;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Map;

/**
 * 极简 RFC6455 WebSocket 客户端（只支持文本帧 + ping/pong/close）。
 *
 * 为什么自己写：Android 上没有现成 WS 库（系统不带 OkHttp），
 * 而本工程是手工构建（aapt2 → javac → d8），不引任何外部依赖。
 *
 * 只覆盖 DSH 用得到的部分：文本帧、掩码发送、握手带 Cookie。
 */
public class WsClient {

    public interface Listener {
        void onOpen();
        void onText(String text);
        void onClose(int code, String reason);
        void onError(Exception e);
    }

    private final String host;
    private final int port;
    private final boolean tls;
    private final String path;
    private final Map<String, String> headers;

    private Socket socket;
    private InputStream in;
    private OutputStream out;
    private Thread reader;
    private volatile Listener listener;
    private volatile boolean open = false;
    private volatile boolean closing = false;
    private final Object writeLock = new Object();

    public WsClient(String host, int port, boolean tls, String path, Map<String, String> headers) {
        this.host = host;
        this.port = port;
        this.tls = tls;
        this.path = path;
        this.headers = headers;
    }

    public boolean isOpen() {
        return open;
    }

    /** 阻塞到握手完成；失败抛 IOException。 */
    public void connect(Listener l) throws IOException {
        this.listener = l;
        Socket s;
        if (tls) {
            s = javax.net.ssl.SSLSocketFactory.getDefault().createSocket();
        } else {
            s = new Socket();
        }
        s.setTcpNoDelay(true);
        s.connect(new InetSocketAddress(host, port), 12000);
        this.socket = s;
        this.in = new BufferedInputStream(s.getInputStream());
        this.out = s.getOutputStream();

        byte[] nonce = new byte[16];
        new SecureRandom().nextBytes(nonce);
        String key = b64(nonce);

        StringBuilder sb = new StringBuilder();
        sb.append("GET ").append(path).append(" HTTP/1.1\r\n");
        sb.append("Host: ").append(host).append(':').append(port).append("\r\n");
        sb.append("Upgrade: websocket\r\n");
        sb.append("Connection: Upgrade\r\n");
        sb.append("Sec-WebSocket-Key: ").append(key).append("\r\n");
        sb.append("Sec-WebSocket-Version: 13\r\n");
        // 浏览器会发 Origin，服务器通常拿它做来源校验；缺了会被直接断连（表现为 eof during handshake）
        sb.append("Origin: ").append(tls ? "https://" : "http://").append(host).append(':').append(port).append("\r\n");
        sb.append("User-Agent: DshMini/0.1\r\n");
        if (headers != null) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                sb.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
            }
        }
        sb.append("\r\n");
        String reqText = sb.toString();
        if (System.getProperty("dsh.wsdebug") != null) {
            System.out.println("[ws-req] " + reqText.replace("\r\n", "\\r\\n"));
        }
        out.write(reqText.getBytes(StandardCharsets.UTF_8));
        out.flush();

        // 读到 \r\n\r\n 为止。⚠️ 不能用 "prev == '\n'" 判断——\r\n\r\n 里
        // 每对之间夹的是 \r，条件永远不成立，会一直读到服务端发的 WS 数据帧，
        // 然后被 reset（表现为 "eof during handshake" 或连接重置）。
        StringBuilder resp = new StringBuilder();
        while (true) {
            int b = in.read();
            if (b < 0) throw new IOException("ws: eof during handshake");
            resp.append((char) b);
            int n = resp.length();
            if (n >= 4
                    && resp.charAt(n - 1) == '\n' && resp.charAt(n - 2) == '\r'
                    && resp.charAt(n - 3) == '\n' && resp.charAt(n - 4) == '\r') {
                break;
            }
        }
        String head = resp.toString();
        int sp = head.indexOf(' ');
        if (sp < 0 || !head.startsWith("HTTP/1.1")) throw new IOException("ws: bad handshake: " + head);
        int code2 = Integer.parseInt(head.substring(sp + 1, sp + 4));
        if (code2 != 101) throw new IOException("ws: handshake HTTP " + code2 + " :: " + head.split("\r\n")[0]);

        open = true;
        if (listener != null) listener.onOpen();
        reader = new Thread(new Runnable() {
            @Override
            public void run() {
                readLoop();
            }
        }, "ws-reader");
        reader.setDaemon(true);
        reader.start();

        // 心跳：每 25 秒一个 ping 帧。中间设备/NAT 长时间没流量会悄悄掐连接，
        // 有心跳能多撑一阵（进程被系统冻结时照样断，那种靠回前台重连）。
        Thread hb = new Thread(new Runnable() {
            @Override
            public void run() {
                while (open) {
                    try {
                        Thread.sleep(25000);
                    } catch (InterruptedException e) {
                        return;
                    }
                    if (!open) return;
                    synchronized (writeLock) {
                        try {
                            writeFrame(0x9, new byte[0]);
                        } catch (IOException e) {
                            return;
                        }
                    }
                }
            }
        }, "ws-heartbeat");
        hb.setDaemon(true);
        hb.start();
    }

    public void sendText(String text) {
        if (!open) return;
        byte[] payload = text.getBytes(StandardCharsets.UTF_8);
        synchronized (writeLock) {
            try {
                writeFrame(0x1, payload);
            } catch (IOException e) {
                fail(e);
            }
        }
    }

    public void close() {
        if (closing) return;
        closing = true;
        try {
            synchronized (writeLock) {
                writeFrame(0x8, new byte[]{0x03, (byte) 0xE8}); // 1000 normal
            }
        } catch (Exception ignored) {
        }
        open = false;
        try { if (socket != null) socket.close(); } catch (Exception ignored) {}
        if (listener != null) listener.onClose(1000, "client close");
    }

    // ---- internals ----

    private void readLoop() {
        try {
            while (open) {
                Frame f = readFrame();
                if (f == null) break;
                if (f.opcode == 0x1) {
                    if (listener != null) listener.onText(new String(f.payload, StandardCharsets.UTF_8));
                } else if (f.opcode == 0x8) {
                    int code = 1000;
                    if (f.payload != null && f.payload.length >= 2) {
                        code = ((f.payload[0] & 0xFF) << 8) | (f.payload[1] & 0xFF);
                    }
                    open = false;
                    if (listener != null) listener.onClose(code, "");
                    break;
                } else if (f.opcode == 0x9) {
                    synchronized (writeLock) {
                        writeFrame(0xA, f.payload == null ? new byte[0] : f.payload);
                    }
                } else if (f.opcode == 0xA) {
                    // pong: ignore
                }
            }
        } catch (Exception e) {
            if (open) fail(e);
        }
    }

    private void fail(Exception e) {
        open = false;
        try { if (socket != null) socket.close(); } catch (Exception ignored) {}
        if (listener != null) listener.onError(e);
    }

    private static class Frame {
        int opcode;
        byte[] payload;
    }

    private Frame readFrame() throws IOException {
        int b0 = in.read();
        if (b0 < 0) return null;
        int opcode = b0 & 0x0F;
        int b1 = in.read();
        if (b1 < 0) return null;
        boolean masked = (b1 & 0x80) != 0;
        long len = b1 & 0x7F;
        if (len == 126) {
            len = ((long) in.read() << 8) | in.read();
        } else if (len == 127) {
            len = 0;
            for (int i = 0; i < 8; i++) len = (len << 8) | in.read();
        }
        if (len > 8L * 1024 * 1024) throw new IOException("ws: frame too big " + len);
        byte[] mask = null;
        if (masked) {
            mask = new byte[4];
            readFully(mask);
        }
        byte[] payload = new byte[(int) len];
        readFully(payload);
        if (mask != null) {
            for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i % 4];
        }
        Frame f = new Frame();
        f.opcode = opcode;
        f.payload = payload;
        return f;
    }

    private void readFully(byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) throw new IOException("ws: eof");
            off += n;
        }
    }

    /** 16 字节 → base64（自实现，避开 Android 7 没有 java.util.Base64）。 */
    private static String b64(byte[] data) {
        final String T = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < data.length; i += 3) {
            int b0 = data[i] & 0xFF;
            int b1 = i + 1 < data.length ? data[i + 1] & 0xFF : 0;
            int b2 = i + 2 < data.length ? data[i + 2] & 0xFF : 0;
            sb.append(T.charAt(b0 >> 2));
            sb.append(T.charAt(((b0 & 0x03) << 4) | (b1 >> 4)));
            sb.append(i + 1 < data.length ? T.charAt(((b1 & 0x0F) << 2) | (b2 >> 6)) : '=');
            sb.append(i + 2 < data.length ? T.charAt(b2 & 0x3F) : '=');
        }
        return sb.toString();
    }

    private void writeFrame(int opcode, byte[] payload) throws IOException {        int len = payload == null ? 0 : payload.length;
        out.write(0x80 | opcode); // FIN + opcode
        if (len < 126) {
            out.write(0x80 | len);
        } else if (len < 65536) {
            out.write(0x80 | 126);
            out.write((len >> 8) & 0xFF);
            out.write(len & 0xFF);
        } else {
            out.write(0x80 | 127);
            for (int i = 7; i >= 0; i--) out.write((int) (((long) len >> (8 * i)) & 0xFF));
        }
        byte[] mask = new byte[4];
        new SecureRandom().nextBytes(mask);
        out.write(mask);
        if (len > 0) {
            byte[] masked = new byte[len];
            for (int i = 0; i < len; i++) masked[i] = (byte) (payload[i] ^ mask[i % 4]);
            out.write(masked);
        }
        out.flush();
    }
}
