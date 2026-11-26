/*
 * SPDX-FileCopyrightText: 2026 Miku-Picked UI
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.picked.unlock

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.UserHandle
import android.provider.Settings
import com.android.internal.graphics.ColorUtils
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/**
 * Miku-Picked UI: alternative unlock animations drawn by AuthRippleView instead of the stock
 * ripple, from the fingerprint sensor (or face) location when the phone unlocks.
 *
 * Settings.System picked_unlock_animation: 0 = stock ripple, 1 = burst, 2 = rings,
 * 3 = shockwave, 4 = none, 5 = water (Glass Layer's water ripple over the whole screen, see
 * picked/water/WaterRipple).
 */
class PickedUnlockEffect(context: Context) {

    private val density = context.resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glow =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            maskFilter = BlurMaskFilter(14 * density, BlurMaskFilter.Blur.NORMAL)
        }

    private var particles = FloatArray(0) // angle, speed, size per particle
    private var cx = 0f
    private var cy = 0f
    private var maxRadius = 0f
    var style = STYLE_STOCK
        private set
    var color = Color.WHITE

    /** Prepares a new run from ([x], [y]); returns false when the stock ripple should play. */
    fun begin(context: Context, x: Float, y: Float, width: Int, height: Int): Boolean {
        style = style(context)
        cx = x
        cy = y
        maxRadius = max(max(x, width - x), max(y, height - y))
        val n = 48
        particles = FloatArray(n * 3)
        for (i in 0 until n) {
            particles[i * 3] = (i + Random.nextFloat() * 0.8f) / n * (2 * Math.PI).toFloat()
            particles[i * 3 + 1] = 0.45f + Random.nextFloat() * 0.55f
            particles[i * 3 + 2] = (2f + Random.nextFloat() * 4f) * density
        }
        return style != STYLE_STOCK
    }

    fun draw(canvas: Canvas, progress: Float) {
        when (style) {
            STYLE_BURST -> drawBurst(canvas, progress)
            STYLE_RINGS -> drawRings(canvas, progress)
            STYLE_SHOCKWAVE -> drawShockwave(canvas, progress)
        }
    }

    private fun easeOut(p: Float) = 1f - (1f - p) * (1f - p) * (1f - p)

    /** Sparks shoot out of the sensor and fade with a short trail. */
    private fun drawBurst(canvas: Canvas, p: Float) {
        val e = easeOut(p)
        val reach = maxRadius * 0.55f
        for (i in 0 until particles.size / 3) {
            val ang = particles[i * 3]
            val d = reach * e * particles[i * 3 + 1]
            val size = particles[i * 3 + 2] * (1f - p * 0.7f)
            val alpha = ((1f - p) * 255).toInt().coerceIn(0, 255)
            for (tail in 0..3) {
                val td = d - tail * 10 * density * (1f - p)
                if (td < 0) continue
                fill.color = ColorUtils.setAlphaComponent(
                    if (i % 3 == 0) Color.WHITE else color, alpha * (4 - tail) / 4)
                canvas.drawCircle(cx + td * cos(ang), cy + td * sin(ang), size * (1f - tail * 0.2f),
                    fill)
            }
        }
        // Core flash.
        fill.color = ColorUtils.setAlphaComponent(Color.WHITE, ((1f - p * 3f).coerceAtLeast(0f) *
            200).toInt())
        canvas.drawCircle(cx, cy, 28 * density * (1f + e), fill)
    }

    /** Three glowing rings ripple out one after another. */
    private fun drawRings(canvas: Canvas, p: Float) {
        for (k in 0 until 3) {
            val q = ((p - k * 0.14f) / 0.72f).coerceIn(0f, 1f)
            if (q <= 0f || q >= 1f) continue
            val r = maxRadius * 0.7f * easeOut(q)
            val a = ((1f - q) * 230).toInt()
            glow.strokeWidth = 10 * density
            glow.color = ColorUtils.setAlphaComponent(color, a / 2)
            canvas.drawCircle(cx, cy, r, glow)
            ring.strokeWidth = (4 - k) * density
            ring.color = ColorUtils.setAlphaComponent(if (k == 0) Color.WHITE else color, a)
            canvas.drawCircle(cx, cy, r, ring)
        }
    }

    /** A bright flash and a thick distortion-like wave to the screen edges. */
    private fun drawShockwave(canvas: Canvas, p: Float) {
        val e = easeOut(p)
        val r = maxRadius * 1.05f * e
        val flash = (1f - p * 2.5f).coerceAtLeast(0f)
        if (flash > 0f) {
            fill.shader = RadialGradient(cx, cy, 160 * density,
                ColorUtils.setAlphaComponent(Color.WHITE, (flash * 220).toInt()), Color.TRANSPARENT,
                Shader.TileMode.CLAMP)
            canvas.drawCircle(cx, cy, 160 * density, fill)
            fill.shader = null
        }
        val thick = 46 * density * (1f - p * 0.6f)
        if (r > thick / 2) {
            ring.shader = RadialGradient(cx, cy, r + thick / 2,
                intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT,
                    ColorUtils.setAlphaComponent(color, ((1f - p) * 170).toInt()),
                    ColorUtils.setAlphaComponent(Color.WHITE, ((1f - p) * 200).toInt()),
                    Color.TRANSPARENT),
                floatArrayOf(0f, ((r - thick / 2) / (r + thick / 2)).coerceIn(0f, 1f),
                    ((r - thick / 6) / (r + thick / 2)).coerceIn(0f, 1f),
                    (r / (r + thick / 2)).coerceIn(0f, 1f), 1f),
                Shader.TileMode.CLAMP)
            fill.shader = ring.shader
            canvas.drawCircle(cx, cy, r + thick / 2, fill)
            fill.shader = null
            ring.shader = null
        }
        // Sparkles riding the wave.
        for (i in 0 until particles.size / 3 step 2) {
            val ang = particles[i * 3]
            val d = r * (0.92f + 0.08f * particles[i * 3 + 1])
            fill.color = ColorUtils.setAlphaComponent(Color.WHITE, ((1f - p) * 255).toInt())
            canvas.drawCircle(cx + d * cos(ang), cy + d * sin(ang), particles[i * 3 + 2] * 0.6f,
                fill)
        }
    }

    companion object {
        const val SETTING = "picked_unlock_animation"
        const val STYLE_STOCK = 0
        const val STYLE_BURST = 1
        const val STYLE_RINGS = 2
        const val STYLE_SHOCKWAVE = 3
        const val STYLE_NONE = 4
        const val STYLE_WATER = 5

        fun style(context: Context): Int =
            Settings.System.getIntForUser(
                context.contentResolver, SETTING, STYLE_STOCK, UserHandle.USER_CURRENT)
    }
}
