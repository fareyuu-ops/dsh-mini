import com.whale.mini.WsClient;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 从会话快照里找图片块，打印上下文——用来确认 DSH 的图片消息长什么样。
 *
 * 用法： java TestImages <cookie> <sessionId>
 */
public class TestImages {
    public static void main(String[] args) throws Exception {
        String cookie = args[0];
        String sid = args[1];

        Map<String, String> h = new HashMap<String, String>();
        h.put("Cookie", cookie);

        final CountDownLatch done = new CountDownLatch(1);

        WsClient ws = new WsClient("127.0.0.1", 19387, false, "/api/remote.mux", h);
        ws.connect(new WsClient.Listener() {
            @Override public void onOpen() { System.out.println("[ok] ws open"); }
            @Override public void onText(String text) {
                if (text.indexOf("snapshot") < 0) return;
                System.out.println("[snapshot] len=" + text.length());
                scan(text, "\"type\":\"image\"");
                scan(text, "\"type\": \"image\"");
                scan(text, "image/png");
                done.countDown();
            }
            @Override public void onClose(int code, String reason) { done.countDown(); }
            @Override public void onError(Exception e) { System.out.println("[err] " + e); done.countDown(); }
        });

        ws.sendText("{\"type\":\"open\",\"streamId\":\"i1\",\"endpoint\":\"session/follow\","
                + "\"payload\":{\"args\":{\"request\":{\"address\":{\"kind\":\"session\",\"sessionId\":\""
                + sid + "\"}}}}}");

        done.await(12, TimeUnit.SECONDS);
        ws.close();
        System.exit(0);
    }

    private static void scan(String s, String needle) {
        int idx = 0;
        int n = 0;
        while ((idx = s.indexOf(needle, idx)) >= 0) {
            n++;
            if (n > 6) { System.out.println("  ... more matches"); return; }
            int from = Math.max(0, idx - 260);
            int to = Math.min(s.length(), idx + 420);
            System.out.println("---- " + needle + " @" + idx + " ----");
            System.out.println(s.substring(from, to).replace("\n", "\\n"));
            idx += needle.length();
        }
        if (n == 0) System.out.println("(no match: " + needle + ")");
    }
}
