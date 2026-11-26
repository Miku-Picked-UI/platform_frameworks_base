/*
 * SPDX-FileCopyrightText: 2026 DrDisagree, Miku-Picked UI
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Ported from Glass Layer (github.com/Mahmud0808/GlassLayer, GPL-3.0): SliderGlass (view
 * sliders), KyantThumb.drawSlider, ViewSwitchMotion and ThumbContrast, in Java. A thin slider
 * gets a 6 dp track and a white capsule thumb; held or dragged, the thumb grows, stretches with
 * the speed and turns into a Liquid Glass lens that refracts the track under it.
 */

package com.android.internal.graphics.picked;

import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RecordingCanvas;
import android.graphics.RenderNode;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewParent;
import android.widget.SeekBar;

import com.android.internal.graphics.ColorUtils;

import java.lang.reflect.Field;
import java.util.List;

/**
 * The Glass Layer slider for a plain {@link SeekBar} or a Material Components slider.
 *
 * @hide
 */
final class GlassSlider implements View.PickedDrawDecoration {

    static final String MATERIAL_SLIDER = "com.google.android.material.slider.BaseSlider";
    private static final String SYSTEMUI_MEDIA = "com.android.systemui.media.";

    // Glass Layer's SliderGlass / KyantThumb constants.
    private static final float TRADITIONAL_MAX_DP = 24f;
    private static final float INACTIVE_ALPHA = 0.3f;
    private static final float DISABLED_ALPHA = 0.38f;
    private static final float SLIDER_LONG_DP = 40f;
    private static final float SLIDER_SHORT_DP = 24f;
    private static final float SOURCE_SPAN = 3f;
    private static final float TRACK_DP = 6f;
    private static final float PRESS_SCALE = 0.3f;
    private static final float STRETCH = 0.6f;
    private static final float MAX_STRETCH = 0.1f;
    private static final float TICK_RADIUS_DP = 1.25f;
    private static final int MAX_TICKS = 50;
    private static final float VISIBLE_PRESS = 0.01f;
    private static final float MIN_CONTRAST = 1.6f;

    // ViewSwitchMotion: the press eases in over ~70 ms and follows the drag speed.
    private static final float FRAME_MS = 16f;
    private static final float RESPONSE_MS = 70f;
    private static final float MOVING = 0.002f;
    private static final float SETTLED = 0.01f;

    private final boolean mMaterial;
    private final RenderNode mSource = new RenderNode("GlassLayerSliderSource");
    private final GlassLens mLens = new GlassLens(2f, 1.2f, 0.9f, 1.2f);
    private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mThumb = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mLensPath = new Path();
    private final Matrix mLensMatrix = new Matrix();
    private float mSourceLeft;
    private float mSourceTop;

    private float mPress;
    private float mVelocity;
    private float mLastPosition = Float.NaN;
    private long mLastAt;

    private int mContrastIn;
    private int mContrastOut;

    GlassSlider(boolean material) {
        mMaterial = material;
    }

    @Override
    public int onPreDraw(View view, Canvas canvas) {
        if (!GlassLayer.isActive(GlassLayer.SLIDERS) || !canvas.isHardwareAccelerated()) return 0;
        try {
            return (mMaterial ? drawMaterial(view, canvas) : drawSeekBar((SeekBar) view, canvas))
                    ? GlassLayer.SKIP_DRAW : 0;
        } catch (ReflectiveOperationException | RuntimeException e) {
            // A Material version with other field names: leave the slider as it is.
            return 0;
        }
    }

    @Override
    public void onPostDraw(View view, Canvas canvas, int token) {}

    private boolean drawSeekBar(SeekBar view, Canvas canvas) {
        float density = view.getResources().getDisplayMetrics().density;
        if (view.getMaxHeight() > TRADITIONAL_MAX_DP * density || inMedia(view)) return false;
        int range = Math.max(1, view.getMax() - view.getMin());
        float fraction = (view.getProgress() - view.getMin()) / (float) range;
        int[] state = view.getDrawableState();
        ColorStateList progress = view.getProgressTintList();
        int active = againstThumb(progress != null
                ? progress.getColorForState(state, Color.WHITE) : accent(view));
        ColorStateList background = view.getProgressBackgroundTintList();
        int inactive = background != null ? background.getColorForState(state, Color.GRAY)
                : scaleAlpha(active, INACTIVE_ALPHA);
        draw(view, canvas, fraction, view.isPressed(), active, inactive,
                view.getTickMark() != null ? range : 0, inactive, active);
        return true;
    }

    private boolean drawMaterial(View view, Canvas canvas) throws ReflectiveOperationException {
        Object values = field(view, "values");
        if (!(values instanceof List<?> list) || list.size() != 1) return false;
        float density = view.getResources().getDisplayMetrics().density;
        if ((int) field(view, "trackThickness") > TRADITIONAL_MAX_DP * density) return false;
        float value = (Float) list.get(0);
        float from = (Float) field(view, "valueFrom");
        float to = (Float) field(view, "valueTo");
        int[] state = view.getDrawableState();
        ColorStateList activeList = (ColorStateList) field(view, "trackColorActive");
        ColorStateList inactiveList = (ColorStateList) field(view, "trackColorInactive");
        int active = againstThumb(activeList != null
                ? activeList.getColorForState(state, activeList.getDefaultColor()) : accent(view));
        int inactive = inactiveList != null
                ? inactiveList.getColorForState(state, inactiveList.getDefaultColor())
                : scaleAlpha(active, INACTIVE_ALPHA);
        float fraction = to > from ? (value - from) / (to - from) : 0f;
        float step = (Float) field(view, "stepSize");
        Object coords = field(view, "ticksCoordinates");
        boolean shown = coords instanceof float[] c && c.length > 0;
        int ticks = step > 0f && shown && to > from ? Math.round((to - from) / step) : 0;
        ColorStateList tickActive = (ColorStateList) field(view, "tickColorActive");
        ColorStateList tickInactive = (ColorStateList) field(view, "tickColorInactive");
        draw(view, canvas, fraction, Boolean.TRUE.equals(field(view, "thumbIsPressed")), active,
                inactive, ticks,
                tickActive != null ? tickActive.getColorForState(state,
                        tickActive.getDefaultColor()) : inactive,
                tickInactive != null ? tickInactive.getColorForState(state,
                        tickInactive.getDefaultColor()) : active);
        return true;
    }

    private void draw(View view, Canvas canvas, float fraction, boolean pressed, int active,
            int inactive, int ticks, int tickActive, int tickInactive) {
        long now = SystemClock.uptimeMillis();
        float elapsed = mLastAt == 0L ? FRAME_MS : Math.max(1L, now - mLastAt);
        mVelocity = Float.isNaN(mLastPosition) ? 0f
                : (fraction - mLastPosition) * FRAME_MS / elapsed;
        mLastPosition = fraction;
        mLastAt = now;
        boolean moving = Math.abs(mVelocity) > MOVING;
        float goal = pressed || moving ? 1f : 0f;
        mPress += (goal - mPress) * (1f - (float) Math.exp(-elapsed / RESPONSE_MS));
        if (Math.abs(goal - mPress) < SETTLED) mPress = goal;
        if (mPress != goal || moving) view.postInvalidateOnAnimation();

        float density = view.getResources().getDisplayMetrics().density;
        boolean rtl = view.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        float alpha = view.isEnabled() ? 1f : DISABLED_ALPHA;
        float centerY = view.getHeight() / 2f;
        float capsuleWidth = SLIDER_LONG_DP * density;
        float capsuleHeight = SLIDER_SHORT_DP * density;
        float reach = capsuleWidth / 2f * (1f + PRESS_SCALE) * (1f + MAX_STRETCH);
        float centerMin = reach;
        float centerMax = view.getWidth() - reach;
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

        // What the lens refracts: the track around the thumb.
        float sourceWidth = capsuleWidth * SOURCE_SPAN;
        float sourceHeight = capsuleHeight * SOURCE_SPAN;
        mSourceLeft = centerX - sourceWidth / 2f;
        mSourceTop = centerY - sourceHeight / 2f;
        mSource.setPosition(0, 0, Math.max(1, (int) sourceWidth), Math.max(1, (int) sourceHeight));
        RecordingCanvas recording = mSource.beginRecording();
        try {
            recording.translate(-mSourceLeft, -mSourceTop);
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
        drawCapsule(canvas, centerX, centerY, capsuleWidth, capsuleHeight, alpha,
                view.getHeight());
    }

    private void drawCapsule(Canvas canvas, float centerX, float centerY, float width,
            float height, float alpha, float viewHeight) {
        float stretch = Math.min(Math.abs(mVelocity) * STRETCH, MAX_STRETCH);
        float scale = 1f + PRESS_SCALE * mPress;
        float roomY = 2f * Math.min(centerY, viewHeight - centerY) / height;
        float scaleX = scale * (1f + stretch);
        float scaleY = Math.min(scale * (1f - stretch / 2f), Math.max(1f, roomY));
        float radius = Math.min(width, height) / 2f;

        int count = canvas.save();
        canvas.translate(centerX, centerY);
        canvas.scale(scaleX, scaleY);
        canvas.translate(-width / 2f, -height / 2f);
        if (mPress > VISIBLE_PRESS) {
            mLensPath.rewind();
            mLensPath.addRoundRect(0f, 0f, width, height, radius, radius, Path.Direction.CW);
            mLensMatrix.setTranslate(mSourceLeft - centerX, mSourceTop - centerY);
            mLensMatrix.postScale(1f / scaleX, 1f / scaleY);
            mLensMatrix.postTranslate(width / 2f, height / 2f);
            mLens.drawSource(canvas, mLensPath, mSource, mLensMatrix, width, height, radius,
                    mPress * alpha);
        }
        mThumb.setColor(Color.WHITE);
        mThumb.setAlpha(Math.max(0, Math.min(255, (int) (255 * (1f - mPress) * alpha))));
        canvas.drawRoundRect(0f, 0f, width, height, radius, radius, mThumb);
        canvas.restoreToCount(count);
    }

    private void setFill(int color, float alpha) {
        mFill.setColor(color);
        mFill.setAlpha((int) (Color.alpha(color) * alpha));
    }

    /** ThumbContrast: darkens the fill until the white thumb stands out against it. */
    private int againstThumb(int color) {
        if (color == mContrastIn && mContrastOut != 0) return mContrastOut;
        int out = color;
        int a = Color.alpha(color);
        if (a != 0) {
            int opaque = ColorUtils.setAlphaComponent(color, 255);
            if (ColorUtils.calculateContrast(Color.WHITE, opaque) < MIN_CONTRAST) {
                float low = 0f;
                float high = 1f;
                for (int i = 0; i < 8; i++) {
                    float mid = (low + high) / 2f;
                    if (ColorUtils.calculateContrast(Color.WHITE,
                            ColorUtils.blendARGB(opaque, Color.BLACK, mid)) >= MIN_CONTRAST) {
                        high = mid;
                    } else {
                        low = mid;
                    }
                }
                out = ColorUtils.setAlphaComponent(
                        ColorUtils.blendARGB(opaque, Color.BLACK, high), a);
            }
        }
        mContrastIn = color;
        mContrastOut = out;
        return out;
    }

    private static int scaleAlpha(int color, float fraction) {
        return ColorUtils.setAlphaComponent(color,
                Math.max(0, Math.min(255, Math.round(Color.alpha(color) * fraction))));
    }

    private static int accent(View view) {
        TypedValue value = new TypedValue();
        return view.getContext().getTheme().resolveAttribute(android.R.attr.colorAccent, value,
                true) ? value.data : Color.WHITE;
    }

    /** SystemUI's media player keeps its own seek bar. */
    private static boolean inMedia(View view) {
        ViewParent parent = view.getParent();
        while (parent instanceof View p) {
            if (p.getClass().getName().startsWith(SYSTEMUI_MEDIA)) return true;
            parent = parent.getParent();
        }
        return false;
    }

    private static Object field(View view, String name) throws ReflectiveOperationException {
        Class<?> type = view.getClass();
        while (type != null && type != View.class) {
            try {
                Field f = type.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(view);
            } catch (NoSuchFieldException e) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }
}
