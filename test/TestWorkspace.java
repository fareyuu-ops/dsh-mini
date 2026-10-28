import com.whale.mini.WsClient;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** JVM 上验证 workspace/follow：应收到 baseline（工作区列表 + 每区的 sessionIds）。 */
public class TestWorkspace {
    public static void main(String[] args) throws Exception {
        String cookie = args[0];
        final String probe = args.length > 1 ? args[1] : null;

        Map<String, String> h = new HashMap<String, String>();
        h.put("Cookie", cookie);

        final CountDownLatch done = new CountDownLatch(1);
        final AtomicInteger n = new AtomicInteger();

        WsClient ws = new WsClient("192.168.1.2", 19387, false, "/api/remote.mux", h);
        ws.connect(new WsClient.Listener() {
            @Override public void onOpen() { System.out.println("[ok] ws open"); }
            @Override public void onText(String text) {
                int k = n.incrementAndGet();
                if (k == 1) {
                    if (probe == null) {
                        System.out.println("[frame 1] " + text.substring(0, Math.min(1500, text.length())));
                    } else {
                        // 找每个 workspace 的 sessionIds 段，看目标会话落在哪个区
                        int idx = text.indexOf(probe);
                        System.out.println("[probe] " + probe + " found=" + (idx >= 0));
                        if (idx >= 0) {
                            int wsIdx = text.lastIndexOf("\"workspaceId\":\"", idx);
                            int a = wsIdx + 15;
                            int b = text.indexOf('"', a);
                            System.out.println("[owner] " + text.substring(a, b));
                            int titleIdx = text.lastIndexOf("\"title\":\"", idx);
                            int ta = titleIdx + 9;
                            int tb = text.indexOf('"', ta);
                            System.out.println("[owner title] " + text.substring(ta, tb));
                            int pathIdx = text.lastIndexOf("\"path\":\"", idx);
                            int pa = pathIdx + 8;
                            int pb = text.indexOf('"', pa);
                            System.out.println("[owner path] " + text.substring(pa, pb));
                        }
                    }
                }
                if (k >= 1) done.countDown();
            }
            @Override public void onClose(int code, String reason) { System.out.println("[close] " + code); done.countDown(); }
            @Override public void onError(Exception e) { System.out.println("[err] " + e); done.countDown(); }
        });

        String open =
            "{\"type\":\"open\",\"streamId\":\"w1\",\"endpoint\":\"workspace/follow\",\"payload\":{\"args\":{}}}";
        ws.sendText(open);
        System.out.println("[sent] open workspace/follow");

        done.await(8, TimeUnit.SECONDS);
        System.out.println("[summary] frames=" + n.get());
        ws.close();
        System.exit(0);
    }
}
