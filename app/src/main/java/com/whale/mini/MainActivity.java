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

import java.io.File;
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

    private EditText etUrl, etToken, etInput;
    private TextView btnConnect, btnSend, btnImage;
    private ListView list;
    private TextView tvStatus, tvTitle, fabMenu;
    private ImageView ivAvatar, btnBack;
    private LinearLayout headerConn, chatHeader, headerBrand, bottomNew, bottomChat, pendingStrip;
    private android.widget.HorizontalScrollView pendingScroll;
    private android.graphics.drawable.Drawable listBgDrawable, chatBgDrawable;

    private static final int REQ_IMAGE = 1001;
    private static final int REQ_CAMERA = 1002;
    private static final int REQ_SPEECH = 1003;
    private static final int REQ_FILE = 1004;
    /** 拍照时写进 ImageProvider 的文件名。 */
    private String cameraName;
    /** 拍照输出的 MediaStore URI。 */
    private Uri cameraUri;
    /** 正在系统相册里选图：这时候 onPause 不能断 WS，回来也不该触发重连。 */
    private boolean selectingImage = false;
    /** 待发送的图片（多张）。static：选图期间 Activity 可能被系统重建，实例字段会丢 */
    private static final List<byte[]> pendingImages = new ArrayList<byte[]>();
    private static final List<String> pendingMimes = new ArrayList<String>();
    private static final List<String> pendingNames = new ArrayList<String>();

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

    /** 状态栏/导航栏图标的明暗：浅色主题要深色图标，否则白图标看不清。 */
    private void applySystemBarIcons() {
        try {
            int flags = getWindow().getDecorView().getSystemUiVisibility();
            if (Theme.dark) {
                flags &= ~android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                flags &= ~0x00000010;   // SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            } else {
                flags |= android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                if (android.os.Build.VERSION.SDK_INT >= 26) {
                    flags |= 0x00000010;
                }
            }
            getWindow().getDecorView().setSystemUiVisibility(flags);
        } catch (Throwable ignored) {
        }
    }

    /** 当前存活的实例：读图是异步的，Activity 可能已被重建，得更新「新的那个」。 */
    private static MainActivity current;

    /** 本实例上次应用的主题；不能用 Theme.dark 比较（设置页会直接改它）。 */
    private boolean appliedDark = true;

    /** 正在等回复（turn 未结束），发送键此时变成「停止」。 */
    private boolean generating = false;

    /** markdown 里的图片（只认 http/https，手机才够得着）。 */
    private static final java.util.regex.Pattern IMG_MD = java.util.regex.Pattern.compile(
            "!\\[[^\\]]*\\]\\((https?://[^)\\s]+)\\)");
    private final java.util.HashMap<String, android.graphics.Bitmap> imgCache =
            new java.util.HashMap<String, android.graphics.Bitmap>();

    static class Msg {
        int role;   // 0 = 我，1 = 助手
        String text;
        boolean thinking;   // 空气泡 + 摇尾巴，等第一个字
        java.util.List<String> imageIds;   // content 里所有 image 块的 attachmentId
        android.graphics.Bitmap localBmp;   // 自己刚发的图，本地直接显示
        java.util.List<android.graphics.Bitmap> localBmps;   // 多张时用这个
        Msg(int r, String t) { role = r; text = t; }
        Msg(int r, String t, boolean th) { role = r; text = t; thinking = th; }
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Theme.dark = getSharedPreferences("dshmini", MODE_PRIVATE).getBoolean("dark", true);
        appliedDark = Theme.dark;
        getWindow().setStatusBarColor(Theme.bg());
        getWindow().setNavigationBarColor(Theme.bg());
        applySystemBarIcons();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Theme.bg());

        // ---- 顶部：连接栏 ----
        headerConn = new LinearLayout(this);
        headerConn.setOrientation(LinearLayout.HORIZONTAL);
        headerConn.setGravity(Gravity.CENTER_VERTICAL);
        headerConn.setPadding(dp(12), dp(10), dp(12), dp(4));

        etUrl = smallInput("http://192.168.1.2:19387");
        etToken = smallInput("token");
        etToken.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        etUrl.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f));

        btnConnect = chip("连接", Theme.accent(), Theme.onAccent());

        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        gap.leftMargin = dp(8);
        headerConn.addView(etUrl);
        headerConn.addView(etToken, withLeftMargin(1f, 8));
        headerConn.addView(btnConnect, gap);

        tvStatus = new TextView(this);
        tvStatus.setTextColor(Theme.muted());
        tvStatus.setTextSize(11.5f);
        tvStatus.setPadding(dp(14), dp(2), dp(14), dp(6));
        tvStatus.setText("未连接");

        // ---- 中部：消息/会话列表 ----
        list = new ListView(this);
        list.setBackgroundColor(Theme.bg());
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
        bottomChat.setBackgroundColor(Theme.panel());
        bottomChat.setVisibility(View.GONE);

        // ---- 列表页品牌栏：头像 + 软件名 ----
        headerBrand = new LinearLayout(this);
        headerBrand.setOrientation(LinearLayout.HORIZONTAL);
        headerBrand.setGravity(Gravity.CENTER_VERTICAL);
        headerBrand.setPadding(dp(16), dp(14), dp(16), dp(8));

        ivAvatar = new ImageView(this);
        ivAvatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        ivAvatar.setBackground(round(Theme.avatarBg(), 23));
        ivAvatar.setImageResource(R.drawable.tail_01);
        ivAvatar.setClipToOutline(true);   // 46dp 方 + 23dp 圆角 → 正圆
        headerBrand.addView(ivAvatar, new LinearLayout.LayoutParams(dp(46), dp(46)));

        TextView brand = new TextView(this);
        brand.setText("DSH Mini");
        brand.setTextColor(Theme.text());
        brand.setTextSize(19);
        brand.setTypeface(Typeface.DEFAULT_BOLD);
        headerBrand.addView(brand, withLeftMargin(1f, 12));

        TextView btnGear = chip("⚙", Theme.chip(), Theme.text());
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
        bottomNew.setBackgroundColor(Theme.panel());

        TextView btnNew = chip("＋  新建对话", Theme.accent(), Theme.onAccent());
        btnNew.setTextSize(15);
        btnNew.setPadding(dp(20), dp(12), dp(20), dp(12));
        btnNew.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { newSession(); }
        });
        bottomNew.addView(btnNew);

        // 返回键用矢量箭头而不是 ‹：引号类字符字形偏左，文字居中永远差一点
        btnBack = new ImageView(this);
        btnBack.setImageResource(R.drawable.ic_back);
        // 矢量图标是白色描边，浅色主题下要染成深色才看得见
        btnBack.setImageTintList(android.content.res.ColorStateList.valueOf(Theme.text()));
        btnBack.setScaleType(ImageView.ScaleType.FIT_CENTER);
        btnBack.setBackground(round(Theme.chip(), 20));
        btnBack.setPadding(dp(11), dp(11), dp(11), dp(11));
        btnBack.setClickable(true);
        btnBack.setEnabled(false);

        // ---- 会话标题栏（聊天时显示：‹ 返回 + 会话名） ----
        chatHeader = new LinearLayout(this);
        chatHeader.setOrientation(LinearLayout.HORIZONTAL);
        chatHeader.setGravity(Gravity.CENTER_VERTICAL);
        chatHeader.setPadding(dp(8), dp(6), dp(14), dp(4));
        chatHeader.setVisibility(View.GONE);

        tvTitle = new TextView(this);
        tvTitle.setTextColor(Theme.text());
        tvTitle.setTextSize(15);
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
        tvTitle.setSingleLine(true);
        tvTitle.setGravity(Gravity.CENTER_VERTICAL);
        tvTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);

        // 三个控件统一 40dp 高、上下零 padding，靠 View 自己垂直居中，避免字形基线差
        chatHeader.addView(btnBack, new LinearLayout.LayoutParams(dp(44), dp(40)));
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, dp(40), 1f);
        titleLp.leftMargin = dp(6);
        chatHeader.addView(tvTitle, titleLp);

        fabMenu = chip("⋯", Theme.chip(), Theme.text());
        fabMenu.setTextSize(18);
        fabMenu.setPadding(dp(12), 0, dp(12), 0);
        fabMenu.setVisibility(View.GONE);
        fabMenu.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showChatMenu(v); }
        });
        chatHeader.addView(fabMenu, new LinearLayout.LayoutParams(dp(42), dp(40)));

        btnImage = chip("＋", Theme.chip(), Theme.text());
        btnImage.setTextSize(18);
        btnImage.setPadding(dp(12), dp(2), dp(12), dp(4));
        btnImage.setAlpha(0.45f);

        etInput = new EditText(this);
        etInput.setHint("输入消息");
        etInput.setHintTextColor(Theme.muted());
        etInput.setTextColor(Theme.text());
        etInput.setTextSize(15);
        etInput.setSingleLine(true);
        etInput.setImeOptions(EditorInfo.IME_ACTION_SEND);
        etInput.setBackground(round(Theme.input(), 22));
        etInput.setPadding(dp(16), dp(10), dp(16), dp(10));
        etInput.setLayoutParams(withLeftMargin(1f, 8));

        btnSend = chip("发送", Theme.accent(), Theme.onAccent());
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
        // 待发送图片的缩略图条（默认隐藏，横向可滚动，避免多张被截断）
        pendingStrip = new LinearLayout(this);
        pendingStrip.setOrientation(LinearLayout.HORIZONTAL);
        pendingStrip.setGravity(Gravity.CENTER_VERTICAL);
        pendingStrip.setPadding(dp(10), dp(8), dp(10), dp(8));
        pendingStrip.setBackgroundColor(Theme.panel());

        pendingScroll = new android.widget.HorizontalScrollView(this);
        pendingScroll.setHorizontalScrollBarEnabled(false);
        pendingScroll.setBackgroundColor(Theme.panel());
        pendingScroll.setVisibility(View.GONE);
        pendingScroll.addView(pendingStrip, new android.widget.HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(bottomNew);
        root.addView(pendingScroll);
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
                t.setTextColor(Theme.text());
                t.setTextSize(15);
                t.setSingleLine(true);
                t.setEllipsize(android.text.TextUtils.TruncateAt.END);

                TextView ago = new TextView(MainActivity.this);
                ago.setText(relTime(sid));
                ago.setTextColor(Theme.muted());
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
            @Override public void onClick(View v) {
                if (generating) doCancel(); else doSend();
            }
        });
        btnBack.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showSessions(); }
        });
        btnImage.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showAttachSheet(); }
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
        refreshPendingStrip();   // Activity 重建后把待发图片恢复出来
        loadLogin();

        // Android 13+ 通知要运行时授权（后台提醒靠它）
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            try {
                if (checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 2001);
                }
            } catch (Throwable ignored) {
            }
        }
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
        // 间距用 margin，不用 padding：padding 会让图片落在 outline 圆角之内，顶角裁不到
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        iv.setLayoutParams(lp);
        // 圆角：借 background 的 outline 裁剪内容（API 21+）
        iv.setBackground(round(0x00000000, 14));
        iv.setClipToOutline(true);
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
        e.setTextColor(Theme.text());
        e.setTextSize(13);
        e.setSingleLine(true);
        e.setBackground(round(Theme.input(), 12));
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
                pill.setBackground(round(Theme.bubbleAi(), 18));
                pill.setPadding(dp(13), dp(9), dp(16), dp(9));

                pill.addView(new WhaleTail(MainActivity.this));

                TextView label = new TextView(MainActivity.this);
                label.setText("思考中");
                label.setTextColor(Theme.muted());
                label.setTextSize(13);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.leftMargin = dp(9);
                pill.addView(label, lp);

                row.addView(pill);
                return row;
            }

            int maxW = (int) (getResources().getDisplayMetrics().widthPixels * 0.84f);

            LinearLayout col = new LinearLayout(MainActivity.this);
            col.setOrientation(LinearLayout.VERTICAL);
            // 图片和气泡同侧：自己发的贴右，AI 发的贴左
            col.setGravity(m.role == 0 ? Gravity.END : Gravity.START);

            TextView tv = new TextView(MainActivity.this);
            tv.setTextSize(15);
            tv.setTextColor(Theme.textOnBubble(m.role == 0));
            tv.setLineSpacing(dp(3), 1f);
            tv.setText(Md.render(m.text, Theme.codeOnBubble(m.role == 0), Theme.codeBg()));
            tv.setBackground(round(m.role == 0 ? Theme.bubbleMe() : Theme.bubbleAi(), 18));
            tv.setPadding(dp(14), dp(10), dp(14), dp(10));
            tv.setTextIsSelectable(true);
            tv.setMaxWidth(maxW);
            if (m.role == 1) tv.setMinWidth(dp(40));
            col.addView(tv);

            // 图片：本地刚发的 → 优先；其次 content 的 attachment；最后 markdown 远程 URL
            if (m.localBmps != null && !m.localBmps.isEmpty()) {
                for (android.graphics.Bitmap b : m.localBmps) {
                    ImageView iv = imageView(maxW);
                    iv.setImageBitmap(b);
                    col.addView(iv);
                }
            } else if (m.localBmp != null) {
                ImageView iv = imageView(maxW);
                iv.setImageBitmap(m.localBmp);
                col.addView(iv);
            } else if (m.imageIds != null && !m.imageIds.isEmpty()) {
                for (String aid : m.imageIds) {
                    ImageView iv = imageView(maxW);
                    String key = "att:" + aid;
                    iv.setTag(key);
                    android.graphics.Bitmap cached = imgCache.get(key);
                    if (cached != null) {
                        iv.setImageBitmap(cached);
                    } else {
                        iv.setMinimumHeight(dp(48));
                        loadAttachmentAsync(aid, iv);
                    }
                    col.addView(iv);
                }
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

            // col 固定 84% 屏宽：内部子 View 各自按内容宽、靠同一侧对齐，
            // 否则 wrap_content 会让最宽的图片反过来限制文字气泡的宽度
            row.addView(col, new LinearLayout.LayoutParams(
                    maxW, ViewGroup.LayoutParams.WRAP_CONTENT));
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
        generating = false;
        updateSendButton();
        btnSend.setEnabled(false);
        btnSend.setAlpha(0.45f);
        btnImage.setText("＋");
        btnImage.setAlpha(0.45f);
        pendingImages.clear();
        pendingMimes.clear();
        pendingNames.clear();
        refreshPendingStrip();
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
        android.graphics.drawable.Drawable base = new CropDrawable(bmp);
        if (Theme.dark) return base;
        // 浅色主题：背景图上叠一层白纱，深色壁纸上也能看清黑字
        android.graphics.drawable.LayerDrawable ld = new android.graphics.drawable.LayerDrawable(
                new android.graphics.drawable.Drawable[]{
                        base,
                        new android.graphics.drawable.ColorDrawable(0xB3FFFFFF)
                });
        return ld;
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
        else list.setBackgroundColor(Theme.bg());
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
        int rangeDays = getSharedPreferences("dshmini", MODE_PRIVATE).getInt("rangeDays", 0);
        long cutoff = rangeDays > 0
                ? System.currentTimeMillis() - rangeDays * 86400000L : 0L;
        for (int i = 0; i < items.length(); i++) {
            JSONObject it = items.optJSONObject(i);
            if (it == null) continue;
            if (it.optBoolean("blank")) continue;   // 空会话不进列表（桌面端也这样）
            long updatedAt = it.optLong("updatedAt", 0);
            if (cutoff > 0 && updatedAt > 0 && updatedAt < cutoff) continue;   // 超出显示范围
            String sid = it.optString("sessionId");
            String title = "";
            JSONObject proj = it.optJSONObject("projections");
            if (proj != null) {
                JSONObject vals = proj.optJSONObject("values");
                if (vals != null) title = vals.optString("title", "");
            }
            names.add(cleanTitle(title) + "\n" + sid);
            if (times != null) times.put(sid, updatedAt);
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
        generating = false;
        updateSendButton();
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
                final boolean turnOver = "turn/end".equals(evType);
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (turnOver) {
                            generating = false;
                            updateSendButton();
                        }
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
                java.util.List<String> ids = imageIdsOf(data);
                if (TextUtils.isEmpty(t) && (ids == null || ids.isEmpty())) return null;
                Msg m = new Msg(0, t);
                m.imageIds = ids;
                return m;
            }
            if ("assistant/message".equals(type)) {
                JSONObject data = ev.optJSONObject("data");
                String t = extractText(data);
                java.util.List<String> ids = imageIdsOf(data);
                if (TextUtils.isEmpty(t) && (ids == null || ids.isEmpty())) return null;
                Msg m = new Msg(1, t);
                m.imageIds = ids;
                return m;
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 从 content 里收集所有 image 块的 attachmentId（历史里一条消息可能带多张图）。 */
    private java.util.List<String> imageIdsOf(JSONObject data) {
        java.util.List<String> ids = new ArrayList<String>();
        if (data == null) return ids;
        JSONArray content = data.optJSONArray("content");
        if (content == null) {
            JSONObject message = data.optJSONObject("message");
            if (message != null) content = message.optJSONArray("content");
        }
        if (content == null) return ids;
        for (int i = 0; i < content.length(); i++) {
            JSONObject b = content.optJSONObject(i);
            if (b == null || !"image".equals(b.optString("type"))) continue;
            JSONObject att = b.optJSONObject("attachment");
            if (att != null) {
                String id = att.optString("attachmentId", "");
                if (id.length() > 0) ids.add(id);
            }
        }
        return ids;
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
        final List<byte[]> imgs = new ArrayList<byte[]>(pendingImages);
        final List<String> mimes = new ArrayList<String>(pendingMimes);
        final List<String> names = new ArrayList<String>(pendingNames);
        if (TextUtils.isEmpty(text) && imgs.isEmpty()) return;
        if (api == null || currentSession == null) return;
        etInput.setText("");
        pendingImages.clear();
        pendingMimes.clear();
        pendingNames.clear();
        refreshPendingStrip();

        // 本地先回显，服务端稍后会推同一条 user/message，在 handleWsFrame 里去重
        pendingUserText = text;
        generating = true;
        updateSendButton();
        String mark = imgs.isEmpty() ? "" : ("【图片" + (imgs.size() > 1 ? " ×" + imgs.size() : "") + "】");
        String echo = imgs.isEmpty() ? text
                : (mark + (TextUtils.isEmpty(text) ? "" : " " + text));
        Msg echoMsg = new Msg(0, echo);
        if (!imgs.isEmpty()) {
            echoMsg.localBmps = new ArrayList<android.graphics.Bitmap>();
            for (byte[] b : imgs) {
                android.graphics.Bitmap bmp = decodeScaled(b, 1400);
                if (bmp != null) echoMsg.localBmps.add(bmp);
            }
        }
        msgs.add(echoMsg);
        msgAdapter.notifyDataSetChanged();
        scrollEnd();

        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    api.sendPrompt(currentSession, text, imgs, mimes, names);
                    ui.post(new Runnable() { @Override public void run() { status("已发送"); } });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        @Override public void run() {
                            status("发送失败: " + e.getMessage());
                            generating = false;
                            updateSendButton();
                        }
                    });
                }
            }
        }).start();
    }

    // ---- 选图 ----

    /** 发送键在「发送 / 停止」之间切换。 */
    private void updateSendButton() {
        if (btnSend == null) return;
        btnSend.setText(generating ? "停止" : "发送");
        btnSend.setBackground(round(generating ? 0xFF7A3B3B : Theme.accent(), 18));
        btnSend.setAlpha(1f);
    }

    /** 打断正在进行的生成（服务端随后会推 turn/end）。 */
    private void doCancel() {
        if (api == null || currentSession == null) return;
        status("正在停止…");
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    api.cancelSession(currentSession);
                    ui.post(new Runnable() {
                        @Override public void run() { status("已请求停止"); }
                    });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        @Override public void run() {
                            status("停止失败: " + e.getMessage());
                            generating = false;
                            updateSendButton();
                        }
                    });
                }
            }
        }).start();
    }

    /** 重建待发送缩略图条：图 + 右上角 ×。 */
    private void refreshPendingStrip() {
        if (pendingStrip == null) return;
        pendingStrip.removeAllViews();
        if (pendingImages.isEmpty()) {
            if (pendingScroll != null) pendingScroll.setVisibility(View.GONE);
            return;
        }
        if (pendingScroll != null) pendingScroll.setVisibility(View.VISIBLE);

        TextView hint = new TextView(this);
        hint.setText("待发 " + pendingImages.size());
        hint.setTextColor(Theme.muted());
        hint.setTextSize(11);
        hint.setPadding(0, 0, dp(8), 0);
        pendingStrip.addView(hint);

        for (int i = 0; i < pendingImages.size(); i++) {
            final int idx = i;
            FrameLayout box = new FrameLayout(this);
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setImageBitmap(decodeScaled(pendingImages.get(i), 160));
            iv.setBackground(round(Theme.bubbleAi(), 10));
            iv.setClipToOutline(true);
            box.addView(iv, new FrameLayout.LayoutParams(dp(56), dp(56)));

            TextView x = new TextView(this);
            x.setText("×");
            x.setTextColor(0xFFFFFFFF);
            x.setTextSize(12);
            x.setGravity(Gravity.CENTER);
            x.setBackground(round(0xCC222222, 9));
            x.setClickable(true);
            x.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (idx < pendingImages.size()) {
                        pendingImages.remove(idx);
                        pendingMimes.remove(idx);
                        pendingNames.remove(idx);
                        refreshPendingStrip();
                    }
                }
            });
            FrameLayout.LayoutParams xlp = new FrameLayout.LayoutParams(dp(18), dp(18));
            xlp.gravity = Gravity.TOP | Gravity.END;
            box.addView(x, xlp);

            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(56), dp(56));
            blp.rightMargin = dp(8);
            pendingStrip.addView(box, blp);
        }
    }

    /** 底部上拉菜单：相册 / 拍摄 / 文件（照着微信那个风格）。 */
    private void showAttachSheet() {
        if (currentSession == null) {
            toast("先进一个会话");
            return;
        }
        final android.app.Dialog dlg = new android.app.Dialog(this);
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);

        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setBackground(round(Theme.panel(), 22));
        sheet.setPadding(dp(16), dp(22), dp(16), dp(30));

        LinearLayout opts = new LinearLayout(this);
        opts.setOrientation(LinearLayout.HORIZONTAL);
        opts.setGravity(Gravity.CENTER_HORIZONTAL);

        int[] icons = {R.drawable.ic_album, R.drawable.ic_camera, R.drawable.ic_folder};
        String[] labels = {"相册", "拍摄", "文件"};

        for (int i = 0; i < labels.length; i++) {
            final int idx = i;

            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            cell.setClickable(true);

            // 白色圆角方块 + 居中图标（微信那套观感）
            LinearLayout box = new LinearLayout(this);
            box.setGravity(Gravity.CENTER);
            box.setBackground(round(0xFFEDEDF2, 20));
            ImageView ic = new ImageView(this);
            ic.setImageResource(icons[i]);
            ic.setColorFilter(0xFF1A1A20);
            box.addView(ic, new LinearLayout.LayoutParams(dp(30), dp(30)));
            cell.addView(box, new LinearLayout.LayoutParams(dp(72), dp(72)));

            TextView label = new TextView(this);
            label.setText(labels[i]);
            label.setTextColor(Theme.muted());
            label.setTextSize(13);
            label.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            llp.topMargin = dp(8);
            cell.addView(label, llp);

            cell.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    dlg.dismiss();
                    if (idx == 0) pickImages();
                    else if (idx == 1) doCamera();
                    else doPickFile();
                }
            });

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            opts.addView(cell, lp);
        }
        sheet.addView(opts);
        dlg.setContentView(sheet);
        dlg.show();

        android.view.Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.BOTTOM);
        }
    }

    private void doCamera() {
        if (currentSession == null) {
            toast("先进一个会话再拍照");
            return;
        }
        try {
            String name = "cam_" + System.currentTimeMillis() + ".jpg";
            android.content.ContentValues values = new android.content.ContentValues();
            values.put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, name);
            values.put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            Uri out = getContentResolver().insert(
                    android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (out == null) throw new java.io.IOException("无法在相册里创建条目");
            cameraUri = out;
            cameraName = name;
            // 存起来：相机期间 Activity 可能被系统回收，字段会丢
            getSharedPreferences("dshmini", MODE_PRIVATE).edit()
                    .putString("cameraUri", out.toString())
                    .putString("cameraName", name)
                    .apply();
            Intent it = new Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE);
            it.putExtra(android.provider.MediaStore.EXTRA_OUTPUT, out);
            it.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            selectingImage = true;
            startActivityForResult(it, REQ_CAMERA);
        } catch (Exception e) {
            selectingImage = false;
            cameraUri = null;
            toast("打不开相机: " + e.getMessage());
        }
    }

    /** 选图片：优先系统照片选择器（真·相册 + 多选），退到图库，再退到文件选择器。 */
    private void pickImages() {
        if (currentSession == null) {
            toast("先进一个会话再选图");
            return;
        }
        // 1) Android 13+ 系统照片选择器
        try {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                Intent it = new Intent(android.provider.MediaStore.ACTION_PICK_IMAGES);
                it.putExtra(android.provider.MediaStore.EXTRA_PICK_IMAGES_MAX, 9);
                selectingImage = true;
                startActivityForResult(it, REQ_IMAGE);
                return;
            }
        } catch (Exception ignored) {
        }
        // 2) 图库
        try {
            Intent it = new Intent(Intent.ACTION_PICK,
                    android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
            it.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            selectingImage = true;
            startActivityForResult(it, REQ_IMAGE);
            return;
        } catch (Exception ignored) {
        }
        // 3) 兜底：文件选择器
        try {
            Intent it = new Intent(Intent.ACTION_GET_CONTENT);
            it.setType("image/*");
            it.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            selectingImage = true;
            startActivityForResult(it, REQ_IMAGE);
        } catch (Exception e) {
            selectingImage = false;
            toast("打不开相册: " + e.getMessage());
        }
    }

    /** 选任意文件：图片当图片发，其它类型暂不支持。 */
    private void doPickFile() {
        if (currentSession == null) {
            toast("先进一个会话");
            return;
        }
        Intent it = new Intent(Intent.ACTION_GET_CONTENT);
        it.setType("*/*");
        it.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            selectingImage = true;
            startActivityForResult(it, REQ_FILE);
        } catch (Exception e) {
            selectingImage = false;
            toast("打不开文件选择器: " + e.getMessage());
        }
    }

    /** 相机把照片写进 MediaStore 的条目里，这里读回内存。 */
    private void loadCameraPhoto() {
        // 优先用持久化的 URI（Activity 被回收重建后字段是空的）
        android.content.SharedPreferences sp = getSharedPreferences("dshmini", MODE_PRIVATE);
        String savedUri = sp.getString("cameraUri", "");
        String savedName = sp.getString("cameraName", "");
        sp.edit().remove("cameraUri").remove("cameraName").apply();

        Uri use = cameraUri;
        String nm = cameraName;
        cameraUri = null;
        if (savedUri.length() > 0) {
            try { use = Uri.parse(savedUri); } catch (Exception ignored) {}
        }
        if (savedName.length() > 0) nm = savedName;
        if (use == null) {
            status("没有拍照结果（URI 丢失）");
            return;
        }
        final Uri uri = use;
        final String name = nm;
        status("读取照片…");
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    java.io.InputStream in = getContentResolver().openInputStream(uri);
                    if (in == null) throw new java.io.IOException("打不开照片");
                    java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                    in.close();
                    final byte[] bytes = bos.toByteArray();
                    if (bytes.length == 0) throw new java.io.IOException("照片是空的（相机没写入）");
                    ui.post(new Runnable() {
                        @Override public void run() {
                            pendingImages.add(bytes);
                            pendingMimes.add("image/jpeg");
                            pendingNames.add(name);
                            MainActivity top = current;
                            if (top != null) {
                                top.btnImage.setAlpha(1f);
                                top.refreshPendingStrip();
                                top.status("已拍照 · " + (bytes.length / 1024) + " KB");
                            }
                        }
                    });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        @Override public void run() { status("读照片失败: " + e.getMessage()); }
                    });
                }
            }
        }).start();
    }

    private static byte[] readAllBytes(File f) throws java.io.IOException {
        java.io.FileInputStream in = new java.io.FileInputStream(f);
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        return bos.toByteArray();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        selectingImage = false;

        if (req == REQ_SPEECH) {
            if (res == RESULT_OK && data != null) {
                java.util.ArrayList<String> r =
                        data.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS);
                if (r != null && !r.isEmpty()) {
                    etInput.setText(r.get(0));
                    etInput.setSelection(etInput.getText().length());
                }
            }
            return;
        }

        if (req == REQ_CAMERA) {
            // 有些相机拍完返回 RESULT_CANCELED，只要 MediaStore 里有内容就算成功
            loadCameraPhoto();
            return;
        }

        if ((req != REQ_IMAGE && req != REQ_FILE) || res != RESULT_OK || data == null) return;

        // 多选：ClipData 里可能有多个
        final List<Uri> uris = new ArrayList<Uri>();
        if (data.getClipData() != null && data.getClipData().getItemCount() > 0) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                Uri u = data.getClipData().getItemAt(i).getUri();
                if (u != null) uris.add(u);
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        if (uris.isEmpty()) return;

        status("读取图片…");
        new Thread(new Runnable() {
            @Override public void run() {
                int ok = 0;
                String err = null;
                for (Uri uri : uris) {
                    try {
                        android.content.ContentResolver cr = getContentResolver();
                        String mime = cr.getType(uri);
                        if (TextUtils.isEmpty(mime) || !mime.startsWith("image/")) {
                            throw new java.io.IOException("暂不支持非图片文件");
                        }
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
                                pendingImages.add(bytes);
                                pendingMimes.add(m);
                                pendingNames.add(nm);
                                MainActivity top = current;
                                if (top != null) {
                                    top.btnImage.setAlpha(1f);
                                    top.refreshPendingStrip();
                                }
                            }
                        });
                        ok++;
                    } catch (Exception e) {
                        err = e.getMessage();
                    }
                }
                final int okCount = ok;
                final String lastErr = err;
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (okCount > 0) status("已选 " + pendingImages.size() + " 张图，发送时带上");
                        else status("读图失败: " + lastErr);
                    }
                });
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
        if (current == this) current = null;
        if (selectingImage) return;   // 去相册/相机，连接要留着
        closeWs();
        // 退到后台：把连接交给前台服务，回复到了会发通知
        if (inChat && currentSession != null && api != null) {
            try {
                Intent svc = new Intent(this, DshService.class);
                svc.putExtra("sessionId", currentSession);
                if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(svc);
                else startService(svc);
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        current = this;
        refreshPendingStrip();   // 重建后从静态列表恢复待发图片
        // 设置里换了主题 → 自己重建一次（和本实例实际应用过的值比）
        boolean darkNow = getSharedPreferences("dshmini", MODE_PRIVATE).getBoolean("dark", true);
        if (darkNow != appliedDark) {
            Theme.dark = darkNow;
            appliedDark = darkNow;
            recreate();
            return;
        }
        applySettings();
        stopService(new Intent(this, DshService.class));   // 回前台，服务退场
        if (selectingImage) return;   // 从相册回来，别把刚选的图冲掉
        // 回到列表页时自动刷新：设置里改了显示范围，回来立刻生效（齿轮替换掉刷新键后的补偿）
        if (!inChat && api != null) {
            refreshSessions();
            return;
        }
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
