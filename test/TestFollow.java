import com.whale.mini.WsClient;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** JVM 上验证 session/follow：应收到 snapshot（历史）。 */
public class TestFollow {
    public static void main(String[] args) throws Exception {
        String cookie = args[0];
        String sid = args.length > 1 ? args[1] : "session-85951a38-cd6c-43f6-83d0-c0d92af39d5e";

        Map<String, String> h = new HashMap<String, String>();
        h.put("Cookie", cookie);

        final CountDownLatch done = new CountDownLatch(1);
        final AtomicInteger n = new AtomicInteger();

        WsClient ws = new WsClient("127.0.0.1", 19387, false, "/api/remote.mux", h);
        ws.connect(new WsClient.Listener() {
            @Override public void onOpen() { System.out.println("[ok] ws open"); }
            @Override public void onText(String text) {
                int k = n.incrementAndGet();
                if (k <= 3) System.out.println("[frame " + k + "] " + text.substring(0, Math.min(300, text.length())));
                if (k >= 3) done.countDown();
            }
            @Override public void onClose(int code, String reason) { System.out.println("[close] " + code); done.countDown(); }
            @Override public void onError(Exception e) { System.out.println("[err] " + e); done.countDown(); }
        });

        // connect 返回时 onOpen 已执行、reader 已起；此时再发 open 帧最稳
        String open =
            "{\"type\":\"open\",\"streamId\":\"f1\",\"endpoint\":\"session/follow\"," +
            "\"payload\":{\"args\":{\"request\":{\"address\":{\"kind\":\"session\",\"sessionId\":\"" + sid + "\"}}}}}";
        ws.sendText(open);
        System.out.println("[sent] open session/follow -> " + sid);

        done.await(10, TimeUnit.SECONDS);
        System.out.println("[summary] frames=" + n.get());
        ws.close();
        System.exit(0);
    }
}
