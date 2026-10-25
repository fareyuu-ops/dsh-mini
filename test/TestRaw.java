import java.io.BufferedInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/** 用裸 Socket 发一个手工拼好的握手请求（与 Python 成功案例逐字节一致）。 */
public class TestRaw {
    public static void main(String[] args) throws Exception {
        String cookie = args[0];
        String req =
                "GET /api/remote.mux HTTP/1.1\r\n" +
                "Host: 127.0.0.1:19387\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" +
                "Sec-WebSocket-Version: 13\r\n" +
                "Origin: http://127.0.0.1:19387\r\n" +
                "User-Agent: DshMini/0.1\r\n" +
                "Cookie: " + cookie + "\r\n" +
                "\r\n";

        Socket s = new Socket();
        s.setTcpNoDelay(true);
        s.connect(new InetSocketAddress("127.0.0.1", 19387), 12000);
        OutputStream out = s.getOutputStream();
        out.write(req.getBytes("UTF-8"));
        out.flush();

        InputStream in = new BufferedInputStream(s.getInputStream());
        StringBuilder sb = new StringBuilder();
        int prev = -1;
        try {
            while (true) {
                int b = in.read();
                if (b < 0) { System.out.println("[eof] partial=" + sb); break; }
                sb.append((char) b);
                if (b == '\n' && prev == '\n') break;
                prev = b;
            }
        } catch (Exception e) {
            System.out.println("[read fail] " + e + " partial=" + sb);
        }
        System.out.println("[resp] " + sb.toString().replace("\r\n", " | "));
        s.close();
    }
}
