/*
 * SPDX-FileCopyrightText: 2026 Miku-Picked UI
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.weather

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Miku-Picked UI: weather condition icon drawn in code (sun, moon, clouds, rain, snow, storm,
 * fog) for a WMO weather code, in one colour so it follows the clock's text colour.
 */
class WeatherIconDrawable(private val code: Int, private val isDay: Boolean, color: Int) :
    Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val path = Path()

    private enum class Kind { CLEAR, PARTLY, CLOUDY, FOG, DRIZZLE, RAIN, SNOW, STORM }

    private val kind = when (code) {
        0 -> Kind.CLEAR
        1, 2 -> Kind.PARTLY
        3 -> Kind.CLOUDY
        45, 48 -> Kind.FOG
        51, 53, 55, 56, 57 -> Kind.DRIZZLE
        61, 63, 65, 66, 67, 80, 81, 82 -> Kind.RAIN
        71, 73, 75, 77, 85, 86 -> Kind.SNOW
        95, 96, 99 -> Kind.STORM
        else -> Kind.CLOUDY
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val s = min(b.width(), b.height()).toFloat()
        canvas.save()
        canvas.translate(b.left + (b.width() - s) / 2f, b.top + (b.height() - s) / 2f)
        stroke.strokeWidth = s * 0.07f
        when (kind) {
            Kind.CLEAR -> if (isDay) sun(canvas, s * 0.5f, s * 0.5f, s * 0.2f) else
                moon(canvas, s * 0.5f, s * 0.5f, s * 0.32f)
            Kind.PARTLY -> {
                if (isDay) sun(canvas, s * 0.36f, s * 0.36f, s * 0.14f) else
                    moon(canvas, s * 0.36f, s * 0.36f, s * 0.22f)
                cloud(canvas, s, s * 0.08f)
            }
            Kind.CLOUDY -> cloud(canvas, s, 0f)
            Kind.FOG -> for (i in 0..2) {
                val y = s * (0.35f + i * 0.17f)
                canvas.drawLine(s * (0.15f + i * 0.05f), y, s * (0.85f - i * 0.05f), y, stroke)
            }
            Kind.DRIZZLE, Kind.RAIN -> {
                cloud(canvas, s, -s * 0.12f)
                val n = if (kind == Kind.RAIN) 3 else 2
                for (i in 0 until n) {
                    val x = s * (0.32f + i * 0.36f / (n - 1).coerceAtLeast(1))
                    canvas.drawLine(x, s * 0.7f, x - s * 0.06f, s * 0.88f, stroke)
                }
            }
            Kind.SNOW -> {
                cloud(canvas, s, -s * 0.12f)
                for (i in 0..2) canvas.drawCircle(s * (0.3f + i * 0.2f),
                    s * (0.78f + (i % 2) * 0.08f), s * 0.045f, paint)
            }
            Kind.STORM -> {
                cloud(canvas, s, -s * 0.12f)
                path.reset()
                path.moveTo(s * 0.55f, s * 0.6f)
                path.lineTo(s * 0.4f, s * 0.8f)
                path.lineTo(s * 0.52f, s * 0.8f)
                path.lineTo(s * 0.44f, s * 0.97f)
                path.lineTo(s * 0.66f, s * 0.74f)
                path.lineTo(s * 0.54f, s * 0.74f)
                path.close()
                canvas.drawPath(path, paint)
            }
        }
        canvas.restore()
    }

    private fun sun(c: Canvas, cx: Float, cy: Float, r: Float) {
        c.drawCircle(cx, cy, r, paint)
        for (i in 0 until 8) {
            val a = i * Math.PI.toFloat() / 4f
            c.drawLine(cx + cos(a) * r * 1.45f, cy + sin(a) * r * 1.45f,
                cx + cos(a) * r * 1.9f, cy + sin(a) * r * 1.9f, stroke)
        }
    }

    private fun moon(c: Canvas, cx: Float, cy: Float, r: Float) {
        path.reset()
        path.addCircle(cx, cy, r, Path.Direction.CW)
        val cut = Path().apply { addCircle(cx + r * 0.45f, cy - r * 0.3f, r * 0.8f,
            Path.Direction.CW) }
        path.op(cut, Path.Op.DIFFERENCE)
        c.drawPath(path, paint)
    }

    /** A cloud across the lower part of the icon, moved down by [dy]. */
    private fun cloud(c: Canvas, s: Float, dy: Float) {
        path.reset()
        path.addCircle(s * 0.38f, s * 0.58f + dy, s * 0.17f, Path.Direction.CW)
        path.addCircle(s * 0.58f, s * 0.5f + dy, s * 0.22f, Path.Direction.CW)
        path.addRoundRect(s * 0.14f, s * 0.58f + dy, s * 0.88f, s * 0.78f + dy, s * 0.1f,
            s * 0.1f, Path.Direction.CW)
        c.drawPath(path, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        stroke.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        stroke.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
