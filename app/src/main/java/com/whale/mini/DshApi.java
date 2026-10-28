package com.whale.mini;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * DSH HTTP RPC 层（协议见 知识库\DSH-协议逆向.md）。
 *
 * 三个请求够了：换 cookie、列会话、发消息。
 * 读历史与流式走 WsClient + session/follow。
 */
public class DshApi {

    public final boolean tls;
    public final String host;
    public final int port;
    public final String origin;   // http(s)://host:port

    private volatile String cookie;

    public DshApi(String baseUrl) {
        String u = baseUrl.trim();
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://" + u;
        this.tls = u.startsWith("https://");
        try {
            URI uri = new URI(u);
            this.host = uri.getHost();
            int p = uri.getPort();
            this.port = p > 0 ? p : (tls ? 443 : 80);
            this.origin = (tls ? "https://" : "http://") + host + ":" + port;
        } catch (Exception e) {
            throw new IllegalArgumentException("bad base url: " + baseUrl, e);
        }
    }

    public String cookie() {
        return cookie;
    }

    /** 用 token 换 30 天 Cookie。 */
    public void login(String token) throws IOException {
        String url = origin + "/?token=" + token;
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setInstanceFollowRedirects(false);
        c.setConnectTimeout(10000);
        c.setReadTimeout(10000);
        c.setRequestMethod("GET");
        try {
            int code = c.getResponseCode();
            List<String> sc = c.getHeaderFields().get("Set-Cookie");
            if (sc != null && !sc.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (String s : sc) {
                    int semi = s.indexOf(';');
                    String kv = semi >= 0 ? s.substring(0, semi) : s;
                    if (sb.length() > 0) sb.append("; ");
                    sb.append(kv);
                }
                this.cookie = sb.toString();
            }
            if (this.cookie == null || this.cookie.isEmpty()) {
                throw new IOException("login: no Set-Cookie (HTTP " + code + ")");
            }
        } finally {
            c.disconnect();
        }
    }

    /** 会话列表。返回 items 数组。 */
    public JSONArray listSessions() throws IOException, JSONException {
        JSONObject args = new JSONObject();
        args.put("_request", new JSONObject());
        JSONObject res = rpc("session/list", args);
        try {
            return res.getJSONObject("value").getJSONArray("items");
        } catch (Exception e) {
            throw new IOException("listSessions: unexpected shape: " + res, e);
        }
    }

    /** 发一条消息（异步，只表示进队列）。 */
    public void sendPrompt(String sessionId, String text) throws IOException, JSONException {
        sendPrompt(sessionId, text, null, null, null);
    }

    /**
     * 带多张图片的消息。图片走 content 里的 image 块：
     * {"type":"image","mediaType":"image/png","data":"<base64>","name":"x.png"}
     * 传 null / 空数组就是纯文本。
     */
    public void sendPrompt(String sessionId, String text,
                           List<byte[]> images, List<String> mediaTypes, List<String> names)
            throws IOException, JSONException {
        JSONArray content = new JSONArray();
        if (images != null) {
            for (int i = 0; i < images.size(); i++) {
                byte[] img = images.get(i);
                if (img == null || img.length == 0) continue;
                String mime = (mediaTypes != null && i < mediaTypes.size()) ? mediaTypes.get(i) : "image/png";
                String name = (names != null && i < names.size()) ? names.get(i) : null;
                JSONObject block = new JSONObject();
                block.put("type", "image");
                block.put("mediaType", mime);
                block.put("data", android.util.Base64.encodeToString(img, android.util.Base64.NO_WRAP));
                if (name != null && name.length() > 0) block.put("name", name);
                content.put(block);
            }
        }
        if (text != null && text.length() > 0) {
            JSONObject block = new JSONObject();
            block.put("type", "text");
            block.put("text", text);
            content.put(block);
        }
        if (content.length() == 0) return;

        JSONObject request = new JSONObject();
        request.put("requestId", java.util.UUID.randomUUID().toString());
        request.put("sessionId", sessionId);
        request.put("mode", "queue");
        request.put("content", content);
        request.put("clientTimeZone", "Asia/Shanghai");

        JSONObject args = new JSONObject();
        args.put("request", request);
        rpc("session/prompt", args);
    }

    /**
     * 读附件内容（会话里图片的 attachmentId → 原始字节）。
     * RPC session/attachment，返回的 data 是 base64。
     */
    public byte[] readAttachment(String sessionId, String attachmentId) throws IOException, JSONException {
        JSONObject req = new JSONObject();
        req.put("sessionId", sessionId);
        req.put("attachmentId", attachmentId);
        JSONObject args = new JSONObject();
        args.put("request", req);
        JSONObject res = rpc("session/attachment", args);
        JSONObject value = res.optJSONObject("value");
        if (value == null) throw new IOException("attachment: no value");
        String b64 = value.optString("data", "");
        if (b64.length() == 0) throw new IOException("attachment: no data");
        return android.util.Base64.decode(b64, android.util.Base64.DEFAULT);
    }

    /** 取消当前会话正在进行的生成（服务端会随后推 turn/end，reason=cancelled）。 */
    public void cancelSession(String sessionId) throws IOException, JSONException {
        JSONObject request = new JSONObject();
        request.put("sessionId", sessionId);
        JSONObject args = new JSONObject();
        args.put("request", request);
        rpc("session/cancel", args);
    }

    /**
     * 查账户余额。返回形如 "¥37.54"；失败返回 null。
     * RPC account/getBalance，参数是 client（不是 request）。
     */
    public String getBalance() throws IOException, JSONException {
        JSONObject client = new JSONObject();
        client.put("version", "1.0.0");
        client.put("locale", "zh-CN");
        client.put("timezoneOffsetSeconds", -28800);
        JSONObject args = new JSONObject();
        args.put("client", client);

        JSONObject res = rpc("account/getBalance", args);
        JSONObject value = res.optJSONObject("value");
        if (value == null) return null;
        if (!"ready".equals(value.optString("status", ""))) return null;
        JSONArray arr = value.optJSONArray("value");
        if (arr == null || arr.length() == 0) return null;

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject w = arr.optJSONObject(i);
            if (w == null) continue;
            String cur = w.optString("currency", "CNY");
            String bal = w.optString("balance", "0");
            if (sb.length() > 0) sb.append("  ");
            sb.append(formatBalance(cur, bal));
        }
        JSONArray bonus = value.optJSONArray("bonusWallets");
        if (bonus != null && bonus.length() > 0) {
            JSONObject b = bonus.optJSONObject(0);
            if (b != null) {
                String bv = b.optString("balance", "0");
                if (!"0".equals(bv) && !"0.0".equals(bv) && !bv.startsWith("0.0000")) {
                    sb.append("  + 赠送 ").append(formatBalance(b.optString("currency", "CNY"), bv));
                }
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private static String formatBalance(String currency, String raw) {
        String symbol = "USD".equals(currency) ? "$" : "¥";
        try {
            java.math.BigDecimal d = new java.math.BigDecimal(raw);
            return symbol + d.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
        } catch (Exception e) {
            return symbol + raw;
        }
    }

    /** 改会话标题。RPC session/rename，参数 {sessionId, title}。 */
    public void rename(String sessionId, String title) throws IOException, JSONException {
        JSONObject req = new JSONObject();
        req.put("sessionId", sessionId);
        req.put("title", title);
        JSONObject args = new JSONObject();
        args.put("request", req);
        rpc("session/rename", args);
    }

    /** 模型目录（可用模型 + 各家支持的思考强度）。参数为空。 */
    public JSONObject modelCatalog() throws IOException, JSONException {
        JSONObject res = rpc("session/modelCatalog", new JSONObject());
        JSONObject value = res.optJSONObject("value");
        if (value == null) throw new IOException("modelCatalog: no value");
        return value;
    }

    /** 切模型 / 思考强度。RPC session/selectModel。 */
    public void selectModel(String sessionId, String provider, String model, String effort)
            throws IOException, JSONException {
        JSONObject req = new JSONObject();
        req.put("sessionId", sessionId);
        req.put("provider", provider);
        req.put("model", model);
        if (effort != null && effort.length() > 0) req.put("reasoningEffort", effort);
        JSONObject args = new JSONObject();
        args.put("request", req);
        rpc("session/selectModel", args);
    }

    /** 新建一个会话，返回 sessionId。 */
    public String createSession() throws IOException, JSONException {
        JSONObject args = new JSONObject();
        args.put("request", new JSONObject());
        JSONObject res = rpc("session/create", args);
        JSONObject value = res.optJSONObject("value");
        if (value == null) throw new IOException("create: no value");
        String sid = value.optString("sessionId", "");
        if (sid.length() == 0) throw new IOException("create: no sessionId");
        return sid;
    }

    /** 打开 session/follow 流（历史 + 流式）。回调在 WS 读线程。 */
    public WsClient openFollow(String sessionId, WsClient.Listener l) throws IOException, JSONException {
        Map<String, String> h = new HashMap<String, String>();
        h.put("Cookie", cookie);
        WsClient ws = new WsClient(host, port, tls, "/api/remote.mux", h);
        ws.connect(l);
        JSONObject address = new JSONObject();
        address.put("kind", "session");
        address.put("sessionId", sessionId);
        JSONObject request = new JSONObject();
        request.put("address", address);
        JSONObject args = new JSONObject();
        args.put("request", request);
        ws.sendText(wsOpen("f1", "session/follow", args));
        return ws;
    }

    /** 拼一个 WS open 帧。 */
    public static String wsOpen(String streamId, String endpoint, JSONObject args) {
        try {
            JSONObject payload = new JSONObject();
            payload.put("args", args);
            JSONObject o = new JSONObject();
            o.put("type", "open");
            o.put("streamId", streamId);
            o.put("endpoint", endpoint);
            o.put("payload", payload);
            return o.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ---- internals ----

    private JSONObject rpc(String method, JSONObject args) throws IOException, JSONException {
        if (cookie == null || cookie.isEmpty()) throw new IOException("rpc: not logged in");
        String url = origin + "/api/" + method;
        JSONObject payload = new JSONObject();
        payload.put("args", args);

        JSONObject body = new JSONObject();
        body.put("type", "client-request");
        body.put("rpcId", java.util.UUID.randomUUID().toString());
        body.put("method", method);
        body.put("payload", payload);

        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(15000);
        c.setReadTimeout(120000);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Cookie", cookie);
        c.setRequestProperty("Content-Length", String.valueOf(bytes.length));
        try {
            OutputStream os = c.getOutputStream();
            os.write(bytes);
            os.flush();
            os.close();

            int code = c.getResponseCode();
            String text = readAll(code >= 400 ? c.getErrorStream() : c.getInputStream());
            if (code != 200) throw new IOException("rpc " + method + ": HTTP " + code + " " + trim(text));

            JSONObject res = new JSONObject(text);
            JSONObject result = res.optJSONObject("result");
            if (result == null) throw new IOException("rpc " + method + ": no result: " + trim(text));
            if (!result.optBoolean("ok", false)) {
                JSONObject err = result.optJSONObject("error");
                String msg = err != null ? err.optString("message", err.toString()) : "unknown error";
                String errCode = err != null ? err.optString("code", "") : "";
                throw new IOException("rpc " + method + " failed [" + errCode + "]: " + msg);
            }
            return result;
        } finally {
            c.disconnect();
        }
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String trim(String s) {
        if (s == null) return "";
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }
}
