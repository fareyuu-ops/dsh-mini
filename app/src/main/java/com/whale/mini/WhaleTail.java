package com.whale.mini;

import android.content.Context;
import android.graphics.drawable.AnimationDrawable;
import android.widget.ImageView;

/**
 * 思考中的小鲸鱼尾巴。
 *
 * 素材来自用户录的 2 秒屏（106×106，纯尾巴那版），按 24fps 抽帧、去掉白底、
 * 缩到 64px，取前 24 帧（1 秒）循环。形状和节奏都是原作者的手笔。
 */
public class WhaleTail extends ImageView {

    private static final int FRAMES = 24;
    private static final int FRAME_MS = 41;

    private AnimationDrawable anim;

    public WhaleTail(Context c) {
        super(c);
        setScaleType(ScaleType.FIT_CENTER);

        anim = new AnimationDrawable();
        for (int i = 1; i <= FRAMES; i++) {
            int id = c.getResources().getIdentifier(
                    String.format("tail_%02d", i), "drawable", c.getPackageName());
            if (id == 0) break;
            try {
                anim.addFrame(c.getDrawable(id), FRAME_MS);
            } catch (Throwable ignored) {
            }
        }
        anim.setOneShot(false);
        setImageDrawable(anim);
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        float d = getResources().getDisplayMetrics().density;
        setMeasuredDimension((int) (24 * d + 0.5f), (int) (24 * d + 0.5f));
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (anim != null) anim.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (anim != null) anim.stop();
        super.onDetachedFromWindow();
    }
}
