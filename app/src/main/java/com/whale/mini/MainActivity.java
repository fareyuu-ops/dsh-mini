package com.whale.mini;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * DSH 的安卓原生极简客户端。
 *
 * 界面走深色 + 气泡（对齐 DSH 的观感），消息区从「一行行字符串」换成
 * 自定义 adapter；助手回复按服务端给的时间轴（assistant/message.data.stream）
 * 逐段回放，节奏和桌面端一致。
 */
public class MainActivity extends Activity {

    // ---- DSH 风格配色 ----
    private static final int C_BG      = 0xFF0F0F12;
    private static final int C_PANEL   = 0xFF141419;
    private static final int C_AI      = 0xFF1C1C23;
    private static final int C_ME      = 0xFF2A4E8F;
    private static final int C_TEXT    = 0xFFE9E9EE;
    private static final int C_MUTED   = 0xFF8B8B97;
    private static final int C_ACCENT  = 0xFF4C8DFF;
    private static final int C_INPUT   = 0xFF1E1E26;
    private static final int C_CODE_BG = 0xFF101015;
    private static final int C_CODE_FG = 0xFF9BD1FF;

    private EditText etUrl, etToken, etInput;
    private TextView btnConnect, btnSend, btnBack, btnImage;
    private ListView list;
    private TextView tvStatus, tvTitle, fabMenu;
    private ImageView ivAvatar;
    private LinearLayout headerConn, chatHeader, headerBrand, bottomNew, bottomChat;
    private android.graphics.drawable.Drawable listBgDrawable, chatBgDrawable;

    private static final int REQ_IMAGE = 1001;
    /** 正在系统相册里选图：这时候 onPause 不能断 WS，回来也不该触发重连。 */
    private boolean selectingImage = false;
    private byte[] pendingImageBytes;
    private String pendingImageMime;
    private String pendingImageName;

    private DshApi api;
    private WsClient ws;
    private String currentSession;
    private boolean inChat = false;

    private final List<String> sessions = new ArrayList<String>();
    private final java.util.HashMap<String, Long> sessionUpdated = new java.util.HashMap<String, Long>();
    private final List<Msg> msgs = new ArrayList<Msg>();
    private ArrayAdapter<String> sessionsAdapter;
    private MsgAdapter msgAdapter;

    private final Handler ui = new Handler(Looper.getMainLooper());

    /** 本地先回显的用户消息，等服务端推同一条 user/message 时跳过，避免重复。 */
    private String pendingUserText = null;

    /** step/start 先挂出来的「思考中」胶囊；assistant/message 到了就复用它。 */
    private Msg pendingThinking = null;

    /** 每次新回放/切会话 +1，旧的回放回调据此作废。 */
    private int playbackToken = 0;

    /** markdown 里的图片（只认 http/https，手机才够得着）。 */
    private static final java.util.regex.Pattern IMG_MD = java.util.regex.Pattern.compile(
            "!\\[[^\\]]*\\]\\((https?://[^)\\s]+)\\)");
    private final java.util.HashMap<String, android.graphics.Bitmap> imgCache =
            new java.util.HashMap<String, android.graphics.Bitmap>();

    static class Msg {
        int role;   // 0 = 我，1 = 助手
        String text;
        boolean thinking;   // 空气泡 + 摇尾巴，等第一个字
        String imageId;     // content 里 image 块的 attachmentId（要去 session/attachment 读）
        android.graphics.Bitmap localBmp;   // 自己刚发的图，本地直接显示
        Msg(int r, String t) { role = r; text = t; }
        Msg(int r, String t, boolean th) { role = r; text = t; thinking = th; }
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(C_BG);
        getWindow().setNavigationBarColor(C_BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_BG);

        // ---- 顶部：连接栏 ----
        headerConn = new LinearLayout(this);
        headerConn.setOrientation(LinearLayout.HORIZONTAL);
        headerConn.setGravity(Gravity.CENTER_VERTICAL);
        headerConn.setPadding(dp(12), dp(10), dp(12), dp(4));

        etUrl = smallInput("http://192.168.1.2:19387");
        etToken = smallInput("token");
        etToken.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        etUrl.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f));

        btnConnect = chip("连接", C_ACCENT, 0xFF0B1B33);

        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        gap.leftMargin = dp(8);
        headerConn.addView(etUrl);
        headerConn.addView(etToken, withLeftMargin(1f, 8));
        headerConn.addView(btnConnect, gap);

        tvStatus = new TextView(this);
        tvStatus.setTextColor(C_MUTED);
        tvStatus.setTextSize(11.5f);
        tvStatus.setPadding(dp(14), dp(2), dp(14), dp(6));
        tvStatus.setText("未连接");

        // ---- 中部：消息/会话列表 ----
        list = new ListView(this);
        list.setBackgroundColor(C_BG);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setVerticalScrollBarEnabled(false);
        list.setClipToPadding(false);
        list.setPadding(0, dp(6), 0, dp(6));

        // ---- 底部：聊天输入栏 ----
        bottomChat = new LinearLayout(this);
        bottomChat.setOrientation(LinearLayout.HORIZONTAL);
        bottomChat.setGravity(Gravity.CENTER_VERTICAL);
        bottomChat.setPadding(dp(10), dp(8), dp(10), dp(10));
        bottomChat.setBackgroundColor(C_PANEL);
        bottomChat.setVisibility(View.GONE);

        // ---- 列表页品牌栏：头像 + 软件名 ----
        headerBrand = new LinearLayout(this);
        headerBrand.setOrientation(LinearLayout.HORIZONTAL);
        headerBrand.setGravity(Gravity.CENTER_VERTICAL);
        headerBrand.setPadding(dp(16), dp(14), dp(16), dp(8));

        ivAvatar = new ImageView(this);
        ivAvatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        ivAvatar.setBackground(round(0xFF1C2E5A, 23));
        ivAvatar.setImageResource(R.drawable.tail_01);
        headerBrand.addView(ivAvatar, new LinearLayout.LayoutParams(dp(46), dp(46)));

        TextView brand = new TextView(this);
        brand.setText("DSH Mini");
        brand.setTextColor(C_TEXT);
        brand.setTextSize(19);
        brand.setTypeface(Typeface.DEFAULT_BOLD);
        headerBrand.addView(brand, withLeftMargin(1f, 12));

        TextView btnGear = chip("⚙", 0xFF24242D, C_TEXT);
        btnGear.setTextSize(17);
        btnGear.setPadding(dp(12), dp(2), dp(12), dp(4));
        btnGear.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openSettings(); }
        });
        headerBrand.addView(btnGear);

        // ---- 底部：列表页的「新建对话」 ----
        bottomNew = new LinearLayout(this);
        bottomNew.setOrientation(LinearLayout.HORIZONTAL);
        bottomNew.setGravity(Gravity.CENTER);
        bottomNew.setPadding(dp(10), dp(10), dp(10), dp(12));
        bottomNew.setBackgroundColor(C_PANEL);

        TextView btnNew = chip("＋  新建对话", C_ACCENT, 0xFF0B1B33);
        btnNew.setTextSize(15);
        btnNew.setPadding(dp(20), dp(12), dp(20), dp(12));
        btnNew.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { newSession(); }
        });
        bottomNew.addView(btnNew);

        btnBack = chip("‹", 0xFF24242D, C_TEXT);
        btnBack.setTextSize(20);
        btnBack.setPadding(dp(14), dp(2), dp(16), dp(4));
        btnBack.setEnabled(false);

        // ---- 会话标题栏（聊天时显示：‹ 返回 + 会话名） ----
        chatHeader = new LinearLayout(this);
        chatHeader.setOrientation(LinearLayout.HORIZONTAL);
        chatHeader.setGravity(Gravity.CENTER_VERTICAL);
        chatHeader.setPadding(dp(8), dp(6), dp(14), dp(4));
        chatHeader.setVisibility(View.GONE);

        tvTitle = new TextView(this);
        tvTitle.setTextColor(C_TEXT);
        tvTitle.setTextSize(15);
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
        tvTitle.setSingleLine(true);
        tvTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);

        chatHeader.addView(btnBack);
        chatHeader.addView(tvTitle, withLeftMargin(1f, 6));

        fabMenu = chip("⋯", 0xFF24242D, C_TEXT);
        fabMenu.setTextSize(18);
        fabMenu.setPadding(dp(12), dp(2), dp(12), dp(4));
        fabMenu.setVisibility(View.GONE);
        fabMenu.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showChatMenu(v); }
        });
        chatHeader.addView(fabMenu);

        btnImage = chip("＋", 0xFF24242D, C_TEXT);
        btnImage.setTextSize(18);
        btnImage.setPadding(dp(12), dp(2), dp(12), dp(4));
        btnImage.setAlpha(0.45f);

        etInput = new EditText(this);
        etInput.setHint("输入消息");
        etInput.setHintTextColor(C_MUTED);
        etInput.setTextColor(C_TEXT);
        etInput.setTextSize(15);
        etInput.setSingleLine(true);
        etInput.setImeOptions(EditorInfo.IME_ACTION_SEND);
        etInput.setBackground(round(C_INPUT, 22));
        etInput.setPadding(dp(16), dp(10), dp(16), dp(10));
        etInput.setLayoutParams(withLeftMargin(1f, 8));

        btnSend = chip("发送", C_ACCENT, 0xFF0B1B33);
        btnSend.setEnabled(false);
        btnSend.setAlpha(0.45f);

        bottomChat.addView(btnImage);
        bottomChat.addView(etInput);
        bottomChat.addView(btnSend, withLeftMargin(ViewGroup.LayoutParams.WRAP_CONTENT, 8));

        root.addView(headerBrand);
        root.addView(headerConn);
        root.addView(chatHeader);
        root.addView(tvStatus);
        root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(bottomNew);
        root.addView(bottomChat);

        setContentView(root);

        sessionsAdapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, sessions) {
            @Override
            public View getView(int pos, View cv, ViewGroup parent) {
                String s = sessions.get(pos);
                int nl = s.lastIndexOf('\n');
                String title = nl >= 0 ? s.substring(0, nl) : s;
                String sid = nl >= 0 ? s.substring(nl + 1).trim() : s;

                LinearLayout row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(16), dp(14), dp(16), dp(14));

                TextView t = new TextView(MainActivity.this);
                t.setText(title);
                t.setTextColor(C_TEXT);
                t.setTextSize(15);
                t.setSingleLine(true);
                t.setEllipsize(android.text.TextUtils.TruncateAt.END);

                TextView ago = new TextView(MainActivity.this);
                ago.setText(relTime(sid));
                ago.setTextColor(C_MUTED);
                ago.setTextSize(12);

                row.addView(t, withLeftMargin(1f, 0));
                row.addView(ago);
                return row;
            }
        };
        msgAdapter = new MsgAdapter();

        btnConnect.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { doConnect(); }
        });
        btnSend.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { doSend(); }
        });
        btnBack.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showSessions(); }
        });
        btnImage.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pickImage(); }
        });
        etInput.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int actionId, android.view.KeyEvent e) {
                if (actionId == EditorInfo.IME_ACTION_SEND) { doSend(); return true; }
                return false;
            }
        });
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                if (!inChat && pos < sessions.size()) openSession(sessions.get(pos));
            }
        });

        // 连上之后连接栏会收起；点状态栏可以再把它叫出来改地址
        tvStatus.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                headerConn.setVisibility(headerConn.getVisibility() == View.VISIBLE
                        ? View.GONE : View.VISIBLE);
            }
        });

        showSessions();
        loadLogin();
    }

    // ---- 记住地址和 token（关掉重开不用再填） ----

    private void saveLogin(String url, String token) {
        try {
            getSharedPreferences("dshmini", MODE_PRIVATE).edit()
                    .putString("url", url)
                    .putString("token", token)
                    .apply();
        } catch (Throwable ignored) {
        }
    }

    private void loadLogin() {
        try {
            SharedPreferences sp = getSharedPreferences("dshmini", MODE_PRIVATE);
            String u = sp.getString("url", "");
            String t = sp.getString("token", "");
            if (!TextUtils.isEmpty(u)) etUrl.setText(u);
            if (!TextUtils.isEmpty(t)) etToken.setText(t);
            if (!TextUtils.isEmpty(u) && !TextUtils.isEmpty(t)) {
                status("自动连接…");
                doConnect();
            }
        } catch (Throwable ignored) {
        }
    }

    private void saveLastSession(String sid) {
        try {
            getSharedPreferences("dshmini", MODE_PRIVATE).edit().putString("last", sid).apply();
        } catch (Throwable ignored) {
        }
    }

    private String lastSession() {
        try {
            return getSharedPreferences("dshmini", MODE_PRIVATE).getString("last", "");
        } catch (Throwable ignored) {
            return "";
        }
    }

    // ---- 小工具 ----

    private int dp(int v) {
        return (int) (getResources().getDisplayMetrics().density * v + 0.5f);
    }

    private ImageView imageView(int maxW) {
        ImageView iv = new ImageView(this);
        iv.setAdjustViewBounds(true);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setMaxWidth(maxW);
        iv.setPadding(0, dp(6), 0, 0);
        return iv;
    }

    /** 大图先按比例降采样，免得 OOM。 */
    private android.graphics.Bitmap decodeScaled(byte[] bytes, int maxDim) {
        if (bytes == null || bytes.length == 0) return null;
        android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;
        int scale = 1;
        int w = o.outWidth;
        while (w / scale > maxDim) scale *= 2;
        o.inJustDecodeBounds = false;
        o.inSampleSize = scale;
        try {
            return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
        } catch (Throwable t) {
            return null;
        }
    }

    private GradientDrawable round(int color, int radiusDp) {        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private EditText smallInput(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setHintTextColor(0xFF5A5A66);
        e.setTextColor(C_TEXT);
        e.setTextSize(13);
        e.setSingleLine(true);
        e.setBackground(round(C_INPUT, 12));
        e.setPadding(dp(12), dp(8), dp(12), dp(8));
        return e;
    }

    private TextView chip(String text, int bg, int fg) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(fg);
        tv.setTextSize(14);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(14), dp(8), dp(14), dp(8));
        tv.setBackground(round(bg, 18));
        tv.setClickable(true);
        return tv;
    }

    private LinearLayout.LayoutParams withLeftMargin(int w, int left) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(left);
        return lp;
    }

    private LinearLayout.LayoutParams withLeftMargin(float weight, int left) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, weight);
        lp.leftMargin = dp(left);
        return lp;
    }

    // ---- 消息渲染 ----

    class MsgAdapter extends BaseAdapter {
        @Override public int getCount() { return msgs.size(); }
        @Override public Object getItem(int i) { return msgs.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override public View getView(int pos, View cv, ViewGroup parent) {
            Msg m = msgs.get(pos);

            LinearLayout row = new LinearLayout(MainActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(dp(12), dp(3), dp(12), dp(3));
            row.setGravity(m.role == 0 ? Gravity.END : Gravity.START);

            // 思考中：一条淡色胶囊，鲸鱼尾巴在摆，等第一个字落地
            if (m.thinking && m.text.length() == 0) {
                LinearLayout pill = new LinearLayout(MainActivity.this);
                pill.setOrientation(LinearLayout.HORIZONTAL);
                pill.setGravity(Gravity.CENTER_VERTICAL);
                pill.setBackground(round(C_AI, 18));
                pill.setPadding(dp(13), dp(9), dp(16), dp(9));

                pill.addView(new WhaleTail(MainActivity.this));

                TextView label = new TextView(MainActivity.this);
                label.setText("思考中");
                label.setTextColor(C_MUTED);
                label.setTextSize(13);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.leftMargin = dp(9);
                pill.addView(label, lp);

                row.addView(pill);
                return row;
            }

            LinearLayout col = new LinearLayout(MainActivity.this);
            col.setOrientation(LinearLayout.VERTICAL);

            TextView tv = new TextView(MainActivity.this);
            tv.setTextSize(15);
            tv.setTextColor(C_TEXT);
            tv.setLineSpacing(dp(3), 1f);
            tv.setText(Md.render(m.text, C_CODE_FG, C_CODE_BG));
            tv.setBackground(round(m.role == 0 ? C_ME : C_AI, 18));
            tv.setPadding(dp(14), dp(10), dp(14), dp(10));
            tv.setTextIsSelectable(true);
            int maxW = (int) (getResources().getDisplayMetrics().widthPixels * 0.84f);
            tv.setMaxWidth(maxW);
            if (m.role == 1) tv.setMinWidth(dp(40));
            col.addView(tv);

            // 图片：本地刚发的 → 优先；其次 content 的 attachment；最后 markdown 远程 URL
            if (m.localBmp != null) {
                ImageView iv = imageView(maxW);
                iv.setImageBitmap(m.localBmp);
                col.addView(iv);
            } else if (m.imageId != null) {
                ImageView iv = imageView(maxW);
                String key = "att:" + m.imageId;
                iv.setTag(key);
                android.graphics.Bitmap cached = imgCache.get(key);
                if (cached != null) {
                    iv.setImageBitmap(cached);
                } else {
                    iv.setMinimumHeight(dp(48));
                    loadAttachmentAsync(m.imageId, iv);
                }
                col.addView(iv);
            } else {
                String imgUrl = firstImageUrl(m.text);
                if (imgUrl != null) {
                    ImageView iv = imageView(maxW);
                    iv.setTag(imgUrl);
                    android.graphics.Bitmap cached = imgCache.get(imgUrl);
                    if (cached != null) {
                        iv.setImageBitmap(cached);
                    } else {
                        iv.setMinimumHeight(dp(36));
                        loadImageAsync(imgUrl, iv);
                    }
                    col.addView(iv);
                }
            }

            row.addView(col);
            return row;
        }
    }

    private String firstImageUrl(String text) {
        if (text == null) return null;
        java.util.regex.Matcher m = IMG_MD.matcher(text);
        return m.find() ? m.group(1) : null;
    }

    private void loadAttachmentAsync(final String attId, final ImageView iv) {
        final String sid = currentSession;
        if (sid == null || api == null) return;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    byte[] bytes = api.readAttachment(sid, attId);
                    final android.graphics.Bitmap bmp = decodeScaled(bytes, 1400);
                    if (bmp == null) return;
                    String key = "att:" + attId;
                    imgCache.put(key, bmp);
                    ui.post(new Runnable() {
                        @Override public void run() {
                            if (("att:" + attId).equals(iv.getTag())) {
                                iv.setMinimumHeight(0);
                                iv.setImageBitmap(bmp);
                            }
                        }
                    });
                } catch (Exception ignored) {
                }
            }
        }).start();
    }

    private void loadImageAsync(final String url, final ImageView iv) {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    java.net.HttpURLConnection c = (java.net.HttpURLConnection)
                            new java.net.URL(url).openConnection();
                    c.setConnectTimeout(10000);
                    c.setReadTimeout(20000);
                    java.io.InputStream in = c.getInputStream();
                    final android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeStream(in);
                    in.close();
                    if (bmp == null) return;
                    imgCache.put(url, bmp);
                    ui.post(new Runnable() {
                        @Override public void run() {
                            if (url.equals(iv.getTag())) {
                                iv.setMinimumHeight(0);
                                iv.setImageBitmap(bmp);
                            }
                        }
                    });
                } catch (Exception ignored) {
                }
            }
        }).start();
    }

    private void scrollEnd() {
        if (msgs.isEmpty()) return;
        list.setSelection(msgs.size() - 1);
    }

    private void clearMsgs() {
        playbackToken++;
        pendingThinking = null;
        ui.removeCallbacksAndMessages(null);
        msgs.clear();
        msgAdapter.notifyDataSetChanged();
    }

    // ---- 动作 ----

    private void doConnect() {
        final String url = etUrl.getText().toString().trim();
        final String token = etToken.getText().toString().trim();
        if (TextUtils.isEmpty(url) || TextUtils.isEmpty(token)) {
            toast("先填地址和 token");
            return;
        }
        status("连接中…");
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    DshApi a = new DshApi(url);
                    a.login(token);
                    final JSONArray items = a.listSessions();
                    api = a;
                    final java.util.HashMap<String, Long> times = new java.util.HashMap<String, Long>();
                    final List<String> names = parseNames(items, times);
                    ui.post(new Runnable() {
                        @Override public void run() {
                            sessions.clear();
                            sessions.addAll(names);
                            sessionUpdated.clear();
                            sessionUpdated.putAll(times);
                            showSessions();
                            headerConn.setVisibility(View.GONE);
                            if (etUrl.getText().length() == 0) etUrl.setText(api.origin);
                            saveLogin(url, token);
                            status("已连接 · " + sessions.size() + " 个会话");

                            // 重开时直接回到上次那个会话
                            String last = lastSession();
                            if (!TextUtils.isEmpty(last)) {
                                for (String s : sessions) {
                                    if (s.endsWith(last)) {
                                        openSession(last);
                                        return;
                                    }
                                }
                            }
                        }
                    });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        @Override public void run() { status("连接失败: " + e.getMessage()); }
                    });
                }
            }
        }).start();
    }

    private void showSessions() {
        inChat = false;
        currentSession = null;
        pendingUserText = null;
        closeWs();
        clearMsgs();
        list.setAdapter(sessionsAdapter);
        applyListBackground();
        btnBack.setEnabled(false);
        btnSend.setEnabled(false);
        btnSend.setAlpha(0.45f);
        btnImage.setText("＋");
        btnImage.setAlpha(0.45f);
        pendingImageBytes = null;
        pendingImageMime = null;
        pendingImageName = null;
        etInput.setHint("先选一个会话");
        etInput.setEnabled(false);
        if (api != null) status(sessions.size() + " 个会话");
        chatHeader.setVisibility(View.GONE);
        headerBrand.setVisibility(View.VISIBLE);
        bottomNew.setVisibility(View.VISIBLE);
        bottomChat.setVisibility(View.GONE);
        fabMenu.setVisibility(View.GONE);
    }

    private void newSession() {
        if (api == null) {
            toast("还没连接");
            return;
        }
        status("新建对话…");
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    final String sid = api.createSession();
                    final JSONArray items = api.listSessions();
                    final java.util.HashMap<String, Long> times = new java.util.HashMap<String, Long>();
                    final List<String> names = parseNames(items, times);
                    ui.post(new Runnable() {
                        @Override public void run() {
                            sessions.clear();
                            sessions.addAll(names);
                            sessionUpdated.clear();
                            sessionUpdated.putAll(times);
                            openSession(sid);
                        }
                    });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        @Override public void run() { status("新建失败: " + e.getMessage()); }
                    });
                }
            }
        }).start();
    }

    private void openSettings() {
        startActivity(new Intent(this, SettingsActivity.class));
    }

    /** 从设置里读头像/背景并应用（设置页返回时会再调一次）。 */
    private void applySettings() {
        SharedPreferences sp = getSharedPreferences("dshmini", MODE_PRIVATE);
        if (ivAvatar != null) {
            android.graphics.Bitmap bmp = decodeFile(sp.getString("avatar", ""), 200);
            if (bmp != null) ivAvatar.setImageBitmap(bmp);
            else ivAvatar.setImageResource(R.drawable.tail_01);
        }
        listBgDrawable = loadBg(sp.getString("listBg", ""));
        chatBgDrawable = loadBg(sp.getString("chatBg", ""));
        applyListBackground();
    }

    private android.graphics.drawable.Drawable loadBg(String path) {
        android.graphics.Bitmap bmp = decodeFile(path, 1600);
        if (bmp == null) return null;
        return new CropDrawable(bmp);
    }

    /** 背景图按「居中裁剪」铺满，不拉伸变形。 */
    static class CropDrawable extends android.graphics.drawable.Drawable {
        private final android.graphics.Bitmap bmp;
        private final android.graphics.Paint paint =
                new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG
                        | android.graphics.Paint.DITHER_FLAG);

        CropDrawable(android.graphics.Bitmap b) {
            bmp = b;
        }

        @Override public void draw(android.graphics.Canvas canvas) {
            android.graphics.Rect b = getBounds();
            if (b.isEmpty() || bmp == null || bmp.isRecycled()) return;
            float s = Math.max((float) b.width() / bmp.getWidth(),
                    (float) b.height() / bmp.getHeight());
            int w = Math.round(bmp.getWidth() * s);
            int h = Math.round(bmp.getHeight() * s);
            int l = b.left + (b.width() - w) / 2;
            int t = b.top + (b.height() - h) / 2;
            canvas.drawBitmap(bmp, null,
                    new android.graphics.Rect(l, t, l + w, t + h), paint);
        }

        @Override public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override public void setColorFilter(android.graphics.ColorFilter cf) {
            paint.setColorFilter(cf);
        }

        @Override public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }
    }

    private void applyListBackground() {
        if (list == null) return;
        android.graphics.drawable.Drawable d = inChat ? chatBgDrawable : listBgDrawable;
        if (d != null) list.setBackground(d);
        else list.setBackgroundColor(C_BG);
    }

    private static android.graphics.Bitmap decodeFile(String path, int maxDim) {
        if (TextUtils.isEmpty(path)) return null;
        if (!new java.io.File(path).exists()) return null;
        try {
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeFile(path, o);
            if (o.outWidth <= 0) return null;
            int scale = 1;
            while (o.outWidth / scale > maxDim || o.outHeight / scale > maxDim) scale *= 2;
            o.inJustDecodeBounds = false;
            o.inSampleSize = scale;
            return android.graphics.BitmapFactory.decodeFile(path, o);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 重新拉一次会话列表（空会话会被筛掉，等于「清爽一下」）。 */
    private void refreshSessions() {
        if (api == null) {
            toast("还没连接");
            return;
        }
        status("刷新中…");
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    final JSONArray items = api.listSessions();
                    final java.util.HashMap<String, Long> times = new java.util.HashMap<String, Long>();
                    final List<String> names = parseNames(items, times);
                    ui.post(new Runnable() {
                        @Override public void run() {
                            sessions.clear();
                            sessions.addAll(names);
                            sessionUpdated.clear();
                            sessionUpdated.putAll(times);
                            sessionsAdapter.notifyDataSetChanged();
                            status(sessions.size() + " 个会话");
                        }
                    });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        @Override public void run() { status("刷新失败: " + e.getMessage()); }
                    });
                }
            }
        }).start();
    }

    /** 聊天页右下角「⋯」的菜单。以后想加功能往这儿添。 */
    private void showChatMenu(View anchor) {
        android.widget.PopupMenu pm = new android.widget.PopupMenu(this, anchor);
        pm.getMenu().add("刷新");
        pm.getMenu().add("复制会话 ID");
        pm.setOnMenuItemClickListener(new android.widget.PopupMenu.OnMenuItemClickListener() {
            @Override public boolean onMenuItemClick(android.view.MenuItem item) {
                String t = String.valueOf(item.getTitle());
                if ("刷新".equals(t)) {
                    if (inChat && currentSession != null) openSession(currentSession);
                    else refreshSessions();
                    return true;
                }
                if ("复制会话 ID".equals(t)) {
                    copySessionId();
                    return true;
                }
                return false;
            }
        });
        pm.show();
    }

    private void copySessionId() {
        if (currentSession == null) return;
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(android.content.ClipData.newPlainText("sessionId", currentSession));
                toast("已复制会话 ID");
            }
        } catch (Exception ignored) {
        }
    }

    private String relTime(String sid) {
        Long t = sessionUpdated.get(sid);
        if (t == null || t <= 0) return "";
        long d = System.currentTimeMillis() - t;
        if (d < 0) d = 0;
        if (d < 60_000L) return "刚刚";
        if (d < 3_600_000L) return (d / 60_000L) + " 分钟前";
        if (d < 86_400_000L) return (d / 3_600_000L) + " 小时前";
        if (d < 30L * 86_400_000L) return (d / 86_400_000L) + " 天前";
        return (d / (30L * 86_400_000L)) + " 个月前";
    }

    /** 把 session/list 的结果转成列表行（标题\n sid），顺带收集 updatedAt。 */
    private List<String> parseNames(JSONArray items, java.util.HashMap<String, Long> times) {
        List<String> names = new ArrayList<String>();
        if (items == null) return names;
        for (int i = 0; i < items.length(); i++) {
            JSONObject it = items.optJSONObject(i);
            if (it == null) continue;
            if (it.optBoolean("blank")) continue;   // 空会话不进列表（桌面端也这样）
            String sid = it.optString("sessionId");
            String title = "";
            JSONObject proj = it.optJSONObject("projections");
            if (proj != null) {
                JSONObject vals = proj.optJSONObject("values");
                if (vals != null) title = vals.optString("title", "");
            }
            names.add(cleanTitle(title) + "\n" + sid);
            if (times != null) times.put(sid, it.optLong("updatedAt", 0));
        }
        return names;
    }

    /** 服务端空标题会给字符串 "null"，这里统一收口。 */
    private String cleanTitle(String t) {
        if (t == null || t.length() == 0 || "null".equals(t)) return "(未命名)";
        return t;
    }

    private String titleOf(String sid) {
        for (String s : sessions) {
            int nl = s.lastIndexOf('\n');
            if (nl >= 0 && s.substring(nl + 1).trim().equals(sid)) return s.substring(0, nl);
        }
        return "新对话";
    }

    private void openSession(String rowOrSid) {
        int nl = rowOrSid.lastIndexOf('\n');
        final String sid = nl >= 0 ? rowOrSid.substring(nl + 1).trim() : rowOrSid.trim();
        currentSession = sid;
        saveLastSession(sid);
        inChat = true;
        pendingUserText = null;
        clearMsgs();
        list.setAdapter(msgAdapter);
        applyListBackground();
        headerBrand.setVisibility(View.GONE);
        chatHeader.setVisibility(View.VISIBLE);
        tvTitle.setText(titleOf(sid));
        bottomNew.setVisibility(View.GONE);
        bottomChat.setVisibility(View.VISIBLE);
        fabMenu.setVisibility(View.VISIBLE);
        btnBack.setEnabled(true);
        btnSend.setEnabled(true);
        btnSend.setAlpha(1f);
        btnImage.setText("＋");
        btnImage.setAlpha(1f);
        // 注意：这里不动 pendingImage*，否则「选图 → 回 onResume 重连」会把图冲掉
        etInput.setEnabled(true);
        etInput.setHint("输入消息");
        status("打开会话…");
        closeWs();

        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    ws = api.openFollow(sid, new WsClient.Listener() {
                        @Override public void onOpen() {
                            ui.post(new Runnable() { @Override public void run() { status("已连接"); } });
                        }
                        @Override public void onText(String text) {
                            handleWsFrame(text);
                        }
                        @Override public void onClose(int code, String reason) {
                            ui.post(new Runnable() {
                                @Override public void run() { status("流已断开"); }
                            });
                        }
                        @Override public void onError(final Exception e) {
                            ui.post(new Runnable() {
                                @Override public void run() { status("流错误: " + e.getMessage()); }
                            });
                        }
                    });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        @Override public void run() { status("打开流失败: " + e.getMessage()); }
                    });
                }
            }
        }).start();
    }

    private void handleWsFrame(String text) {
        try {
            JSONObject o = new JSONObject(text);
            String type = o.optString("type");
            if ("error".equals(type)) {
                JSONObject err = o.optJSONObject("error");
                final String msg = err != null ? err.optString("message", err.toString()) : text;
                ui.post(new Runnable() { @Override public void run() { status("流错误: " + msg); } });
                return;
            }
            if (!"item".equals(type)) return;
            JSONObject val = o.optJSONObject("value");
            if (val == null) return;
            String vtype = val.optString("type");

            if ("snapshot".equals(vtype)) {
                JSONArray records = val.optJSONArray("records");
                final List<Msg> loaded = new ArrayList<Msg>();
                if (records != null) {
                    for (int i = 0; i < records.length(); i++) {
                        JSONObject rec = records.optJSONObject(i);
                        if (rec == null) continue;
                        JSONObject ev = rec.optJSONObject("event");
                        if (ev == null) continue;
                        Msg m = renderEvent(ev);
                        if (m != null) loaded.add(m);
                    }
                }
                ui.post(new Runnable() {
                    @Override public void run() {
                        clearMsgs();
                        msgs.addAll(loaded);
                        msgAdapter.notifyDataSetChanged();
                        scrollEnd();
                    }
                });
                return;
            }

            JSONObject ev = val.optJSONObject("event");
            if (ev == null) return;
            final String evType = ev.optString("type");

            // 模型开始这一 step 时先挂一个摇尾巴的胶囊——真正「等」的是这段时间
            if ("step/start".equals(evType)) {
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (pendingThinking != null && msgs.contains(pendingThinking)) return;
                        pendingThinking = new Msg(1, "", true);
                        msgs.add(pendingThinking);
                        msgAdapter.notifyDataSetChanged();
                        scrollEnd();
                    }
                });
                return;
            }

            if ("step/end".equals(evType) || "turn/end".equals(evType)) {
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (pendingThinking == null) return;
                        if (msgs.remove(pendingThinking)) msgAdapter.notifyDataSetChanged();
                        pendingThinking = null;
                    }
                });
                return;
            }

            if ("assistant/message".equals(evType)) {
                final JSONObject data = ev.optJSONObject("data");
                if (data == null) return;
                ui.post(new Runnable() { @Override public void run() { playAssistant(data); } });
                return;
            }

            final Msg m = renderEvent(ev);
            if (m == null) return;
            ui.post(new Runnable() {
                @Override public void run() {
                    if (m.role == 0 && pendingUserText != null && pendingUserText.equals(m.text)) {
                        pendingUserText = null;
                        return;
                    }
                    msgs.add(m);
                    msgAdapter.notifyDataSetChanged();
                    scrollEnd();
                }
            });
        } catch (Exception ignored) {
        }
    }

    /**
     * 助手消息按时间轴回放：stream 里的 text-chunks 携带 texts[]（切好的片段）
     * 和 dt[]（每段之前的毫秒间隔）。按 dt 累加延迟逐段追加，就是桌面端那个节奏。
     */
    private void playAssistant(final JSONObject data) {
        pendingUserText = null;

        // 先看这一步到底有没有正文：工具调用/空步骤没有 text-chunks，
        // 以前照样建气泡，结果列表里挂出一串灰色空圆。
        JSONArray stream = data.optJSONArray("stream");
        boolean hasText = false;
        if (stream != null) {
            for (int i = 0; i < stream.length(); i++) {
                JSONObject s = stream.optJSONObject(i);
                if (s != null && "text-chunks".equals(s.optString("type"))) {
                    hasText = true;
                    break;
                }
            }
        }
        String fallback = extractText(data);
        if (!hasText && TextUtils.isEmpty(fallback)) {
            // 没有正文就什么都不建；思考中胶囊留着，交给 step/end 清
            return;
        }

        final Msg m;
        if (pendingThinking != null && msgs.contains(pendingThinking)) {
            m = pendingThinking;   // 复用 step/start 挂出来的那一颗，别再多一个气泡
            m.thinking = true;
        } else {
            m = new Msg(1, "", true);
            msgs.add(m);
        }
        pendingThinking = null;
        msgAdapter.notifyDataSetChanged();
        scrollEnd();

        if (stream == null) {
            m.thinking = false;
            m.text = fallback;
            msgAdapter.notifyDataSetChanged();
            scrollEnd();
            return;
        }

        final int token = ++playbackToken;
        long acc = 0;
        boolean any = false;
        for (int i = 0; i < stream.length(); i++) {
            JSONObject s = stream.optJSONObject(i);
            if (s == null) continue;
            if (!"text-chunks".equals(s.optString("type"))) continue;
            JSONArray texts = s.optJSONArray("texts");
            JSONArray dts = s.optJSONArray("dt");
            if (texts == null) continue;
            any = true;
            for (int j = 0; j < texts.length(); j++) {
                final String piece = texts.optString(j, "");
                if (piece.length() == 0) continue;
                long d = (dts != null && j < dts.length()) ? dts.optLong(j, 0) : 0;
                acc += Math.max(0, d);
                ui.postDelayed(new Runnable() {
                    @Override public void run() {
                        if (token != playbackToken) return;
                        m.thinking = false;
                        m.text = m.text + piece;
                        msgAdapter.notifyDataSetChanged();
                        scrollEnd();
                    }
                }, acc);
            }
        }

        if (!any) {
            m.thinking = false;
            m.text = extractText(data);
            msgAdapter.notifyDataSetChanged();
            scrollEnd();
        }
    }

    /** 把一条会话事件转成消息；不关心的返回 null。 */
    private Msg renderEvent(JSONObject ev) {
        try {
            String type = ev.optString("type");
            if ("user/message".equals(type)) {
                JSONObject data = ev.optJSONObject("data");
                // 只显示真人输入：插件注入（system-reminder / 记忆 / 技能目录）
                // 也走 user/message，source.kind 是 "plugin"。
                if (data != null) {
                    JSONObject src = data.optJSONObject("source");
                    String kind = src != null ? src.optString("kind", "user") : "user";
                    if (!"user".equals(kind)) return null;
                }
                String t = extractText(data);
                String id = firstImageId(data);
                if (TextUtils.isEmpty(t) && id == null) return null;
                Msg m = new Msg(0, t);
                m.imageId = id;
                return m;
            }
            if ("assistant/message".equals(type)) {
                JSONObject data = ev.optJSONObject("data");
                String t = extractText(data);
                String id = firstImageId(data);
                if (TextUtils.isEmpty(t) && id == null) return null;
                Msg m = new Msg(1, t);
                m.imageId = id;
                return m;
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 从 content 里找 image 块的 attachmentId（历史消息里图片只有这个引用）。 */
    private String firstImageId(JSONObject data) {
        if (data == null) return null;
        JSONArray content = data.optJSONArray("content");
        if (content == null) {
            JSONObject message = data.optJSONObject("message");
            if (message != null) content = message.optJSONArray("content");
        }
        if (content == null) return null;
        for (int i = 0; i < content.length(); i++) {
            JSONObject b = content.optJSONObject(i);
            if (b == null || !"image".equals(b.optString("type"))) continue;
            JSONObject att = b.optJSONObject("attachment");
            if (att != null) {
                String id = att.optString("attachmentId", "");
                if (id.length() > 0) return id;
            }
        }
        return null;
    }

    private String extractText(JSONObject data) {        if (data == null) return "";
        // assistant/message 是 {turn, step, message:{content:[...]}}，
        // user/message 是 {content:[...]} —— 两种都接住。
        JSONArray content = data.optJSONArray("content");
        if (content == null) {
            JSONObject message = data.optJSONObject("message");
            if (message != null) content = message.optJSONArray("content");
        }
        if (content == null) content = data.optJSONArray("prompt");
        if (content == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < content.length(); i++) {
            JSONObject b = content.optJSONObject(i);
            if (b == null) continue;
            // 只要 text 块：reasoning 是思考草稿，不显示
            if ("text".equals(b.optString("type"))) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(b.optString("text", ""));
            }
        }
        return sb.toString();
    }

    private void doSend() {
        final String text = etInput.getText().toString().trim();
        final byte[] img = pendingImageBytes;
        final String mime = pendingImageMime;
        final String name = pendingImageName;
        if (TextUtils.isEmpty(text) && img == null) return;
        if (api == null || currentSession == null) return;
        etInput.setText("");
        pendingImageBytes = null;
        pendingImageMime = null;
        pendingImageName = null;

        // 本地先回显，服务端稍后会推同一条 user/message，在 handleWsFrame 里去重
        pendingUserText = text;
        String echo = img != null ? ("【图片】" + (TextUtils.isEmpty(text) ? "" : " " + text)) : text;
        Msg echoMsg = new Msg(0, echo);
        if (img != null) echoMsg.localBmp = decodeScaled(img, 1400);
        msgs.add(echoMsg);
        msgAdapter.notifyDataSetChanged();
        scrollEnd();

        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    api.sendPrompt(currentSession, text, img, mime, name);
                    ui.post(new Runnable() { @Override public void run() { status("已发送"); } });
                } catch (final Exception e) {
                    ui.post(new Runnable() { @Override public void run() { status("发送失败: " + e.getMessage()); } });
                }
            }
        }).start();
    }

    // ---- 选图 ----

    private void pickImage() {
        if (currentSession == null) {
            toast("先进一个会话再选图");
            return;
        }
        Intent it = new Intent(Intent.ACTION_GET_CONTENT);
        it.setType("image/*");
        it.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            selectingImage = true;
            startActivityForResult(it, REQ_IMAGE);
        } catch (Exception e) {
            selectingImage = false;
            toast("打不开相册: " + e.getMessage());
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        selectingImage = false;
        if (req != REQ_IMAGE || res != RESULT_OK || data == null) return;
        final Uri uri = data.getData();
        if (uri == null) return;
        status("读取图片…");
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    android.content.ContentResolver cr = getContentResolver();
                    String mime = cr.getType(uri);
                    if (TextUtils.isEmpty(mime)) mime = "image/png";
                    String name = queryName(uri);
                    java.io.InputStream in = cr.openInputStream(uri);
                    java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[16384];
                    int n;
                    int total = 0;
                    while ((n = in.read(buf)) > 0) {
                        total += n;
                        if (total > 12 * 1024 * 1024) throw new java.io.IOException("图片太大（超过 12MB）");
                        bos.write(buf, 0, n);
                    }
                    in.close();
                    final byte[] bytes = bos.toByteArray();
                    final String m = mime, nm = name;
                    ui.post(new Runnable() {
                        @Override public void run() {
                            pendingImageBytes = bytes;
                            pendingImageMime = m;
                            pendingImageName = nm;
                            btnImage.setAlpha(1f);
                            btnImage.setText("🖼");
                            status("已选图 " + nm + " · " + (bytes.length / 1024) + " KB，发送时带上");
                        }
                    });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        @Override public void run() { status("读图失败: " + e.getMessage()); }
                    });
                }
            }
        }).start();
    }

    private String queryName(Uri uri) {
        String name = "image";
        try {
            android.database.Cursor c = getContentResolver().query(uri, null, null, null, null);
            if (c != null) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0 && c.moveToFirst()) name = c.getString(idx);
                c.close();
            }
        } catch (Exception ignored) {
        }
        return (name == null || name.length() == 0) ? "image" : name;
    }

    private void closeWs() {
        if (ws != null) {
            try { ws.close(); } catch (Exception ignored) {}
            ws = null;
        }
    }

    private void status(String s) {
        tvStatus.setText(s);
    }

    private void toast(String s) {
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onBackPressed() {
        if (inChat) { showSessions(); return; }
        super.onBackPressed();
    }

    // 切到后台时安卓会冻结进程，WS 基本必死（表现为 Software caused connection abort）。
    // 索性主动断开，回前台再按当前会话重连一遍——顺带把断线期间的消息补回来。
    @Override
    protected void onPause() {
        super.onPause();
        if (!selectingImage) closeWs();
    }

    @Override
    protected void onResume() {
        super.onResume();
        applySettings();
        if (selectingImage) return;   // 从相册回来，别把刚选的图冲掉
        if (inChat && currentSession != null && api != null
                && (ws == null || !ws.isOpen())) {
            status("重新连接…");
            openSession(currentSession);
        }
    }

    @Override
    protected void onDestroy() {
        playbackToken++;
        ui.removeCallbacksAndMessages(null);
        closeWs();
        super.onDestroy();
    }
}
