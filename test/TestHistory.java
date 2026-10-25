import com.whale.mini.WsClient;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** 拉一次快照，看测试会话里模型有没有真的收到图片（找“收到图片”和 assistant/message）。 */
public class TestHistory {
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
                try {
                    java.nio.file.Files.write(java.nio.file.Paths.get("D:\\whale home\\tmp\\snapshot.json"),
                            text.getBytes("UTF-8"));
                    System.out.println("[saved] tmp\\snapshot.json");
                } catch (Exception e) {
                    System.out.println("[save-err] " + e);
                }
                done.countDown();
            }
            @Override public void onClose(int code, String reason) { done.countDown(); }
            @Override public void onError(Exception e) { System.out.println("[err] " + e); done.countDown(); }
        });

        ws.sendText("{\"type\":\"open\",\"streamId\":\"h1\",\"endpoint\":\"session/follow\","
                + "\"payload\":{\"args\":{\"request\":{\"address\":{\"kind\":\"session\",\"sessionId\":\""
                + sid + "\"}}}}}");

        done.await(15, TimeUnit.SECONDS);
        ws.close();
        System.exit(0);
    }

    private static void show(String s, String needle) {
        int idx = s.indexOf(needle);
        if (idx < 0) { System.out.println("(no match: " + needle + ")"); return; }
        int from = Math.max(0, idx - 300);
        int to = Math.min(s.length(), idx + 500);
        System.out.println("---- " + needle + " @" + idx + " ----");
        System.out.println(s.substring(from, to).replace("\n", "\\n"));
    }
}
