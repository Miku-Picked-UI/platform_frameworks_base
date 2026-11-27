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
    private static final float MIN_CONTRAST = 1.6f;


    private final boolean mMaterial;
    private final GlassThumb mThumb = new GlassThumb();

    private final GlassMotion mMotion = new GlassMotion();


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
        float density = view.getResources().getDisplayMetrics().density;
        mMotion.update(fraction, pressed);
        if (mMotion.animating()) view.postInvalidateOnAnimation();
        mThumb.drawSlider(canvas, 0f, view.getWidth(), view.getHeight() / 2f, fraction,
                view.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL, mMotion.press(),
                mMotion.stretch(), density, active, inactive,
                view.isEnabled() ? 1f : DISABLED_ALPHA, ticks, tickActive, tickInactive, 0f,
                view.getHeight());
    }

    /** ThumbContrast: darkens the fill until the white thumb stands out against it. */
    static int againstThumb(int color) {
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

    private static final android.util.ArrayMap<String, Field> sFields = new android.util.ArrayMap<>();

    private static Object field(View view, String name) throws ReflectiveOperationException {
        String key = view.getClass().getName() + '#' + name;
        Field cached = sFields.get(key);
        if (cached != null) return cached.get(view);
        Class<?> type = view.getClass();
        while (type != null && type != View.class) {
            try {
                Field f = type.getDeclaredField(name);
                f.setAccessible(true);
                sFields.put(key, f);
                return f.get(view);
            } catch (NoSuchFieldException e) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }
}
