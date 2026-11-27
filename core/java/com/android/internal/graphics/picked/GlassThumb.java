/*
 * SPDX-FileCopyrightText: 2026 DrDisagree, Miku-Picked UI
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Ported from Glass Layer (github.com/Mahmud0808/GlassLayer, GPL-3.0): KyantThumb (drawSlider,
 * drawToggle, drawCapsule), in Java. A white capsule thumb; pressed, it grows, stretches with its
 * speed and turns into a Liquid Glass lens over what lies under it (recorded into a RenderNode).
 */

package com.android.internal.graphics.picked;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RecordingCanvas;
import android.graphics.RenderNode;

/**
 * Glass Layer's Kyant-style glass thumb for sliders and switches.
 *
 * @hide
 */
final class GlassThumb {

    // KyantThumb constants.
    private static final float PADDING_DP = 2f;
    private static final float RATIO = 5f / 3f;
    private static final float MIN_TRAVEL = 0.45f;
    private static final float PRESS_SCALE = 0.5f;
    private static final float MAX_STRETCH = 0.2f;
    private static final float VISIBLE_PRESS = 0.01f;
    private static final float TOGGLE_LENS_X_START = 2f / 3f;
    private static final float TOGGLE_LENS_X_END = 0.75f;
    private static final float TOGGLE_LENS_Y_END = 0.75f;
    private static final float SLIDER_LONG_DP = 40f;
    private static final float SLIDER_SHORT_DP = 24f;
    private static final float SOURCE_SPAN = 3f;
    private static final float TRACK_DP = 6f;
    private static final float SLIDER_PRESS_SCALE = 0.3f;
    private static final float SLIDER_MAX_STRETCH = 0.1f;
    private static final float TICK_RADIUS_DP = 1.25f;
    private static final int MAX_TICKS = 50;

    private final RenderNode mSource = new RenderNode("GlassLayerThumbSource");
    private final GlassLens mLens = new GlassLens(2f, 1.2f, 0.9f, 1.2f);
    private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mThumb = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mLensPath = new Path();
    private final Matrix mLensMatrix = new Matrix();
    private float mSourceLeft;
    private float mSourceTop;

    /** A slider across [boundsStart, boundsEnd] at {@code centerY}: track, ticks and thumb. */
    void drawSlider(Canvas canvas, float boundsStart, float boundsEnd, float centerY,
            float fraction, boolean rtl, float press, float stretch, float density,
            int active, int inactive, float alpha, int ticks, int tickActive, int tickInactive,
            float boundsTop, float boundsBottom) {
        float capsuleWidth = SLIDER_LONG_DP * density;
        float capsuleHeight = SLIDER_SHORT_DP * density;
        float reach = capsuleWidth / 2f * (1f + SLIDER_PRESS_SCALE) * (1f + SLIDER_MAX_STRETCH);
        float centerMin = boundsStart + reach;
        float centerMax = boundsEnd - reach;
        if (centerMax <= centerMin) return;
        float trackStart = centerMin - capsuleWidth / 4f;
        float trackEnd = centerMax + capsuleWidth / 4f;
        float progress = Math.max(0f, Math.min(1f, fraction));
        float centerX = centerMin + (rtl ? 1f - progress : progress) * (centerMax - centerMin);
        float thickness = TRACK_DP * density;
        float radius = thickness / 2f;
        float top = centerY - radius;

        setFill(inactive, alpha);
        canvas.drawRoundRect(trackStart, top, trackEnd, top + thickness, radius, radius, mFill);
        float fill = (trackEnd - trackStart) * progress;
        float fillStart = rtl ? trackEnd - fill : trackStart;
        float fillEnd = rtl ? trackEnd : trackStart + fill;
        if (fill > 0f) {
            setFill(active, alpha);
            canvas.drawRoundRect(fillStart, top, fillEnd, top + thickness, radius, radius, mFill);
        }
        if (ticks >= 1 && ticks <= MAX_TICKS) {
            float dot = TICK_RADIUS_DP * density;
            for (int i = 0; i <= ticks; i++) {
                float x = centerMin + i * (centerMax - centerMin) / ticks;
                setFill(x >= fillStart && x <= fillEnd ? tickActive : tickInactive, alpha);
                canvas.drawCircle(x, centerY, dot, mFill);
            }
        }

        float sourceWidth = capsuleWidth * SOURCE_SPAN;
        float sourceHeight = capsuleHeight * SOURCE_SPAN;
        float sourceLeft = centerX - sourceWidth / 2f;
        float sourceTop = centerY - sourceHeight / 2f;
        RecordingCanvas recording = beginSource(sourceLeft, sourceTop, sourceWidth, sourceHeight);
        try {
            recording.translate(-sourceLeft, -sourceTop);
            mFill.setColor(inactive);
            mFill.setAlpha(255);
            recording.drawRoundRect(trackStart, top, trackEnd, top + thickness, radius, radius,
                    mFill);
            if (fill > 0f) {
                mFill.setColor(active);
                recording.drawRoundRect(fillStart, top, fillEnd, top + thickness, radius, radius,
                        mFill);
            }
        } finally {
            mSource.endRecording();
        }
        drawCapsule(canvas, 0f, 0f, centerX, centerY, capsuleWidth, capsuleHeight, press,
                stretch, true, alpha, SLIDER_PRESS_SCALE, SLIDER_MAX_STRETCH,
                Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY, boundsTop, boundsBottom, 1f, 1f);
    }

    /**
     * A switch's thumb on a track at ({@code left}, {@code top}); {@code position} 0..1 from the
     * start. {@code trackColor} is what the lens refracts (the track, opaque).
     */
    void drawToggle(Canvas canvas, float left, float top, float width, float height,
            float position, float press, float stretch, float density, int trackColor,
            float alpha, float boundsStart, float boundsEnd, float boundsTop,
            float boundsBottom) {
        if (width <= 0f || height <= 0f) return;
        float margin = height;
        RecordingCanvas recording = beginSource(-margin, -margin, width + margin * 2f,
                height + margin * 2f);
        try {
            mFill.setColor(trackColor | 0xFF000000);
            mFill.setAlpha(255);
            recording.drawRoundRect(margin, margin, margin + width, margin + height, height / 2f,
                    height / 2f, mFill);
        } finally {
            mSource.endRecording();
        }
        float padding = PADDING_DP * density;
        float thumbHeight = height - padding * 2f;
        float thumbWidth = Math.min(thumbHeight * RATIO,
                width - padding * 2f - thumbHeight * MIN_TRAVEL);
        float travel = width - padding * 2f - thumbWidth;
        float centerX = padding + Math.max(0f, Math.min(1f, position)) * travel + thumbWidth / 2f;
        drawCapsule(canvas, left, top, centerX, height / 2f, thumbWidth, thumbHeight, press,
                stretch, true, alpha, PRESS_SCALE, MAX_STRETCH,
                boundsStart - left, boundsEnd - left, boundsTop - top, boundsBottom - top,
                TOGGLE_LENS_X_START + (TOGGLE_LENS_X_END - TOGGLE_LENS_X_START) * press,
                TOGGLE_LENS_Y_END * press);
    }

    private RecordingCanvas beginSource(float left, float top, float width, float height) {
        mSourceLeft = left;
        mSourceTop = top;
        mSource.setPosition(0, 0, Math.max(1, (int) width), Math.max(1, (int) height));
        return mSource.beginRecording();
    }

    private void drawCapsule(Canvas canvas, float left, float top, float centerX, float centerY,
            float width, float height, float press, float stretchIn, boolean horizontal,
            float alpha, float pressScale, float maxStretch, float boundsStart, float boundsEnd,
            float boundsTop, float boundsBottom, float lensScaleX, float lensScaleY) {
        float stretch = Math.min(maxStretch, stretchIn);
        float scale = 1f + pressScale * press;
        float roomX = 2f * Math.min(centerX - boundsStart, boundsEnd - centerX) / width;
        float roomY = 2f * Math.min(centerY - boundsTop, boundsBottom - centerY) / height;
        float scaleX = Math.min(scale * (horizontal ? 1f + stretch : 1f - stretch / 2f),
                Math.max(1f, roomX));
        float scaleY = Math.min(scale * (horizontal ? 1f - stretch / 2f : 1f + stretch),
                Math.max(1f, roomY));
        float radius = Math.min(width, height) / 2f;

        int count = canvas.save();
        canvas.translate(left + centerX, top + centerY);
        canvas.scale(scaleX, scaleY);
        canvas.translate(-width / 2f, -height / 2f);
        if (press > VISIBLE_PRESS) {
            mLensPath.rewind();
            mLensPath.addRoundRect(0f, 0f, width, height, radius, radius, Path.Direction.CW);
            mLensMatrix.setTranslate(mSourceLeft - centerX, mSourceTop - centerY);
            mLensMatrix.postScale(1f / scaleX, 1f / scaleY);
            mLensMatrix.postScale(lensScaleX, lensScaleY);
            mLensMatrix.postTranslate(width / 2f, height / 2f);
            mLens.drawSource(canvas, mLensPath, mSource, mLensMatrix, width, height, radius,
                    press * alpha);
        }
        mThumb.setColor(Color.WHITE);
        mThumb.setAlpha(Math.max(0, Math.min(255, (int) (255 * (1f - press) * alpha))));
        canvas.drawRoundRect(0f, 0f, width, height, radius, radius, mThumb);
        canvas.restoreToCount(count);
    }

    private void setFill(int color, float alpha) {
        mFill.setColor(color);
        mFill.setAlpha((int) (Color.alpha(color) * alpha));
    }
}
