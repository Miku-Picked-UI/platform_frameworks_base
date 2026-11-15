/*
 * SPDX-FileCopyrightText: 2026 Miku-Picked UI
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.picked.charging

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.BatteryManager
import android.view.View
import android.view.animation.LinearInterpolator
import com.android.systemui.res.R
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Miku-Picked UI: the charging animation drawn by [ChargingAnimationController]. It fades a dark
 * scrim in, fills up to the battery level in the chosen style while the percentage counts up,
 * shows the live power and the time to full, then fades out (~3.4 s in all).
 */
class ChargingAnimationView(
    context: Context,
    private val style: Int,
    private val level: Int,
    private val wireless: Boolean,
    private val onFinished: () -> Unit,
) : View(context) {

    private val density = resources.displayMetrics.density
    private val accent = context.getColor(android.R.color.system_accent1_300)
    private val accentDeep = context.getColor(android.R.color.system_accent1_600)
    private val fillColor =
        if (level <= 15) Color.rgb(255, 92, 92) else if (level >= 95) Color.rgb(90, 220, 140)
        else accent

    private val scrim = Paint().apply { color = Color.BLACK }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
    private val glow =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            maskFilter = BlurMaskFilter(18 * density, BlurMaskFilter.Blur.NORMAL)
        }
    private val big =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.create("sans-serif", Typeface.NORMAL), 300, false)
            textSize = 72 * density
        }
    private val small =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(220, 255, 255, 255)
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            textSize = 15 * density
        }
    private val path = Path()
    private val rect = RectF()
    private val bubbles = List(14) { floatArrayOf(Random.nextFloat(), Random.nextFloat(),
        0.4f + Random.nextFloat() * 0.6f) }

    /** 0..1 over the whole animation. */
    private var t = 0f
    private var detail = ""
    private var lastDetailTick = -1L
    private val animator =
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = DURATION_MS
            interpolator = LinearInterpolator()
            addUpdateListener {
                t = it.animatedValue as Float
                if ((t * DURATION_MS).toLong() / 500 != lastDetailTick) {
                    lastDetailTick = (t * DURATION_MS).toLong() / 500
                    detail = readDetail()
                }
                invalidate()
            }
            doOnEnd { onFinished() }
        }
    private fun ValueAnimator.doOnEnd(block: () -> Unit) {
        addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) = block()
        })
    }

    fun start() {
        detail = readDetail()
        animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    /** Overall alpha: fade in over the first 12 %, out over the last 15 %. */
    private fun alpha(): Float = when {
        t < 0.12f -> t / 0.12f
        t > 0.85f -> (1f - t) / 0.15f
        else -> 1f
    }

    /** Fill progress 0..1: eases up to the level between 8 % and 55 %. */
    private fun fillProgress(): Float {
        val p = ((t - 0.08f) / 0.47f).coerceIn(0f, 1f)
        return 1f - (1f - p) * (1f - p) * (1f - p)
    }

    override fun onDraw(canvas: Canvas) {
        val a = alpha()
        scrim.alpha = (a * 190).toInt()
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrim)
        val shown = level * fillProgress()
        when (style) {
            ChargingAnimationController.STYLE_RING -> drawRing(canvas, a, shown)
            ChargingAnimationController.STYLE_WAVE -> drawWave(canvas, a, shown)
            else -> drawLiquid(canvas, a, shown)
        }
        val cx = width / 2f
        val textY = if (style == ChargingAnimationController.STYLE_WAVE) height * 0.38f
            else height * 0.5f + big.textSize * 0.35f
        big.alpha = (a * 255).toInt()
        small.alpha = (a * 220).toInt()
        canvas.drawText(String.format(Locale.getDefault(), "%d%%", shown.toInt()), cx, textY, big)
        canvas.drawText(detail, cx, textY + 34 * density, small)
    }

    // --- Liquid: a pill that fills with a wavy liquid and rising bubbles -------------------
    private fun drawLiquid(canvas: Canvas, a: Float, shown: Float) {
        val w = min(width * 0.42f, 190 * density)
        val h = w * 2.1f
        val cx = width / 2f
        val cy = height / 2f
        rect.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
        val r = w / 2
        stroke.strokeWidth = 3 * density
        stroke.color = Color.WHITE
        stroke.alpha = (a * 90).toInt()
        canvas.drawRoundRect(rect, r, r, stroke)

        canvas.save()
        path.reset()
        path.addRoundRect(rect, r, r, Path.Direction.CW)
        canvas.clipPath(path)
        val surface = rect.bottom - h * shown / 100f
        val phase = (t * 4 * PI).toFloat()
        val amp = 7 * density * (1f - 0.6f * fillProgress())
        for (layer in 0..1) {
            path.reset()
            path.moveTo(rect.left, rect.bottom)
            var x = rect.left
            while (x <= rect.right + 4) {
                val k = (x - rect.left) / w
                path.lineTo(x, surface + amp * sin(k * 2 * PI.toFloat() + phase + layer * 2f))
                x += 4
            }
            path.lineTo(rect.right, rect.bottom)
            path.close()
            fill.shader = LinearGradient(0f, surface, 0f, rect.bottom,
                if (layer == 0) accentDeep else fillColor,
                if (layer == 0) accentDeep else accentDeep, Shader.TileMode.CLAMP)
            fill.alpha = (a * if (layer == 0) 150 else 235).toInt()
            canvas.drawPath(path, fill)
        }
        fill.shader = null
        fill.color = Color.WHITE
        for (b in bubbles) {
            val travel = (b[1] + t * b[2] * 2f) % 1f
            val by = rect.bottom - travel * (rect.bottom - surface)
            if (by < surface) continue
            fill.alpha = (a * 110 * (1f - travel)).toInt()
            canvas.drawCircle(rect.left + 12 * density + b[0] * (w - 24 * density), by,
                (2 + 3 * b[2]) * density, fill)
        }
        canvas.restore()
    }

    // --- Ring: a glowing arc sweeps round to the level --------------------------------------
    private fun drawRing(canvas: Canvas, a: Float, shown: Float) {
        val radius = min(width, height) * 0.32f
        val cx = width / 2f
        val cy = height / 2f
        rect.set(cx - radius, cy - radius, cx + radius, cy + radius)
        stroke.strokeWidth = 10 * density
        stroke.color = Color.WHITE
        stroke.alpha = (a * 40).toInt()
        canvas.drawArc(rect, 0f, 360f, false, stroke)
        val sweep = 360f * shown / 100f
        glow.strokeWidth = 16 * density
        glow.color = fillColor
        glow.alpha = (a * 170).toInt()
        canvas.drawArc(rect, -90f, sweep, false, glow)
        stroke.color = fillColor
        stroke.alpha = (a * 255).toInt()
        canvas.drawArc(rect, -90f, sweep, false, stroke)
        // A spark orbiting the ring.
        val ang = (-90f + sweep) * PI.toFloat() / 180f
        fill.shader = null
        fill.color = Color.WHITE
        fill.alpha = (a * 255).toInt()
        canvas.drawCircle(cx + radius * cos(ang), cy + radius * sin(ang), 7 * density, fill)
        for (i in 0 until 10) {
            val pa = ang - i * 0.05f
            fill.alpha = (a * 160 * (1f - i / 10f)).toInt()
            canvas.drawCircle(cx + radius * cos(pa), cy + radius * sin(pa),
                (5f - i * 0.4f) * density, fill)
        }
    }

    // --- Wave: the whole screen fills from the bottom with layered waves --------------------
    private fun drawWave(canvas: Canvas, a: Float, shown: Float) {
        val top = height - height * 0.85f * shown / 100f
        val phase = (t * 3 * PI).toFloat()
        for (layer in 0..2) {
            path.reset()
            path.moveTo(0f, height.toFloat())
            var x = 0f
            val amp = (14 - layer * 3) * density
            val len = width * (0.9f + layer * 0.35f)
            while (x <= width + 6) {
                path.lineTo(x, top + layer * 10 * density +
                    amp * sin(x / len * 2 * PI.toFloat() + phase * (1 + layer * 0.4f) + layer))
                x += 6
            }
            path.lineTo(width.toFloat(), height.toFloat())
            path.close()
            fill.shader = LinearGradient(0f, top, 0f, height.toFloat(), fillColor, accentDeep,
                Shader.TileMode.CLAMP)
            fill.alpha = (a * (90 + layer * 50)).toInt()
            canvas.drawPath(path, fill)
        }
        fill.shader = null
    }

    /** "18.4 W · Full in 1 h 5 min" (or "Charging wirelessly" when nothing is known yet). */
    private fun readDetail(): String {
        val parts = ArrayList<String>(2)
        val watts = ChargingInfo.watts(context)
        if (watts >= 0.5f) parts += String.format(Locale.getDefault(), "%.1f W", watts)
        val bm = context.getSystemService(BatteryManager::class.java)
        val remaining = bm?.computeChargeTimeRemaining() ?: -1L
        if (remaining > 0) {
            val min = (remaining / 60_000L).toInt()
            parts += context.getString(R.string.picked_charging_anim_full_in,
                if (min >= 60) context.getString(R.string.picked_charging_anim_hm, min / 60, min % 60)
                else context.getString(R.string.picked_charging_anim_m, min.coerceAtLeast(1)))
        }
        if (parts.isEmpty()) {
            return context.getString(if (wireless) R.string.picked_charging_anim_wireless
                else R.string.picked_charging_anim_wired)
        }
        return parts.joinToString(" · ")
    }

    private companion object {
        const val DURATION_MS = 3400L
    }
}
