package com.whale.mini;

/**
 * 两套配色。所有界面颜色都从这里取，切换主题后 recreate() 重绘即可。
 *
 * 用静态开关而不是 Resources 主题，是因为布局全是代码写的，
 * 走 Theme 资源反而要到处传 Context。
 */
public class Theme {

    /** true = 深色（默认），false = 浅色。 */
    public static boolean dark = true;

    // ---- 深色 ----
    private static final int D_BG     = 0xFF0F0F12;
    private static final int D_PANEL  = 0xFF141419;
    private static final int D_AI     = 0xFF1C1C23;
    private static final int D_ME     = 0xFF2A4E8F;
    private static final int D_TEXT   = 0xFFE9E9EE;
    private static final int D_MUTED  = 0xFF8B8B97;
    private static final int D_ACCENT = 0xFF4C8DFF;
    private static final int D_INPUT  = 0xFF1E1E26;
    private static final int D_CHIP   = 0xFF24242D;
    private static final int D_CODE_BG = 0xFF101015;
    private static final int D_CODE_FG = 0xFF9BD1FF;
    private static final int D_AVATAR_BG = 0xFF1C2E5A;

    // ---- 浅色 ----
    private static final int L_BG     = 0xFFF2F2F5;
    private static final int L_PANEL  = 0xFFFFFFFF;
    private static final int L_AI     = 0xFFFFFFFF;
    private static final int L_ME     = 0xFF4C8DFF;
    private static final int L_TEXT   = 0xFF1A1A1E;
    private static final int L_MUTED  = 0xFF7A7A85;
    private static final int L_ACCENT = 0xFF2F6FE0;
    private static final int L_INPUT  = 0xFFE9E9EE;
    private static final int L_CHIP   = 0xFFE4E4EA;
    private static final int L_CODE_BG = 0xFFF0F0F4;
    private static final int L_CODE_FG = 0xFF1A5FB4;
    private static final int L_AVATAR_BG = 0xFFDCE6F7;

    public static int bg()       { return dark ? D_BG : L_BG; }
    public static int panel()    { return dark ? D_PANEL : L_PANEL; }
    public static int bubbleAi() { return dark ? D_AI : L_AI; }
    public static int bubbleMe() { return dark ? D_ME : L_ME; }
    public static int text()     { return dark ? D_TEXT : L_TEXT; }
    public static int muted()    { return dark ? D_MUTED : L_MUTED; }
    public static int accent()   { return dark ? D_ACCENT : L_ACCENT; }
    public static int input()    { return dark ? D_INPUT : L_INPUT; }
    public static int chip()     { return dark ? D_CHIP : L_CHIP; }
    public static int codeBg()   { return dark ? D_CODE_BG : L_CODE_BG; }
    public static int codeFg()   { return dark ? D_CODE_FG : L_CODE_FG; }
    public static int avatarBg() { return dark ? D_AVATAR_BG : L_AVATAR_BG; }

    /** 强调色按钮上的文字：深色底用深蓝字，浅色底用白字。 */
    public static int onAccent() { return dark ? 0xFF0B1B33 : 0xFFFFFFFF; }

    /** 气泡里的文字：自己发的蓝气泡在浅色下要白字。 */
    public static int textOnBubble(boolean mine) {
        if (dark) return D_TEXT;
        return mine ? 0xFFFFFFFF : L_TEXT;
    }

    /** 代码块里的文字色（跟随气泡文字）。 */
    public static int codeOnBubble(boolean mine) {
        if (dark) return D_CODE_FG;
        return mine ? 0xFFDCEBFF : L_CODE_FG;
    }
}
