/*
 * SPDX-FileCopyrightText: 2026 DrDisagree, Miku-Picked UI
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Ported from Glass Layer (github.com/Mahmud0808/GlassLayer, GPL-3.0): SwitchGlass (view
 * switches), ViewSwitchMotion, KyantTrackColors and SurfaceBehind, in Java. A Switch or
 * SwitchCompat (MaterialSwitch) gets a 64 x 28 dp tinted glass track with a lit rim and a white
 * capsule thumb that, held, dragged or flipping, swells into a Liquid Glass lens and stretches
 * with its speed.
 *
 * Miku-Picked UI: the thumb can also be dragged where the row, not the switch, takes the touch
 * (a SwitchPreference's switch isn't clickable): letting go past the middle clicks the row.
 */

package com.android.internal.graphics.picked;

import android.animation.Animator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.Checkable;

import com.android.internal.graphics.ColorUtils;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * The Glass Layer switch for {@link android.widget.Switch} and AndroidX SwitchCompat.
 *
 * @hide
 */
final class GlassSwitch implements View.PickedDrawDecoration {

    static final String SWITCH_COMPAT = "androidx.appcompat.widget.SwitchCompat";

    private static final int DRAWN = 1;
    private static final float WIDTH_DP = 64f;
    private static final float HEIGHT_DP = 28f;
    private static final float DISABLED_ALPHA = 0.38f;
    private static final int TOUCH_MODE_DRAGGING = 2;
    /** Glass Layer's default switch tint. */
    private static final float TINT = 0.55f;

    // KyantTrackColors: the off track.
    private static final int DARK_OFF = Color.argb(92, 120, 120, 128);
    private static final int LIGHT_OFF = Color.argb(51, 120, 120, 120);


    // GlassRim.
    private static final float STROKE_DP = 1f;
    private static final float TOP_ALPHA = 0.2f;
    private static final float BOTTOM_ALPHA = 0.06f;
    private static final float SHEEN_ALPHA = 0.14f;
    private static final float SHEEN_SPAN = 0.55f;

    private static final int[] CHECKED = {android.R.attr.state_checked,
        android.R.attr.state_enabled};
    private static final int[] DISABLED_CHECKED = {android.R.attr.state_checked};

    private final GlassThumb mThumb = new GlassThumb();
    private final Paint mTrack = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mRim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mSheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mPath = new Path();
    private final RectF mRect = new RectF();
    private float mShaderTop = Float.NaN;
    private float mShaderHeight;

    private boolean mHidden;
    private int mStockMinWidth = -1;

    private final GlassMotion mMotion = new GlassMotion();

    // Dragging, for switches whose row takes the touch.
    private static final int TOUCH_NONE = 0;
    private static final int TOUCH_DOWN = 1;
    private static final int TOUCH_DRAG = 2;
    private static final int MAX_ROW_DEPTH = 5;
    private static final long SETTLE_MS = 120L;
    private float mTrackLeft;
    private float mTrackTop;
    private float mTrackWidth;
    private float mTrackHeight;
    private float mTravel;
    private boolean mRtl;
    private int mTouch = TOUCH_NONE;
    private float mDownX;
    private float mDownPosition;
    private View mRow;
    /** A finger is on the switch: the lens stays while it's held, dragged or not. */
    private boolean mTouching;

    GlassSwitch() {
        mRim.setStyle(Paint.Style.STROKE);
    }

    /** Widens the switch to Glass Layer's 64 dp track before it's measured. */
    void widen(View view) {
        if (!GlassLayer.isActive(GlassLayer.SWITCHES)) return;
        try {
            int current = (Integer) field(view, "mSwitchMinWidth");
            int target = (int) (WIDTH_DP * view.getResources().getDisplayMetrics().density);
            if (current >= target) return;
            if (mStockMinWidth < 0) mStockMinWidth = current;
            setField(view, "mSwitchMinWidth", target);
            view.requestLayout();
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Another SwitchCompat version: keep its width.
        }
    }

    @Override
    public int onPreDraw(View view, Canvas canvas) {
        try {
            if (!GlassLayer.isActive(GlassLayer.SWITCHES) || !canvas.isHardwareAccelerated()) {
                restore(view);
                return 0;
            }
            if (mStockMinWidth < 0) widen(view);
            setAlpha((Drawable) field(view, "mTrackDrawable"), 0);
            setAlpha((Drawable) field(view, "mThumbDrawable"), 0);
            setAlpha(view.getBackground(), 0);
            mHidden = true;
            return DRAWN;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return 0;
        }
    }

    @Override
    public void onPostDraw(View view, Canvas canvas, int token) {
        if ((token & DRAWN) == 0) return;
        try {
            drawToggle(view, canvas);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Fields renamed: the switch just shows without its glass.
        }
    }

    private void restore(View view) {
        if (!mHidden) return;
        mHidden = false;
        try {
            setAlpha((Drawable) field(view, "mTrackDrawable"), 255);
            setAlpha((Drawable) field(view, "mThumbDrawable"), 255);
            setAlpha(view.getBackground(), 255);
            if (mStockMinWidth >= 0) {
                setField(view, "mSwitchMinWidth", mStockMinWidth);
                mStockMinWidth = -1;
                view.requestLayout();
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Nothing more to give back.
        }
    }

    private void drawToggle(View view, Canvas canvas) throws ReflectiveOperationException {
        float switchLeft = ((Number) field(view, "mSwitchLeft")).floatValue();
        float switchTop = ((Number) field(view, "mSwitchTop")).floatValue();
        float switchRight = ((Number) field(view, "mSwitchRight")).floatValue();
        float switchBottom = ((Number) field(view, "mSwitchBottom")).floatValue();
        float density = view.getResources().getDisplayMetrics().density;
        boolean rtl = view.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        float start = Math.max(switchLeft, view.getPaddingLeft());
        float end = Math.min(switchRight, view.getWidth() - view.getPaddingRight());
        float width = Math.min(end - start, WIDTH_DP * density);
        float height = Math.min(Math.min(switchBottom - switchTop, HEIGHT_DP * density),
                width * HEIGHT_DP / WIDTH_DP);
        if (width <= 0f || height <= 0f) return;
        float left = rtl ? start : end - width;
        float top = (switchTop + switchBottom - height) / 2f;
        mTrackLeft = left;
        mTrackTop = top;
        mTrackWidth = width;
        mTrackHeight = height;
        mTravel = GlassThumb.toggleTravel(width, height, density);
        mRtl = rtl;

        boolean checked = view instanceof Checkable c && c.isChecked();
        Object position = field(view, "mThumbPosition");
        float raw = position instanceof Number n ? n.floatValue() : (checked ? 1f : 0f);
        Object touchMode = field(view, "mTouchMode");
        boolean dragging = touchMode instanceof Number t && t.intValue() == TOUCH_MODE_DRAGGING;

        mMotion.update(raw, view.isPressed() || dragging || mTouching);
        if (mMotion.animating()) view.postInvalidateOnAnimation();

        boolean enabled = view.isEnabled();
        ColorStateList tints = (ColorStateList) field(view, "mTrackTintList");
        if (tints == null) {
            GradientDrawable shape = SolidShapes.of((Drawable) field(view, "mTrackDrawable"));
            tints = shape != null ? shape.getColor() : null;
        }
        int on = GlassSlider.againstThumb(tints != null
                ? tints.getColorForState(enabled ? CHECKED : DISABLED_CHECKED,
                        tints.getDefaultColor())
                : accent(view.getContext()));
        int off = offColor(view.getContext(), enabled);
        int trackColor = ColorUtils.blendARGB(off, on, Math.max(0f, Math.min(1f, raw)));
        float alpha = enabled ? 1f : DISABLED_ALPHA;

        drawTrack(canvas, left, top, width, height, trackColor, density);
        int behind = opaque(surfaceBehind(view));
        mThumb.drawToggle(canvas, left, top, width, height, rtl ? 1f - raw : raw,
                mMotion.press(), mMotion.stretch(), density, over(trackColor, behind), alpha, 0f, view.getWidth(), 0f,
                view.getHeight());
    }

    @Override
    public boolean onTouchEvent(View view, MotionEvent event) {
        if (!mHidden || !GlassLayer.isActive(GlassLayer.SWITCHES)) return false;
        boolean taken;
        try {
            taken = touch(view, event);
        } catch (ReflectiveOperationException | RuntimeException e) {
            mTouch = TOUCH_NONE;
            mRow = null;
            taken = false;
        }
        // Holding the lens while touched: a clickable switch drags itself, so this just watches.
        boolean touching = switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN -> taken || view.isClickable();
            case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> false;
            default -> mTouching;
        };
        if (touching != mTouching) {
            mTouching = touching;
            // Pressing doesn't always redraw a switch (its drawables may not have a pressed look).
            view.postInvalidateOnAnimation();
        }
        return taken;
    }

    private boolean touch(View view, MotionEvent event) throws ReflectiveOperationException {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN -> {
                mTouch = TOUCH_NONE;
                mRow = null;
                // A clickable switch drags itself; this is for the ones whose row takes touches.
                if (view.isClickable() || mTravel <= 0f || !(view instanceof Checkable)) {
                    return false;
                }
                float slop = ViewConfiguration.get(view.getContext()).getScaledTouchSlop();
                float x = event.getX();
                float y = event.getY();
                if (x < mTrackLeft - slop || x > mTrackLeft + mTrackWidth + slop
                        || y < mTrackTop - slop || y > mTrackTop + mTrackHeight + slop) {
                    return false;
                }
                View row = clickableRow(view);
                if (row == null) return false;
                mRow = row;
                mDownX = x;
                mDownPosition = thumbPosition(view);
                mTouch = TOUCH_DOWN;
                view.setPressed(true);
                return true;
            }
            case MotionEvent.ACTION_MOVE -> {
                if (mTouch == TOUCH_NONE) return false;
                float x = event.getX();
                if (mTouch == TOUCH_DOWN) {
                    float slop = ViewConfiguration.get(view.getContext()).getScaledTouchSlop();
                    if (Math.abs(x - mDownX) <= slop) return true;
                    mTouch = TOUCH_DRAG;
                    mDownX = x;
                    mDownPosition = thumbPosition(view);
                    Object animator = field(view, "mPositionAnimator");
                    if (animator instanceof Animator a) a.cancel();
                    ViewParent parent = view.getParent();
                    if (parent != null) parent.requestDisallowInterceptTouchEvent(true);
                }
                float delta = (x - mDownX) / mTravel * (mRtl ? -1f : 1f);
                setThumbPosition(view, Math.max(0f, Math.min(1f, mDownPosition + delta)));
                return true;
            }
            case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                int mode = mTouch;
                View row = mRow;
                mTouch = TOUCH_NONE;
                mRow = null;
                if (mode == TOUCH_NONE) return false;
                view.setPressed(false);
                boolean clicked = false;
                if (event.getActionMasked() == MotionEvent.ACTION_UP && row != null) {
                    boolean checked = ((Checkable) view).isChecked();
                    // A tap clicks the row as it would have; a drag only when it ends on the
                    // other side. The preference then sets the switch, which slides on from here.
                    if (mode == TOUCH_DOWN || (thumbPosition(view) >= 0.5f) != checked) {
                        clicked = row.performClick();
                    }
                }
                if (clicked) {
                    // In case the preference turned the change down, or binds it later.
                    view.postDelayed(() -> settle(view), SETTLE_MS);
                } else {
                    settle(view);
                }
                return true;
            }
            default -> {
                return mTouch != TOUCH_NONE;
            }
        }
    }

    /** Slides a let-go thumb to where the switch's state says, if nothing else did. */
    private void settle(View view) {
        if (mTouch != TOUCH_NONE || !view.isAttachedToWindow()) return;
        try {
            float target = ((Checkable) view).isChecked() ? 1f : 0f;
            if (thumbPosition(view) == target) return;
            Object animator = field(view, "mPositionAnimator");
            if (animator instanceof Animator a && a.isRunning()) return;
            Method animate = method(view, "animateThumbToCheckedState", boolean.class);
            if (animate != null) {
                animate.invoke(view, target == 1f);
            } else {
                setThumbPosition(view, target);
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Left where it is; the next bind puts it right.
        }
    }

    /** The row that handles the switch's clicks: a clickable parent, short of the list. */
    private static View clickableRow(View view) {
        ViewParent parent = view.getParent();
        for (int i = 0; i < MAX_ROW_DEPTH && parent instanceof ViewGroup group; i++) {
            if (group instanceof android.widget.AdapterView
                    || group.getClass().getName().endsWith("RecyclerView")) {
                return null;
            }
            if (group.isClickable() && group.isEnabled() && group.hasOnClickListeners()) {
                return group;
            }
            parent = group.getParent();
        }
        return null;
    }

    private static float thumbPosition(View view) throws ReflectiveOperationException {
        return ((Number) field(view, "mThumbPosition")).floatValue();
    }

    private static void setThumbPosition(View view, float position)
            throws ReflectiveOperationException {
        setField(view, "mThumbPosition", position);
        view.invalidate();
    }

    private static final android.util.ArrayMap<String, Method> sMethods =
            new android.util.ArrayMap<>();

    private static Method method(View view, String name, Class<?>... types) {
        String key = view.getClass().getName() + '#' + name;
        if (sMethods.containsKey(key)) return sMethods.get(key);
        Method found = null;
        for (Class<?> type = view.getClass(); type != null && type != View.class && found == null;
                type = type.getSuperclass()) {
            try {
                found = type.getDeclaredMethod(name, types);
                found.setAccessible(true);
            } catch (NoSuchMethodException e) {
                // Look further up.
            }
        }
        sMethods.put(key, found);
        return found;
    }

    private void drawTrack(Canvas canvas, float left, float top, float width, float height,
            int color, float density) {
        float radius = height / 2f;
        float tint = Math.min(1f, TINT * GlassLayer.tintScale());
        mTrack.setColor(ColorUtils.setAlphaComponent(color,
                Math.max(0, Math.min(255, Math.round(Color.alpha(color) * tint)))));
        canvas.drawRoundRect(left, top, left + width, top + height, radius, radius, mTrack);
        if (!GlassLayer.rim()) return;
        if (mShaderTop != top || mShaderHeight != height) {
            mShaderTop = top;
            mShaderHeight = height;
            mSheen.setShader(new LinearGradient(0f, top, 0f, top + height * SHEEN_SPAN,
                    white(SHEEN_ALPHA), Color.TRANSPARENT, Shader.TileMode.CLAMP));
            mRim.setShader(new LinearGradient(0f, top, 0f, top + height,
                    new int[] {white(TOP_ALPHA), Color.TRANSPARENT, white(BOTTOM_ALPHA)},
                    new float[] {0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        }
        canvas.drawRoundRect(left, top, left + width, top + height, radius, radius, mSheen);
        float stroke = STROKE_DP * density;
        mRim.setStrokeWidth(stroke);
        mRect.set(left + stroke / 2f, top + stroke / 2f, left + width - stroke / 2f,
                top + height - stroke / 2f);
        float r = Math.max(0f, radius - stroke / 2f);
        canvas.drawRoundRect(mRect, r, r, mRim);
    }

    private static int offColor(Context context, boolean enabled) {
        boolean night = (context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        int color = night ? DARK_OFF : LIGHT_OFF;
        return enabled ? color : ColorUtils.setAlphaComponent(color, Color.alpha(color) / 2);
    }

    /** SurfaceBehind: the first fairly opaque fill of a parent, or none. */
    private static int surfaceBehind(View view) {
        ViewParent parent = view.getParent();
        while (parent instanceof View p) {
            Drawable background = p.getBackground();
            Integer color = null;
            if (background instanceof ColorDrawable cd) {
                color = cd.getColor();
            } else {
                GradientDrawable shape = SolidShapes.of(background);
                if (shape != null && shape.getColor() != null) {
                    color = shape.getColor().getDefaultColor();
                }
            }
            if (color != null) {
                int a = Math.round(Color.alpha(color) * background.getAlpha() / 255f * p.getAlpha());
                if (a >= 32) return ColorUtils.setAlphaComponent(color, Math.min(255, a));
            }
            parent = parent.getParent();
        }
        return Color.TRANSPARENT;
    }

    private static int opaque(int color) {
        return Color.alpha(color) == 0 ? Color.BLACK : ColorUtils.setAlphaComponent(color, 255);
    }

    private static int over(int top, int bottom) {
        return ColorUtils.compositeColors(top, bottom);
    }

    private static int accent(Context context) {
        TypedValue value = new TypedValue();
        return context.getTheme().resolveAttribute(android.R.attr.colorAccent, value, true)
                ? value.data : Color.GRAY;
    }

    private static int white(float alpha) {
        return Color.argb(Math.round(Math.max(0f, Math.min(1f, alpha)) * 255), 255, 255, 255);
    }

    private static void setAlpha(Drawable drawable, int alpha) {
        if (drawable != null && drawable.getAlpha() != alpha) drawable.setAlpha(alpha);
    }

    private static final android.util.ArrayMap<String, Field> sFields = new android.util.ArrayMap<>();

    private static Field find(View view, String name) throws NoSuchFieldException {
        String key = view.getClass().getName() + '#' + name;
        Field cached = sFields.get(key);
        if (cached != null) return cached;
        for (Class<?> type = view.getClass(); type != null && type != View.class;
                type = type.getSuperclass()) {
            try {
                Field f = type.getDeclaredField(name);
                f.setAccessible(true);
                sFields.put(key, f);
                return f;
            } catch (NoSuchFieldException e) {
                // Look further up.
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static Object field(View view, String name) throws ReflectiveOperationException {
        return find(view, name).get(view);
    }

    private static void setField(View view, String name, Object value)
            throws ReflectiveOperationException {
        find(view, name).set(view, value);
    }
}
