/*
 * SPDX-FileCopyrightText: 2026 Miku-Picked UI
 * SPDX-License-Identifier: Apache-2.0
 *
 * The native counterpart of Glass Layer's ViewGlassUnderlay and GlassRim (DrDisagree,
 * github.com/Mahmud0808/GlassLayer): the view's solid background keeps only a tint of its
 * opacity, with glass under it and a lit rim and sheen on its edge.
 */

package com.android.internal.graphics.picked;

import android.annotation.NonNull;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewRootImpl;

import com.android.internal.graphics.drawable.BackgroundBlurDrawable;

/**
 * A view's Glass Layer decoration (see {@link GlassLayer#attach}).
 *
 * @hide
 */
public final class GlassSurface implements View.PickedDrawDecoration {

    /** Live glass: a SurfaceFlinger blur region (Liquid Glass) under the view's shape. */
    public static final int LIVE = 1;
    /** Draw the rim over the view's content instead of under its tinted background. */
    public static final int RIM_ON_TOP = 1 << 1;
    /** No rim or sheen (flat glass, such as a panel that its rows are glass on). */
    public static final int NO_RIM = 1 << 2;
    /**
     * One row of a group of cards (Settings): an edge with small corners is a seam to the next
     * row, with a faint rim and no sheen (Glass Layer's GlassRim.drawSegment).
     */
    public static final int SEGMENTED = 1 << 3;

    private static final int DRAWN = 1;

    private String mSurface;
    private float mTint;
    private int mFlags;

    private GradientDrawable mTinted;
    private int mStockAlpha = -1;
    private int mAppliedAlpha = -1;
    private BackgroundBlurDrawable mBlur;
    private ViewRootImpl mBlurRoot;

    private final Rect mBounds = new Rect();
    private final float[] mRadii = new float[8];

    GlassSurface(String surface, float tint, int flags) {
        update(surface, tint, flags);
    }

    void update(String surface, float tint, int flags) {
        mSurface = surface;
        mTint = tint;
        mFlags = flags;
        mShaderTop = Integer.MIN_VALUE;
    }

    @Override
    public int onPreDraw(@NonNull View view, @NonNull Canvas canvas) {
        GradientDrawable shape =
                GlassLayer.isActive(mSurface) ? SolidShapes.of(view.getBackground()) : null;
        if (shape == null) {
            release(view);
            return 0;
        }
        if (shape != mTinted) {
            restore();
            mTinted = shape;
            mStockAlpha = shape.getAlpha();
            mAppliedAlpha = -1;
        }
        int current = shape.getAlpha();
        if (current != mAppliedAlpha) mStockAlpha = current;
        float tint = Math.min(1f, mTint * GlassLayer.tintScale());
        int alpha = Math.max(0, Math.min(255, Math.round(mStockAlpha * tint)));
        mAppliedAlpha = alpha;
        if (current != alpha) shape.setAlpha(alpha);
        if (mStockAlpha <= 0 || !resolveGeometry(view, shape)) {
            hideBlur();
            return 0;
        }
        float density = view.getResources().getDisplayMetrics().density;
        if ((mFlags & LIVE) != 0 && canvas.isHardwareAccelerated()) {
            drawBlur(view, canvas, density);
        } else {
            hideBlur();
        }
        if ((mFlags & (RIM_ON_TOP | NO_RIM)) == 0) drawRim(canvas, density, mStockAlpha / 255f);
        return DRAWN;
    }

    @Override
    public void onPostDraw(@NonNull View view, @NonNull Canvas canvas, int token) {
        if (token != DRAWN || (mFlags & RIM_ON_TOP) == 0 || (mFlags & NO_RIM) != 0) return;
        drawRim(canvas, view.getResources().getDisplayMetrics().density, mStockAlpha / 255f);
    }

    /** Gives the background its own opacity back and takes the blur region away. */
    void release(View view) {
        restore();
        hideBlur();
    }

    private void restore() {
        if (mTinted != null && mStockAlpha >= 0 && mTinted.getAlpha() != mStockAlpha) {
            mTinted.setAlpha(mStockAlpha);
        }
        mTinted = null;
        mStockAlpha = -1;
        mAppliedAlpha = -1;
    }

    private boolean resolveGeometry(View view, GradientDrawable shape) {
        Rect b = shape.getBounds();
        if (b.isEmpty()) b = view.getBackground().getBounds();
        if (b.isEmpty()) return false;
        mBounds.set(b);
        mBounds.offset(view.getScrollX(), view.getScrollY());
        float half = Math.min(mBounds.width(), mBounds.height()) / 2f;
        if (shape.getShape() == GradientDrawable.OVAL) {
            java.util.Arrays.fill(mRadii, half);
            return true;
        }
        float[] radii = null;
        try {
            radii = shape.getCornerRadii();
        } catch (RuntimeException ignored) {
            // Not set: one radius for every corner.
        }
        if (radii != null && radii.length == 8) {
            for (int i = 0; i < 8; i++) mRadii[i] = Math.min(radii[i], half);
        } else {
            java.util.Arrays.fill(mRadii, Math.min(shape.getCornerRadius(), half));
        }
        return true;
    }

    private void drawBlur(View view, Canvas canvas, float density) {
        ViewRootImpl root = view.getViewRootImpl();
        if (root == null) return;
        if (mBlur == null || mBlurRoot != root) {
            hideBlur();
            mBlur = root.createBackgroundBlurDrawable();
            // Src-over: the blur region keeps what the window drew below the view (a glass
            // panel's tint under its glass rows) instead of cutting a hole through it.
            mBlur.setXfermode(null);
            mBlur.setColor(Color.TRANSPARENT);
            mBlurRoot = root;
        }
        int radius = Math.round(GlassLayer.blurDp() * density);
        if (radius <= 0) {
            hideBlur();
            return;
        }
        mBlur.setVisible(true, false);
        mBlur.setAlpha(Math.round(255 * view.getAlpha()));
        mBlur.setBlurRadius(radius);
        mBlur.setCornerRadius(mRadii[0], mRadii[2], mRadii[6], mRadii[4]);
        mBlur.setBounds(mBounds.left, mBounds.top, mBounds.right, mBounds.bottom);
        mBlur.draw(canvas);
    }

    private void hideBlur() {
        if (mBlur != null) mBlur.setVisible(false, false);
    }

    // Glass Layer's GlassRim: a 1 dp edge lit from the top, and a sheen down the top 55 %
    // (at most 32 dp) of the shape.
    private static final float STROKE_DP = 1f;
    private static final float TOP_ALPHA = 0.2f;
    private static final float BOTTOM_ALPHA = 0.06f;
    private static final float SHEEN_ALPHA = 0.14f;
    private static final float SHEEN_SPAN = 0.55f;
    private static final float SHEEN_MAX_DP = 32f;
    private static final float FADE_STOP = 0.5f;
    private static final float SEAM_ALPHA = 0.04f;
    private static final float SEAM_RADIUS_DP = 8f;

    private static final Paint sStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint sSheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Path sPath = new Path();
    private static final RectF sRect = new RectF();
    private static final float[] sInset = new float[8];

    static {
        sStroke.setStyle(Paint.Style.STROKE);
    }

    private Shader mSheenShader;
    private Shader mRimShader;
    private int mShaderTop = Integer.MIN_VALUE;
    private int mShaderBottom;
    private float mShaderAlpha = -1f;
    private float mShaderDensity;
    private float mShaderRadiusTop;
    private float mShaderRadiusBottom;

    private void drawRim(Canvas canvas, float density, float alpha) {
        if (!GlassLayer.rim() || alpha <= 0f) return;
        if (mShaderTop != mBounds.top || mShaderBottom != mBounds.bottom
                || mShaderRadiusTop != mRadii[0] || mShaderRadiusBottom != mRadii[4]
                || mShaderAlpha != alpha || mShaderDensity != density) {
            mShaderTop = mBounds.top;
            mShaderRadiusTop = mRadii[0];
            mShaderRadiusBottom = mRadii[4];
            mShaderBottom = mBounds.bottom;
            mShaderAlpha = alpha;
            mShaderDensity = density;
            float seam = SEAM_RADIUS_DP * density;
            boolean segmented = (mFlags & SEGMENTED) != 0;
            boolean openTop = segmented && Math.max(mRadii[0], mRadii[2]) <= seam;
            boolean openBottom = segmented && Math.max(mRadii[4], mRadii[6]) <= seam;
            float sheenEnd = mBounds.top
                    + Math.min(mBounds.height() * SHEEN_SPAN, SHEEN_MAX_DP * density);
            mSheenShader = openTop ? null : new LinearGradient(0f, mBounds.top, 0f, sheenEnd,
                    white(SHEEN_ALPHA * alpha), Color.TRANSPARENT, Shader.TileMode.CLAMP);
            mRimShader = new LinearGradient(0f, mBounds.top, 0f, mBounds.bottom,
                    new int[] {white((openTop ? SEAM_ALPHA : TOP_ALPHA) * alpha),
                        segmented ? white(SEAM_ALPHA * alpha) : Color.TRANSPARENT,
                        white((openBottom ? SEAM_ALPHA : BOTTOM_ALPHA) * alpha)},
                    new float[] {0f, FADE_STOP, 1f}, Shader.TileMode.CLAMP);
        }
        sRect.set(mBounds);
        sPath.rewind();
        sPath.addRoundRect(sRect, mRadii, Path.Direction.CW);
        if (mSheenShader != null) {
            sSheen.setShader(mSheenShader);
            canvas.drawPath(sPath, sSheen);
        }

        float stroke = STROKE_DP * density;
        sRect.inset(stroke / 2f, stroke / 2f);
        for (int i = 0; i < 8; i++) sInset[i] = Math.max(0f, mRadii[i] - stroke / 2f);
        sPath.rewind();
        sPath.addRoundRect(sRect, sInset, Path.Direction.CW);
        sStroke.setStrokeWidth(stroke);
        sStroke.setShader(mRimShader);
        canvas.drawPath(sPath, sStroke);
    }

    private static int white(float alpha) {
        return Color.argb(Math.round(Math.max(0f, Math.min(1f, alpha)) * 255), 255, 255, 255);
    }
}
