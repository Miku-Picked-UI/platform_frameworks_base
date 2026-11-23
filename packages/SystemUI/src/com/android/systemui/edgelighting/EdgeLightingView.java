/*
 * Copyright (C) 2026 The PenguinOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.edgelighting;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

class EdgeLightingView extends View {

    static final class Style {
        static final String RAIN = "rain";
        static final String SNOW = "snow";
        static final String BUBBLES = "bubbles";
        static final String BURST = "burst";

        private static final float[][] SIZES = {
                {0.05f, 0.08f}, {0.09f, 0.13f}, {0.14f, 0.22f}, {0.26f, 0.40f}};
        private static final int[] COUNTS = {8, 14, 24};
        private static final long[] DURATIONS_MS = {3000, 5000, 8000};
        private static final float[] SPINS = {0f, 1f, 2.5f};

        final String effect;
        final float minSize, maxSize;
        final int count;
        final long durationMs;
        final float spin;

        Style(String effect, int size, int amount, int duration, int rotation) {
            this.effect = effect == null ? RAIN : effect;
            float[] range = SIZES[clamp(size, SIZES.length)];
            minSize = range[0];
            maxSize = range[1];
            count = COUNTS[clamp(amount, COUNTS.length)];
            durationMs = DURATIONS_MS[clamp(duration, DURATIONS_MS.length)];
            spin = SPINS[clamp(rotation, SPINS.length)];
        }

        static Style defaults() {
            return new Style(RAIN, 2, 1, 1, 1);
        }

        private static int clamp(int v, int n) {
            return Math.max(0, Math.min(n - 1, v));
        }
    }

    private static final float GRAVITY_DP = 2600f;
    private static final float BOUNCE = 0.55f;
    private static final float FRICTION = 0.985f;
    private static final long SPAWN_SPREAD_MS = 1200;
    private static final long FADE_MS = 450;

    private static final class Sprite {
        float x, y, vx, vy, radius, angle, spin, phase;
        long spawnAt;
        boolean active;
    }

    private final Bitmap mBitmap;
    private final Style mStyle;
    private final Runnable mOnFinished;
    private final List<Sprite> mSprites = new ArrayList<>();
    private final Paint mPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final RectF mDst = new RectF();
    private final Random mRandom = new Random();
    private final float mGravity;
    private final float mAspect;

    private long mStart;
    private long mLastFrame;
    private long mFinishAt = -1;

    EdgeLightingView(Context context, Bitmap bitmap, Style style, Runnable onFinished) {
        super(context);
        mBitmap = bitmap;
        mStyle = style;
        mOnFinished = onFinished;
        mGravity = GRAVITY_DP * context.getResources().getDisplayMetrics().density;
        mAspect = bitmap.getHeight() / (float) bitmap.getWidth();
    }

    void finish() {
        if (mFinishAt < 0) mFinishAt = now();
    }

    private static long now() {
        return System.nanoTime() / 1_000_000;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (!mSprites.isEmpty() || w == 0) return;
        mStart = mLastFrame = now();
        for (int i = 0; i < mStyle.count; i++) {
            Sprite s = new Sprite();
            float size = w * (mStyle.minSize
                    + mRandom.nextFloat() * (mStyle.maxSize - mStyle.minSize));
            s.radius = size * Math.max(1f, mAspect) / 2f;
            s.angle = mRandom.nextFloat() * 360f;
            s.spin = (mRandom.nextFloat() - 0.5f) * 240f * mStyle.spin;
            s.phase = mRandom.nextFloat() * 6.283f;
            s.spawnAt = mStart + (long) (SPAWN_SPREAD_MS * i / (float) mStyle.count);
            spawn(s, w, h, true);
            mSprites.add(s);
        }
        if (Style.BURST.equals(mStyle.effect)) {
            for (Sprite s : mSprites) s.spawnAt = mStart;
        }
    }

    private void spawn(Sprite s, int w, int h, boolean first) {
        switch (mStyle.effect) {
            case Style.SNOW -> {
                s.x = s.radius + mRandom.nextFloat() * (w - 2 * s.radius);
                s.y = -s.radius - (first ? 0 : mRandom.nextFloat() * h * 0.2f);
                s.vx = 0;
                s.vy = h * (0.08f + mRandom.nextFloat() * 0.07f);
            }
            case Style.BUBBLES -> {
                s.x = s.radius + mRandom.nextFloat() * (w - 2 * s.radius);
                s.y = h + s.radius + (first ? 0 : mRandom.nextFloat() * h * 0.2f);
                s.vx = 0;
                s.vy = -h * (0.12f + mRandom.nextFloat() * 0.1f);
            }
            case Style.BURST -> {
                s.x = w / 2f;
                s.y = h / 2f;
                double angle = mRandom.nextDouble() * Math.PI * 2;
                float speed = h * (0.6f + mRandom.nextFloat() * 0.7f);
                s.vx = (float) Math.cos(angle) * speed;
                s.vy = (float) Math.sin(angle) * speed;
            }
            default -> {
                s.x = s.radius + mRandom.nextFloat() * (w - 2 * s.radius);
                s.y = -s.radius;
                s.vx = (mRandom.nextFloat() - 0.5f) * w * 0.6f;
                s.vy = mRandom.nextFloat() * h * 0.3f;
            }
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        long t = now();
        if (mFinishAt < 0 && t - mStart > mStyle.durationMs) mFinishAt = t;
        float alpha = 1f;
        if (mFinishAt >= 0) {
            alpha = 1f - Math.min(1f, (t - mFinishAt) / (float) FADE_MS);
            if (alpha <= 0f) {
                post(mOnFinished);
                return;
            }
        }
        float dt = Math.min(0.033f, (t - mLastFrame) / 1000f);
        mLastFrame = t;
        step(t, dt);

        for (Sprite s : mSprites) {
            if (!s.active) continue;
            float halfW = s.radius / Math.max(1f, mAspect);
            float halfH = halfW * mAspect;
            float spriteAlpha = alpha;
            if (Style.SNOW.equals(mStyle.effect)) {
                float fadeStart = getHeight() * 0.8f;
                if (s.y > fadeStart) {
                    spriteAlpha *= Math.max(0f, 1f - (s.y - fadeStart) / (getHeight() * 0.2f));
                }
            }
            mPaint.setAlpha(Math.round(spriteAlpha * 255));
            canvas.save();
            canvas.rotate(s.angle, s.x, s.y);
            mDst.set(s.x - halfW, s.y - halfH, s.x + halfW, s.y + halfH);
            canvas.drawBitmap(mBitmap, null, mDst, mPaint);
            canvas.restore();
        }
        postInvalidateOnAnimation();
    }

    private void step(long t, float dt) {
        int w = getWidth(), h = getHeight();
        float seconds = (t - mStart) / 1000f;
        boolean collide = false;
        for (Sprite s : mSprites) {
            if (!s.active) {
                if (t < s.spawnAt) continue;
                s.active = true;
            }
            s.angle += s.spin * dt;
            switch (mStyle.effect) {
                case Style.SNOW -> {
                    s.x += (float) Math.sin(seconds * 1.6f + s.phase) * w * 0.06f * dt;
                    s.y += s.vy * dt;
                    if (s.y - s.radius > h) spawn(s, w, h, false);
                }
                case Style.BUBBLES -> {
                    s.x += (float) Math.sin(seconds * 2.2f + s.phase) * w * 0.08f * dt;
                    s.y += s.vy * dt;
                    if (s.y + s.radius < 0) spawn(s, w, h, false);
                }
                case Style.BURST -> {
                    s.vy += mGravity * 0.35f * dt;
                    s.vx *= 0.995f;
                    s.x += s.vx * dt;
                    s.y += s.vy * dt;
                    bounceOffEdges(s, w, h);
                    collide = true;
                }
                default -> {
                    s.vy += mGravity * dt;
                    s.x += s.vx * dt;
                    s.y += s.vy * dt;
                    bounceOffEdges(s, w, h);
                    collide = true;
                }
            }
        }
        if (collide) pushApart();
    }

    private void bounceOffEdges(Sprite s, int w, int h) {
        if (s.y + s.radius > h) {
            s.y = h - s.radius;
            s.vy = -s.vy * BOUNCE;
            s.vx *= FRICTION;
            s.spin *= 0.8f;
        } else if (s.y - s.radius < 0 && s.vy < 0) {
            s.y = s.radius;
            s.vy = -s.vy * BOUNCE;
        }
        if (s.x - s.radius < 0) {
            s.x = s.radius;
            s.vx = -s.vx * BOUNCE;
        } else if (s.x + s.radius > w) {
            s.x = w - s.radius;
            s.vx = -s.vx * BOUNCE;
        }
    }

    private void pushApart() {
        for (int i = 0; i < mSprites.size(); i++) {
            Sprite a = mSprites.get(i);
            if (!a.active) continue;
            for (int j = i + 1; j < mSprites.size(); j++) {
                Sprite b = mSprites.get(j);
                if (!b.active) continue;
                float dx = b.x - a.x, dy = b.y - a.y;
                float min = (a.radius + b.radius) * 0.85f;
                float dist2 = dx * dx + dy * dy;
                if (dist2 >= min * min || dist2 == 0f) continue;
                float dist = (float) Math.sqrt(dist2);
                float nx = dx / dist, ny = dy / dist;
                float push = (min - dist) / 2f;
                a.x -= nx * push; a.y -= ny * push;
                b.x += nx * push; b.y += ny * push;
                float rel = (b.vx - a.vx) * nx + (b.vy - a.vy) * ny;
                if (rel < 0) {
                    float impulse = -(1 + BOUNCE) * rel / 2f;
                    a.vx -= impulse * nx; a.vy -= impulse * ny;
                    b.vx += impulse * nx; b.vy += impulse * ny;
                }
            }
        }
    }
}
