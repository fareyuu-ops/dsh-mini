package com.whale.mini;

import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;

/**
 * 极简 markdown → Spannable。
 *
 * 只做聊天里真会出现的几样：**粗体**、`行内代码`、```代码块```、# 标题、- 列表。
 * 流式回放时文本可能是半截的（未闭合的 ** 或 ```），所以每步都要能容错。
 */
public class Md {

    public static CharSequence render(String src, int codeFg, int codeBg) {
        if (src == null || src.length() == 0) return "";
        SpannableStringBuilder sb = new SpannableStringBuilder();
        String[] lines = src.split("\n", -1);

        boolean inCode = false;
        int codeStart = -1;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String t = line.trim();

            if (t.startsWith("```")) {
                if (!inCode) {
                    inCode = true;
                    codeStart = sb.length();
                } else {
                    inCode = false;
                    if (sb.length() > codeStart) {
                        sb.setSpan(new BackgroundColorSpan(codeBg), codeStart, sb.length(),
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                    codeStart = -1;
                    // 代码块后空一行再继续
                    if (i < lines.length - 1) sb.append('\n');
                }
                continue;
            }

            if (inCode) {
                int s = sb.length();
                sb.append(line).append('\n');
                sb.setSpan(new TypefaceSpan("monospace"), s, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new ForegroundColorSpan(codeFg), s, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                continue;
            }

            if (t.startsWith("#")) {
                int lvl = 0;
                while (lvl < t.length() && t.charAt(lvl) == '#') lvl++;
                String body = t.substring(lvl).trim();
                int s = sb.length();
                sb.append(body).append('\n');
                sb.setSpan(new StyleSpan(Typeface.BOLD), s, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new RelativeSizeSpan(lvl <= 1 ? 1.22f : 1.1f), s, sb.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                continue;
            }

            if (t.matches("^[-*+]\\s+.*")) {
                String body = t.substring(t.indexOf(' ') + 1);
                sb.append("• ");
                inline(sb, body, codeFg, codeBg);
                sb.append('\n');
                continue;
            }

            inline(sb, line, codeFg, codeBg);
            sb.append('\n');
        }

        // 未闭合的代码块也要有底色，否则流式中途看着断
        if (inCode && sb.length() > codeStart) {
            sb.setSpan(new BackgroundColorSpan(codeBg), codeStart, sb.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        if (sb.length() > 0 && sb.charAt(sb.length() - 1) == '\n') {
            sb.delete(sb.length() - 1, sb.length());
        }
        return sb;
    }

    private static void inline(SpannableStringBuilder sb, String s, int codeFg, int codeBg) {
        int i = 0;
        int n = s.length();
        while (i < n) {
            int b = s.indexOf("**", i);
            int c = s.indexOf('`', i);
            int next;
            if (b < 0) next = c;
            else if (c < 0) next = b;
            else next = Math.min(b, c);

            if (next < 0) {
                sb.append(s.substring(i));
                return;
            }
            if (next > i) sb.append(s.substring(i, next));

            if (next == b) {
                int end = s.indexOf("**", b + 2);
                if (end < 0) {
                    sb.append(s.substring(b));
                    return;
                }
                int st = sb.length();
                sb.append(s.substring(b + 2, end));
                sb.setSpan(new StyleSpan(Typeface.BOLD), st, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                i = end + 2;
            } else {
                int end = s.indexOf('`', c + 1);
                if (end < 0) {
                    sb.append(s.substring(c));
                    return;
                }
                int st = sb.length();
                sb.append(s.substring(c + 1, end));
                sb.setSpan(new TypefaceSpan("monospace"), st, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new ForegroundColorSpan(codeFg), st, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new BackgroundColorSpan(codeBg), st, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                i = end + 1;
            }
        }
    }
}
