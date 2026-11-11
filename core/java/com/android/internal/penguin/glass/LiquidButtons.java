/*
 * SPDX-FileCopyrightText: 2026 Miku-Picked UI
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.internal.penguin.glass;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Insets;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Process;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.StateSet;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewRootImpl;
import android.widget.Button;
import android.widget.CompoundButton;

import com.android.internal.dynamicanimation.animation.SpringAnimation;
import com.android.internal.dynamicanimation.animation.SpringForce;
import com.android.internal.graphics.drawable.BackgroundBlurDrawable;

import java.util.Arrays;
import java.util.WeakHashMap;

/**
 * Miku-Picked UI: iOS 26 style Liquid Glass buttons in apps.
 *
 * <p>For an app window, finds its buttons ({@link Button} and its subclasses, which include
 * AppCompat and Material buttons; not checkboxes, switches or radio buttons, and not text-only
 * buttons without a background) and gives each a {@link GlassButtonBackground}: a capsule of
 * live glass (a blur region SurfaceFlinger's renderengine draws as Liquid Glass when the
 * system-wide switch is on), tinted with the button's own color so apps keep their look, with a
 * light hairline edge, and a squish that springs back when pressed.
 *
 * <p>Off unless Settings.Secure {@code picked_glass_buttons} is 1; packages listed in
 * {@code picked_glass_buttons_excluded} (comma separated) are left alone. Buttons drawn by
 * Jetpack Compose are not views and keep their look.
 */
public final class LiquidButtons {
    public static final String SETTING_ENABLED = "picked_glass_buttons";
    public static final String SETTING_EXCLUDED = "picked_glass_buttons_excluded";

    private static final long SCAN_INTERVAL_MS = 400;

    private final WeakHashMap<View, Boolean> mDone = new WeakHashMap<>();
    private final float mDensity;
    private long mLastScan;
    private boolean mScanPending;

    private LiquidButtons(Context context) {
        mDensity = context.getResources().getDisplayMetrics().density;
    }

    /** The helper for an app window, or null when the feature is off for it. */
    public static LiquidButtons create(Context context) {
        if (Process.myUid() < Process.FIRST_APPLICATION_UID) return null;
        try {
            if (Settings.Secure.getInt(context.getContentResolver(), SETTING_ENABLED, 0) == 0) {
                return null;
            }
            final String list =
                    Settings.Secure.getString(context.getContentResolver(), SETTING_EXCLUDED);
            if (list != null
                    && Arrays.asList(list.split(",")).contains(context.getPackageName())) {
                return null;
            }
            return new LiquidButtons(context);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Called after the window laid out; restyles new buttons, at most every 400 ms. */
    public void onLayout(View root) {
        if (!(root instanceof ViewGroup)) return;
        final long now = SystemClock.uptimeMillis();
        final long wait = SCAN_INTERVAL_MS - (now - mLastScan);
        if (wait > 0) {
            if (!mScanPending) {
                mScanPending = true;
                root.postDelayed(() -> {
                    mScanPending = false;
                    if (root.isAttachedToWindow()) onLayout(root);
                }, wait);
            }
            return;
        }
        mLastScan = now;
        scan((ViewGroup) root);
    }

    private void scan(ViewGroup group) {
        for (int i = 0, n = group.getChildCount(); i < n; i++) {
            final View child = group.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE) continue;
            if (child instanceof Button button && !(child instanceof CompoundButton)) {
                maybeStyle(button);
            } else if (child instanceof ViewGroup sub) {
                scan(sub);
            }
        }
    }

    private void maybeStyle(Button button) {
        if (mDone.containsKey(button)) return;
        final Drawable original = button.getBackground();
        if (original == null || original instanceof GlassButtonBackground) return;
        final int color = buttonColor(button, original);
        // Text-only buttons (no visible fill) stay as they are.
        if (Color.alpha(color) < 0x20) {
            mDone.put(button, Boolean.TRUE);
            return;
        }
        final GlassButtonBackground glass =
                new GlassButtonBackground(button, original, color, mDensity);
        try {
            button.setBackground(glass);
            // The tint was the fill's color; the glass draws it itself.
            button.setBackgroundTintList(null);
            button.setStateListAnimator(null);
            button.setElevation(0f);
            button.setOutlineProvider(glass.mOutline);
        } catch (RuntimeException e) {
            // A button that refuses a new background keeps its own.
        }
        mDone.put(button, Boolean.TRUE);
    }

    /** The button's fill color: its background tint (Material, AppCompat) or its drawable's. */
    private static int buttonColor(Button button, Drawable background) {
        final ColorStateList tint = button.getBackgroundTintList();
        if (tint != null) return tint.getDefaultColor();
        final Integer fromDrawable = drawableColor(background);
        return fromDrawable != null ? fromDrawable : Color.WHITE;
    }

    private static Integer drawableColor(Drawable d) {
        if (d instanceof ColorDrawable c) return c.getColor();
        if (d instanceof GradientDrawable g) {
            final ColorStateList c = g.getColor();
            return c != null ? c.getDefaultColor() : null;
        }
        if (d instanceof LayerDrawable layers) {
            // RippleDrawable is a LayerDrawable: its content layer holds the fill.
            for (int i = 0; i < layers.getNumberOfLayers(); i++) {
                if (d instanceof RippleDrawable
                        && layers.getId(i) == android.R.id.mask) {
                    continue;
                }
                final Integer c = drawableColor(layers.getDrawable(i));
                if (c != null) return c;
            }
        }
        return null;
    }

    /**
     * The glass capsule. It draws inside the original background's optical insets (Material
     * buttons draw their visible shape inset inside a larger touch target).
     */
    static final class GlassButtonBackground extends Drawable {
        private static final float FILL_ALPHA = 0.55f;
        private static final float PRESSED_FILL_ALPHA = 0.75f;
        private static final int BLUR_RADIUS = 40;

        private final View mView;
        private final Insets mInsets;
        private final Rect mPadding = new Rect();
        private final boolean mHasPadding;
        private final int mColor;
        private final float mDensity;
        private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mRim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF mRect = new RectF();
        private BackgroundBlurDrawable mBlur;
        private boolean mPressed;
        private float mRimHeight = -1;
        private final SpringAnimation mScaleX;
        private final SpringAnimation mScaleY;

        final android.view.ViewOutlineProvider mOutline = new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                final Rect b = shapeBounds();
                outline.setRoundRect(b, b.height() / 2f);
                outline.setAlpha(0f);
            }
        };

        GlassButtonBackground(View view, Drawable original, int color, float density) {
            mView = view;
            mColor = color;
            mDensity = density;
            Insets insets = Insets.NONE;
            try {
                insets = original.getOpticalInsets();
            } catch (RuntimeException ignored) {
            }
            mInsets = insets;
            mHasPadding = original.getPadding(mPadding);
            mRim.setStyle(Paint.Style.STROKE);
            mRim.setStrokeWidth(Math.max(1f, density));
            mScaleX = spring(SpringAnimation.SCALE_X);
            mScaleY = spring(SpringAnimation.SCALE_Y);
        }

        private SpringAnimation spring(
                com.android.internal.dynamicanimation.animation.DynamicAnimation.ViewProperty
                        property) {
            final SpringAnimation anim = new SpringAnimation(mView, property, 1f);
            anim.getSpring().setDampingRatio(0.5f).setStiffness(SpringForce.STIFFNESS_MEDIUM);
            return anim;
        }

        private Rect shapeBounds() {
            final Rect b = new Rect(getBounds());
            b.left += mInsets.left;
            b.top += mInsets.top;
            b.right -= mInsets.right;
            b.bottom -= mInsets.bottom;
            return b;
        }

        @Override
        public void draw(Canvas canvas) {
            final Rect b = shapeBounds();
            if (b.width() <= 0 || b.height() <= 0) return;
            final float radius = b.height() / 2f;
            mRect.set(b);

            // Live glass behind the button (SurfaceFlinger draws it as Liquid Glass).
            if (mBlur == null) {
                final ViewRootImpl root = mView.getViewRootImpl();
                if (root != null) {
                    mBlur = root.createBackgroundBlurDrawable();
                    mBlur.setXfermode(null);
                    mBlur.setColor(0);
                }
            }
            if (mBlur != null) {
                mBlur.setBlurRadius(BLUR_RADIUS);
                mBlur.setCornerRadius(radius);
                mBlur.setBounds(b);
                mBlur.draw(canvas);
            }

            // The button's own color as a translucent tint.
            final float alpha = mPressed ? PRESSED_FILL_ALPHA : FILL_ALPHA;
            mFill.setColor(Color.argb(
                    Math.round(Color.alpha(mColor) * alpha),
                    Color.red(mColor), Color.green(mColor), Color.blue(mColor)));
            canvas.drawRoundRect(mRect, radius, radius, mFill);

            // A hairline edge lit from the top.
            if (mRimHeight != b.height()) {
                mRimHeight = b.height();
                mRim.setShader(new LinearGradient(0, b.top, 0, b.bottom,
                        Color.argb(0x8C, 0xFF, 0xFF, 0xFF), Color.argb(0x26, 0xFF, 0xFF, 0xFF),
                        Shader.TileMode.CLAMP));
            }
            final float inset = mRim.getStrokeWidth() / 2f;
            mRect.inset(inset, inset);
            canvas.drawRoundRect(mRect, radius - inset, radius - inset, mRim);
        }

        @Override
        public boolean isStateful() {
            return true;
        }

        @Override
        protected boolean onStateChange(int[] state) {
            final boolean pressed = StateSet.stateSetMatches(
                    new int[] {android.R.attr.state_pressed}, state);
            if (pressed == mPressed) return false;
            mPressed = pressed;
            // Squish while held, spring back with a little bounce when let go.
            final float target = pressed ? 0.94f : 1f;
            mScaleX.animateToFinalPosition(target);
            mScaleY.animateToFinalPosition(target);
            return true;
        }

        @Override
        public boolean getPadding(Rect padding) {
            padding.set(mPadding);
            return mHasPadding;
        }

        @Override
        public Insets getOpticalInsets() {
            return mInsets;
        }

        @Override
        public void setAlpha(int alpha) {}

        @Override
        public void setColorFilter(ColorFilter colorFilter) {}

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
