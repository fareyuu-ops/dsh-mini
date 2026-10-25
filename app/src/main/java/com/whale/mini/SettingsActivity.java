package com.whale.mini;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * 设置页：服务器、头像、两套背景、关于。
 *
 * 图片一律复制进应用私有目录（getFilesDir），只存路径，Web 端不用管。
 */
public class SettingsActivity extends Activity {

    private static final int C_BG      = 0xFF0F0F12;
    private static final int C_PANEL   = 0xFF141419;
    private static final int C_TEXT    = 0xFFE9E9EE;
    private static final int C_MUTED   = 0xFF8B8B97;
    private static final int C_ACCENT  = 0xFF4C8DFF;
    private static final int C_INPUT   = 0xFF1E1E26;

    private static final int REQ_AVATAR = 2001;
    private static final int REQ_LIST_BG = 2002;
    private static final int REQ_CHAT_BG = 2003;

    private EditText etUrl, etToken;
    private ImageView ivAvatar;
    private TextView tvListBg, tvChatBg;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(C_BG);
        getWindow().setNavigationBarColor(C_BG);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(C_BG);
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(8), dp(16), dp(24));

        // 顶部
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, dp(8), 0, dp(14));

        TextView back = chip("‹", 0xFF24242D, C_TEXT);
        back.setTextSize(20);
        back.setPadding(dp(14), dp(2), dp(16), dp(4));
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        header.addView(back);

        TextView title = new TextView(this);
        title.setText("设置");
        title.setTextColor(C_TEXT);
        title.setTextSize(19);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(title, margin(1f, 12));
        root.addView(header);

        // ---- 服务器 ----
        root.addView(label("服务器"));
        etUrl = input("http://192.168.1.2:19387");
        etToken = input("token");
        root.addView(etUrl);
        root.addView(etToken, topMargin(8));

        SharedPreferences sp = prefs();
        etUrl.setText(sp.getString("url", ""));
        etToken.setText(sp.getString("token", ""));

        TextView save = chip("保存连接信息", C_ACCENT, 0xFF0B1B33);
        save.setPadding(dp(18), dp(11), dp(18), dp(11));
        save.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                prefs().edit()
                        .putString("url", etUrl.getText().toString().trim())
                        .putString("token", etToken.getText().toString().trim())
                        .apply();
                toast("已保存，回主界面会自动重连");
            }
        });
        root.addView(save, topMargin(10));

        // ---- 头像 ----
        root.addView(label("头像"));
        ivAvatar = new ImageView(this);
        ivAvatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        ivAvatar.setBackground(round(0xFF1C2E5A, 28));
        root.addView(ivAvatar, new LinearLayout.LayoutParams(dp(56), dp(56)));
        refreshAvatarPreview();

        LinearLayout avatarRow = new LinearLayout(this);
        avatarRow.setOrientation(LinearLayout.HORIZONTAL);
        avatarRow.setPadding(0, dp(10), 0, 0);
        TextView pickAvatar = chip("选择图片", 0xFF24242D, C_TEXT);
        pickAvatar.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pick(REQ_AVATAR); }
        });
        TextView resetAvatar = chip("恢复默认", 0xFF24242D, C_MUTED);
        resetAvatar.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                prefs().edit().remove("avatar").apply();
                refreshAvatarPreview();
            }
        });
        avatarRow.addView(pickAvatar);
        avatarRow.addView(resetAvatar, topMargin(0));
        root.addView(avatarRow);

        // ---- 背景 ----
        root.addView(label("对话列表背景"));
        tvListBg = note();
        root.addView(tvListBg);
        TextView pickListBg = chip("选择图片", 0xFF24242D, C_TEXT);
        pickListBg.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pick(REQ_LIST_BG); }
        });
        root.addView(pickListBg, topMargin(8));
        TextView resetListBg = chip("恢复默认", 0xFF24242D, C_MUTED);
        resetListBg.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                prefs().edit().remove("listBg").apply();
                refreshBgLabels();
            }
        });
        root.addView(resetListBg, topMargin(8));

        root.addView(label("聊天背景"));
        tvChatBg = note();
        root.addView(tvChatBg);
        TextView pickChatBg = chip("选择图片", 0xFF24242D, C_TEXT);
        pickChatBg.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pick(REQ_CHAT_BG); }
        });
        root.addView(pickChatBg, topMargin(8));
        TextView resetChatBg = chip("恢复默认", 0xFF24242D, C_MUTED);
        resetChatBg.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                prefs().edit().remove("chatBg").apply();
                refreshBgLabels();
            }
        });
        root.addView(resetChatBg, topMargin(8));

        // ---- 关于 ----
        root.addView(label("关于"));
        TextView about = note();
        about.setText("DSH Mini 0.3\n"
                + "DeepSeek Harness 的安卓原生极简客户端。\n"
                + "纯手写 WebSocket / HTTP，无第三方依赖，手工构建（aapt2 + javac + d8）。\n\n"
                + "协议细节见仓库 docs/DSH-协议逆向.md。\n"
                + "GitHub: 待填（推送后更新）");
        about.setLineSpacing(dp(3), 1f);
        root.addView(about);

        scroll.addView(root);
        setContentView(scroll);
    }

    // ---- 选图 ----

    private void pick(int req) {
        Intent it = new Intent(Intent.ACTION_GET_CONTENT);
        it.setType("image/*");
        it.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(it, req);
        } catch (Exception e) {
            toast("打不开相册: " + e.getMessage());
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        String name = req == REQ_AVATAR ? "avatar.img"
                : req == REQ_LIST_BG ? "listbg.img" : "chatbg.img";
        try {
            String path = copyToFiles(uri, name);
            SharedPreferences.Editor e = prefs().edit();
            if (req == REQ_AVATAR) e.putString("avatar", path);
            else if (req == REQ_LIST_BG) e.putString("listBg", path);
            else e.putString("chatBg", path);
            e.apply();
            if (req == REQ_AVATAR) refreshAvatarPreview();
            else refreshBgLabels();
            toast("已保存");
        } catch (Exception ex) {
            toast("保存失败: " + ex.getMessage());
        }
    }

    private String copyToFiles(Uri uri, String name) throws IOException {
        File dst = new File(getFilesDir(), name);
        InputStream in = getContentResolver().openInputStream(uri);
        if (in == null) throw new IOException("打开图片失败");
        FileOutputStream out = new FileOutputStream(dst);
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        out.close();
        return dst.getAbsolutePath();
    }

    // ---- 预览 ----

    private void refreshAvatarPreview() {
        String p = prefs().getString("avatar", "");
        if (!TextUtils.isEmpty(p) && new File(p).exists()) {
            Bitmap bmp = decodeFile(p, 200);
            if (bmp != null) {
                ivAvatar.setImageBitmap(bmp);
                return;
            }
        }
        ivAvatar.setImageResource(R.drawable.tail_01);
    }

    private void refreshBgLabels() {
        String l = prefs().getString("listBg", "");
        String c = prefs().getString("chatBg", "");
        tvListBg.setText(TextUtils.isEmpty(l) ? "默认深色" : "已设置");
        tvChatBg.setText(TextUtils.isEmpty(c) ? "默认深色" : "已设置");
    }

    private static Bitmap decodeFile(String path, int maxDim) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, o);
            if (o.outWidth <= 0) return null;
            int scale = 1;
            while (o.outWidth / scale > maxDim || o.outHeight / scale > maxDim) scale *= 2;
            o.inJustDecodeBounds = false;
            o.inSampleSize = scale;
            return BitmapFactory.decodeFile(path, o);
        } catch (Throwable t) {
            return null;
        }
    }

    // ---- 小工具 ----

    private SharedPreferences prefs() {
        return getSharedPreferences("dshmini", MODE_PRIVATE);
    }

    private int dp(int v) {
        return (int) (getResources().getDisplayMetrics().density * v + 0.5f);
    }

    private GradientDrawable round(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
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

    private EditText input(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setHintTextColor(0xFF5A5A66);
        e.setTextColor(C_TEXT);
        e.setTextSize(13);
        e.setSingleLine(true);
        e.setBackground(round(C_INPUT, 12));
        e.setPadding(dp(12), dp(9), dp(12), dp(9));
        return e;
    }

    private TextView label(String s) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextColor(C_TEXT);
        tv.setTextSize(15);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setPadding(0, dp(22), 0, dp(8));
        return tv;
    }

    private TextView note() {
        TextView tv = new TextView(this);
        tv.setTextColor(C_MUTED);
        tv.setTextSize(12);
        return tv;
    }

    private LinearLayout.LayoutParams topMargin(int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(top);
        return lp;
    }

    private LinearLayout.LayoutParams margin(float weight, int left) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, weight);
        lp.leftMargin = dp(left);
        return lp;
    }

    private void toast(String s) {
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show();
    }
}
