import com.whale.mini.WsClient;

import java.util.HashMap;
import java.util.Map;

/** JVM 上直接验证 WsClient 的握手（不经过 Android）。 */
public class TestHandshake {
    public static void main(String[] args) throws Exception {
        String cookie = args[0];
        String host = "127.0.0.1";
        int port = 19387;

        Map<String, String> h = new HashMap<String, String>();
        h.put("Cookie", cookie);

        WsClient ws = new WsClient(host, port, false, "/api/remote.mux", h);
        try {
            ws.connect(new WsClient.Listener() {
                @Override public void onOpen() { System.out.println("[ok] ws open"); }
                @Override public void onText(String text) { System.out.println("[msg] " + text.substring(0, Math.min(200, text.length()))); }
                @Override public void onClose(int code, String reason) { System.out.println("[close] " + code + " " + reason); }
                @Override public void onError(Exception e) { System.out.println("[err] " + e); }
            });
            System.out.println("[state] isOpen=" + ws.isOpen());
            Thread.sleep(1500);
            ws.close();
        } catch (Exception e) {
            System.out.println("[fail] " + e.getClass().getSimpleName() + ": " + e.getMessage());
            e.printStackTrace();
        }
        System.exit(0);
    }
}
