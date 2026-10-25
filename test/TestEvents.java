import com.whale.mini.WsClient;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 探针：订阅 $events 全局广播，然后往一个会话发消息，
 * 看有没有逐字 token 事件（agent/assistant-stream 之类）。
 *
 * 用法：
 *   java TestEvents <cookie> <sessionId> [等待秒数]
 *
 * 只是为了摸协议，不进 App。
 */
public class TestEvents {
    private static final Pattern EV = Pattern.compile("\"event\"\\s*:\\s*\"([^\"]+)\"");

    public static void main(String[] args) throws Exception {
        final String cookie = args[0];
        final String sid = args[1];
        final String endpoint = args.length > 2 ? args[2] : "$events";
        final String argsJson = args.length > 3 ? args[3] : "{}";
        final int waitSec = args.length > 4 ? Integer.parseInt(args[4]) : 25;

        Map<String, String> h = new HashMap<String, String>();
        h.put("Cookie", cookie);

        final CountDownLatch done = new CountDownLatch(1);
        final AtomicInteger n = new AtomicInteger();
        final Map<String, Integer> types = new HashMap<String, Integer>();

        WsClient ws = new WsClient("127.0.0.1", 19387, false, "/api/remote.mux", h);
        ws.connect(new WsClient.Listener() {
            @Override public void onOpen() { System.out.println("[ok] ws open"); }
            @Override public void onText(String text) {
                int k = n.incrementAndGet();
                String type = jsonStr(text, "type");
                String ev = jsonStr(text, "event");
                String key = type + (ev != null ? ":" + ev : "");
                Integer c = types.get(key);
                types.put(key, c == null ? 1 : c + 1);
                // assistant/message 打完整（要看 stream 字段），其他裁短
                int max = text.contains("assistant/message") ? 9000 : 400;
                System.out.println("[f" + k + "] " + crop(text, max));
            }
            @Override public void onClose(int code, String reason) { System.out.println("[close] " + code); done.countDown(); }
            @Override public void onError(Exception e) { System.out.println("[err] " + e); done.countDown(); }
        });

        ws.sendText("{\"type\":\"open\",\"streamId\":\"ev1\",\"endpoint\":\"" + endpoint
                + "\",\"payload\":{\"args\":" + argsJson + "}}");
        System.out.println("[sent] open " + endpoint);

        // 1.5 秒后发一条消息，逼出流式事件
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    Thread.sleep(1500);
                    System.out.println("[http] sending prompt...");
                    String r = postJson(cookie, "/api/session/prompt",
                            "{\"type\":\"client-request\",\"rpcId\":\"" + java.util.UUID.randomUUID() + "\","
                            + "\"method\":\"session/prompt\",\"payload\":{\"args\":{\"request\":{"
                            + "\"requestId\":\"" + java.util.UUID.randomUUID() + "\","
                            + "\"sessionId\":\"" + sid + "\","
                            + "\"mode\":\"queue\","
                            + "\"content\":[{\"type\":\"text\",\"text\":\"从1数到10，每个数字后面加句号。\"}],"
                            + "\"clientTimeZone\":\"Asia/Shanghai\"}}}}");
                    System.out.println("[http] resp: " + crop(r, 300));
                } catch (Exception e) {
                    System.out.println("[http-err] " + e);
                }
            }
        });
        t.setDaemon(true);
        t.start();

        done.await(waitSec, TimeUnit.SECONDS);
        System.out.println("[summary] frames=" + n.get() + " types=" + types);
        ws.close();
        System.exit(0);
    }

    private static String postJson(String cookie, String path, String body) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:19387" + path).openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(15000);
        c.setReadTimeout(120000);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Cookie", cookie);
        HttpURLConnection.class.getName(); // no-op
        OutputStream os = c.getOutputStream();
        os.write(bytes);
        os.flush();
        os.close();
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while (in != null && (n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return "HTTP " + code + " " + new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String jsonStr(String json, String field) {
        Matcher m = Pattern.compile("\"" + field + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    private static String crop(String s, int max) {
        if (s == null) return "null";
        return s.length() <= max ? s : s.substring(0, max) + "...(" + s.length() + ")";
    }
}
