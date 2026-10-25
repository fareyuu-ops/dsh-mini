import com.whale.mini.WsClient;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 通用「试开一个 WS stream」探针：endpoint / args 全部从命令行给，
 * 只为摸未知端点用（不改 App 代码）。
 *
 * 用法：
 *   java TestOpen <cookie> <endpoint> <args-json> [等待秒数]
 * 例：
 *   java TestOpen "dsh-auth-...=..." agent/assistant-stream '{"request":{"address":{"kind":"session","sessionId":"session-..."}}}' 8
 */
public class TestOpen {
    public static void main(String[] args) throws Exception {
        String cookie = args[0];
        String endpoint = args[1];
        String argsJson = args.length > 2 ? args[2] : "{}";
        int waitSec = args.length > 3 ? Integer.parseInt(args[3]) : 8;

        Map<String, String> h = new HashMap<String, String>();
        h.put("Cookie", cookie);

        final CountDownLatch done = new CountDownLatch(1);
        final AtomicInteger n = new AtomicInteger();

        WsClient ws = new WsClient("127.0.0.1", 19387, false, "/api/remote.mux", h);
        ws.connect(new WsClient.Listener() {
            @Override public void onOpen() { System.out.println("[ok] ws open"); }
            @Override public void onText(String text) {
                int k = n.incrementAndGet();
                System.out.println("[frame " + k + "] " + crop(text, 800));
            }
            @Override public void onClose(int code, String reason) { System.out.println("[close] " + code + " " + reason); done.countDown(); }
            @Override public void onError(Exception e) { System.out.println("[err] " + e); done.countDown(); }
        });

        String open = "{\"type\":\"open\",\"streamId\":\"s1\",\"endpoint\":\"" + endpoint + "\","
                + "\"payload\":{\"args\":" + argsJson + "}}";
        ws.sendText(open);
        System.out.println("[sent] " + open);

        done.await(waitSec, TimeUnit.SECONDS);
        System.out.println("[summary] frames=" + n.get());
        ws.close();
        System.exit(0);
    }

    private static String crop(String s, int max) {
        if (s == null) return "null";
        return s.length() <= max ? s : s.substring(0, max) + "...(" + s.length() + ")";
    }
}
