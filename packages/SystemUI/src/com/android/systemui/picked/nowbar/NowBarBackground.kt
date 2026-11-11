/*
 * SPDX-FileCopyrightText: 2026 Miku-Picked UI
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.picked.nowbar

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.util.Log
import android.view.View
import android.view.WindowManager
import com.android.internal.graphics.drawable.BackgroundBlurDrawable
import java.util.function.Consumer

/**
 * Miku-Picked UI: draws the Now Bar's background in one of three styles.
 *
 *  - [STYLE_LIQUID]: a lightly tinted, strongly see-through live blur of what is behind the
 *    lockscreen (SurfaceFlinger background blur through [BackgroundBlurDrawable]), with a
 *    glass rim on top: a rounded-rect distance field gives each pixel's distance to the edge
 *    and its outward normal, and a light from the top left puts a bright specular band on the
 *    facing bezel, a weaker one on the opposite bezel and a thin bright edge line.
 *  - [STYLE_FROSTED]: a heavier blur with a darker tint, a soft top sheen and a hairline rim.
 *  - [STYLE_BLACK]: solid black.
 *
 * When window blurs are off (developer option, battery saver, or a device without
 * SurfaceFlinger blur) the glass styles fall back to a darker translucent fill so text stays
 * readable.
 */
class NowBarBackground(private val view: View) {

    private var currentStyle = STYLE_LIQUID
    private var blur: BackgroundBlurDrawable? = null
    private var blurEnabled = true
    private var windowManager: WindowManager? = null
    private val blurListener = Consumer<Boolean> { enabled ->
        blurEnabled = enabled
        view.invalidate()
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rect = RectF()
    private var sheenHeight = -1f

    private var glassShader: RuntimeShader? = null
    private val glassPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var glassBroken = false

    fun setStyle(newStyle: Int) {
        val s = if (newStyle in STYLE_LIQUID..STYLE_BLACK) newStyle else STYLE_LIQUID
        if (s == currentStyle) return
        currentStyle = s
        view.invalidate()
    }

    fun onAttached() {
        val density = view.resources.displayMetrics.density
        blur =
            try {
                view.viewRootImpl?.createBackgroundBlurDrawable()
            } catch (t: Throwable) {
                Log.e(TAG, "background blur unavailable", t)
                null
            }
        windowManager = view.context.getSystemService(WindowManager::class.java)
        try {
            windowManager?.let {
                blurEnabled = it.isCrossWindowBlurEnabled
                it.addCrossWindowBlurEnabledListener(view.context.mainExecutor, blurListener)
            }
        } catch (t: Throwable) {
            blurEnabled = false
        }
        rimPaint.strokeWidth = density
    }

    fun onDetached() {
        try {
            windowManager?.removeCrossWindowBlurEnabledListener(blurListener)
        } catch (_: Throwable) {}
        windowManager = null
        blur = null
    }

    /**
     * Draws the background for a view of [width] x [height]. [alpha] is the view's effective
     * alpha on screen (its own times its parents'); the blur region is composited by
     * SurfaceFlinger and does not follow the view's alpha by itself.
     */
    fun draw(canvas: Canvas, width: Int, height: Int, cornerRadius: Float, alpha: Float) {
        if (width <= 0 || height <= 0 || alpha <= 0f) return
        val radius = minOf(cornerRadius, width / 2f, height / 2f)
        rect.set(0f, 0f, width.toFloat(), height.toFloat())

        if (currentStyle == STYLE_BLACK) {
            fillPaint.shader = null
            fillPaint.color = Color.BLACK
            canvas.drawRoundRect(rect, radius, radius, fillPaint)
            return
        }

        val liquid = currentStyle == STYLE_LIQUID
        val b = blur
        val blurring = b != null && blurEnabled
        if (blurring) {
            b!!.setBlurRadius(if (liquid) LIQUID_BLUR else FROSTED_BLUR)
            b.setCornerRadius(radius)
            b.setColor(Color.TRANSPARENT)
            b.alpha = (alpha.coerceIn(0f, 1f) * 255).toInt()
            b.setBounds(0, 0, width, height)
            b.draw(canvas)
        }

        // Tint over the blur (or the opaque fallback without blur).
        fillPaint.shader = null
        fillPaint.color =
            when {
                !blurring -> NO_BLUR_TINT
                liquid -> LIQUID_TINT
                else -> FROSTED_TINT
            }
        canvas.drawRoundRect(rect, radius, radius, fillPaint)

        if (liquid && drawLiquidRim(canvas, width, height, radius)) return

        // Frosted (and liquid without AGSL): a soft sheen from the top and a hairline rim.
        if (sheenHeight != height.toFloat()) {
            sheenHeight = height.toFloat()
            sheenPaint.shader =
                LinearGradient(
                    0f, 0f, 0f, sheenHeight,
                    Color.argb(0x24, 0xFF, 0xFF, 0xFF), Color.argb(0x04, 0xFF, 0xFF, 0xFF),
                    Shader.TileMode.CLAMP,
                )
        }
        canvas.drawRoundRect(rect, radius, radius, sheenPaint)
        rimPaint.color = Color.argb(0x38, 0xFF, 0xFF, 0xFF)
        val inset = rimPaint.strokeWidth / 2f
        rect.inset(inset, inset)
        canvas.drawRoundRect(rect, radius - inset, radius - inset, rimPaint)
    }

    /** Returns false when the shader can't be used, so the caller draws the frosted rim. */
    private fun drawLiquidRim(canvas: Canvas, width: Int, height: Int, radius: Float): Boolean {
        if (glassBroken || !canvas.isHardwareAccelerated) return false
        val shader =
            glassShader
                ?: try {
                    RuntimeShader(RIM_SHADER).also {
                        glassShader = it
                        glassPaint.shader = it
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "liquid glass shader unavailable", t)
                    glassBroken = true
                    return false
                }
        val density = view.resources.displayMetrics.density
        try {
            shader.setFloatUniform("uSize", width.toFloat(), height.toFloat())
            shader.setFloatUniform("uRadius", radius)
            shader.setFloatUniform("uBezel", minOf(radius, BEZEL_DP * density))
            shader.setFloatUniform("uDp", density)
            shader.setFloatUniform("uLight", LIGHT_X, LIGHT_Y)
        } catch (t: Throwable) {
            Log.e(TAG, "liquid glass disabled", t)
            glassBroken = true
            return false
        }
        canvas.drawRoundRect(rect, radius, radius, glassPaint)
        return true
    }

    companion object {
        private const val TAG = "NowBarBackground"

        const val STYLE_LIQUID = 0
        const val STYLE_FROSTED = 1
        const val STYLE_BLACK = 2

        private const val LIQUID_BLUR = 28
        private const val FROSTED_BLUR = 80
        private val LIQUID_TINT = Color.argb(0x40, 0x10, 0x10, 0x14)
        private val FROSTED_TINT = Color.argb(0x73, 0x16, 0x16, 0x1A)
        private val NO_BLUR_TINT = Color.argb(0xC7, 0x16, 0x16, 0x1A)

        private const val BEZEL_DP = 14f

        // Direction the light travels in (from the top left, pointing down and right).
        private const val LIGHT_X = 0.4472f
        private const val LIGHT_Y = 0.8944f

        /**
         * Glass rim for a rounded rect of size uSize at the origin. Output is premultiplied
         * white (highlights) drawn src-over the tinted blur.
         */
        private const val RIM_SHADER =
            """
            uniform float2 uSize;
            uniform float uRadius;
            uniform float uBezel;
            uniform float uDp;
            uniform float2 uLight;

            float roundRect(float2 p, float2 halfSize, float r) {
                float2 q = abs(p) - halfSize + r;
                return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
            }

            half4 main(float2 coord) {
                float2 halfSize = uSize * 0.5;
                float2 p = coord - halfSize;
                float d = roundRect(p, halfSize, uRadius);
                float coverage = clamp(0.5 - d, 0.0, 1.0);
                if (coverage <= 0.0) return half4(0.0);

                // Outward normal from the distance field's gradient.
                float e = 0.5;
                float2 g = float2(
                    roundRect(p + float2(e, 0.0), halfSize, uRadius) -
                        roundRect(p - float2(e, 0.0), halfSize, uRadius),
                    roundRect(p + float2(0.0, e), halfSize, uRadius) -
                        roundRect(p - float2(0.0, e), halfSize, uRadius));
                float2 n = g / max(length(g), 0.0001);

                // 1 at the edge, falling to 0 across the bezel (a convex lip).
                float t = clamp(-d / uBezel, 0.0, 1.0);
                float lip = pow(1.0 - t, 2.2);

                float facing = dot(n, -uLight);
                float spec = pow(max(facing, 0.0), 2.5) * lip * 0.55;
                float back = pow(max(-facing, 0.0), 3.0) * lip * 0.22;
                float edge = (1.0 - smoothstep(0.0, 1.2 * uDp, -d)) * 0.42;
                float sheen = (1.0 - coord.y / uSize.y) * 0.05;

                float a = clamp(spec + back + edge + sheen, 0.0, 1.0) * coverage;
                return half4(a, a, a, a);
            }
            """
    }
}
