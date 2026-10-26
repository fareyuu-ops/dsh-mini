package com.whale.mini;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 常驻前台服务：App 退到后台时接管 WebSocket，收到回复就发通知。
 *
 * 用前台服务（带常驻通知）而不是普通后台服务，是因为 Android 8+ 的后台服务
 * 会被系统随时冻结/杀掉，而前台服务能稳定挂着——代价是通知栏常驻一条。
 */
public class DshService extends Service {

    private static final String CHANNEL_ID = "dsh_mini_bg";
    private static final int NOTI_ID = 1001;
    private static final int NOTI_REPLY_ID = 1002;

    private WsClient ws;
    private String sessionId;
    private NotificationManager nm;

    @Override
    public void onCreate() {
        super.onCreate();
        nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        ensureChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTI_ID, buildNotification("已挂后台", "有回复会在这里提醒你"));

        String sid = intent != null ? intent.getStringExtra("sessionId") : null;
        String url = intent != null ? intent.getStringExtra("url") : null;
        String token = intent != null ? intent.getStringExtra("token") : null;
        // 任何一项缺失都从设置里补：Activity 只传 sessionId 时 url/token 会是 null
        SharedPreferences sp = getSharedPreferences("dshmini", MODE_PRIVATE);
        if (sid == null || sid.length() == 0) sid = sp.getString("last", "");
        if (url == null || url.length() == 0) url = sp.getString("url", "");
        if (token == null || token.length() == 0) token = sp.getString("token", "");
        if (sid != null && sid.length() > 0 && !sid.equals(sessionId)) {
            sessionId = sid;
            connect(sid, url, token);
        }
        return START_STICKY;
    }

    private void connect(final String sid, final String url, final String token) {
        closeWs();
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    if (url == null || token == null) return;
                    final DshApi api = new DshApi(url);
                    api.login(token);
                    ws = api.openFollow(sid, new WsClient.Listener() {
                        @Override public void onOpen() {
                            notifyState("已连接", "有回复会在这里提醒你");
                        }

                        @Override
                        public void onText(String text) {
                            handleFrame(text);
                        }

                        @Override public void onClose(int code, String reason) {
                            notifyState("连接断开", "回到 App 会自动重连");
                        }

                        @Override public void onError(Exception e) {
                            notifyState("连接出错", String.valueOf(e.getMessage()));
                        }
                    });
                } catch (Exception ignored) {
                }
            }
        }).start();
    }

    /** 后台只关心「AI 回复完成」这一件事，不做逐字回放。 */
    private void handleFrame(String text) {
        try {
            JSONObject o = new JSONObject(text);
            if (!"item".equals(o.optString("type"))) return;
            JSONObject val = o.optJSONObject("value");
            if (val == null) return;
            JSONObject ev = val.optJSONObject("event");
            if (ev == null) return;
            String t = ev.optString("type");

            if ("assistant/message".equals(t)) {
                String body = extractText(ev.optJSONObject("data"));
                String line = body.length() > 60 ? body.substring(0, 60) + "…" : body;
                notifyReply(line.length() == 0 ? "回复到了" : line);
            } else if ("turn/start".equals(t)) {
                // 新的一轮开始才复位；turn/end 紧跟 assistant/message，不复位，
                // 否则「鱼鱼回你了」会被瞬间刷掉
                notifyState("已连接", "有回复会在这里提醒你");
            }
        } catch (Exception ignored) {
        }
    }

    private String extractText(JSONObject data) {
        if (data == null) return "";
        JSONArray content = data.optJSONArray("content");
        if (content == null) {
            JSONObject m = data.optJSONObject("message");
            if (m != null) content = m.optJSONArray("content");
        }
        if (content == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < content.length(); i++) {
            JSONObject b = content.optJSONObject(i);
            if (b == null || !"text".equals(b.optString("type"))) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(b.optString("text", ""));
        }
        return sb.toString();
    }

    // ---- 通知 ----

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT < 26 || nm == null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                "后台待命", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("App 在后台保持连接时的状态与回复提醒");
        ch.enableLights(false);
        ch.enableVibration(true);
        nm.createNotificationChannel(ch);
    }

    private PendingIntent openApp() {
        Intent it = new Intent(this, MainActivity.class);
        it.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = Build.VERSION.SDK_INT >= 23
                ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                : PendingIntent.FLAG_UPDATE_CURRENT;
        return PendingIntent.getActivity(this, 0, it, flags);
    }

    private Notification buildNotification(String title, String text) {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        b.setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_notify)
                .setOngoing(true)
                .setContentIntent(openApp());
        return b.build();
    }

    private void notifyReply(String line) {
        startForeground(NOTI_ID, buildNotification("鱼鱼回你了", line));
    }

    private void notifyState(String title, String text) {
        startForeground(NOTI_ID, buildNotification(title, text));
    }

    private void notifyIdle() {
        startForeground(NOTI_ID, buildNotification("已挂后台", "有回复会在这里提醒你"));
    }

    private void closeWs() {
        if (ws != null) {
            try { ws.close(); } catch (Exception ignored) {}
            ws = null;
        }
    }

    @Override
    public void onDestroy() {
        closeWs();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
