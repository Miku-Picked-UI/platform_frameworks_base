/*
 * SPDX-FileCopyrightText: 2026 Miku-Picked UI
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.picked.visualizer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.android.systemui.picked.audio.SharedVisualizer
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Miku-Picked UI: lockscreen music visualizer (#11).
 *
 * Reads the output mix through [SharedVisualizer], which SystemUI shares with the OneUisland
 * island. A second Visualizer of our own could not be enabled while the island's was running, so
 * the bars stayed flat. Draws [BARS] rounded bars from the FFT magnitudes. The tap is only open
 * while [setActive] is true (toggle on, media playing, screen on, not dozing).
 */
class LockscreenVisualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val magnitudes = FloatArray(BARS)
    private val drawn = FloatArray(BARS)
    private val raw = FloatArray(BARS) // tap thread only
    @Volatile private var active = false
    private var color = 0xFFFFFFFF.toInt()

    // Frames arrive AS_PLAYED (they follow the volume), so normalise against a slowly decaying peak.
    private var peak = MIN_PEAK

    private val sink = SharedVisualizer.Sink { fft, _ -> processFft(fft) }

    fun setColor(c: Int) {
        color = c
        paint.color = c
        invalidate()
    }

    /** Open or close the shared audio tap. Safe to call repeatedly. */
    fun setActive(shouldBeActive: Boolean) {
        if (shouldBeActive == active) return
        active = shouldBeActive
        if (active) {
            SharedVisualizer.open(sink)
        } else {
            SharedVisualizer.close(sink)
            synchronized(magnitudes) { magnitudes.fill(0f) }
            peak = MIN_PEAK
            postInvalidate()
        }
    }

    private fun processFft(fft: ByteArray) {
        if (!active) return
        // fft layout: [DC_re, Nyquist_re, re1, im1, re2, im2, ...]. The music sits in the lower
        // bins, so map bars onto the lower half on a square-root scale. Low bars get a few bins,
        // high bars get more.
        val bins = (fft.size / 2) - 1
        if (bins < BARS) return
        val usable = bins / 2
        var framePeak = 0f
        for (i in 0 until BARS) {
            val lo = 1 + ((i.toFloat() / BARS).let { it * it } * usable).toInt()
            val hi = max(lo + 1, 1 + (((i + 1f) / BARS).let { it * it } * usable).toInt())
            var sum = 0f
            for (bin in lo until min(hi, bins + 1)) {
                val k = bin * 2
                if (k + 1 >= fft.size) break
                sum += hypot(fft[k].toFloat(), fft[k + 1].toFloat())
            }
            raw[i] = sum / (hi - lo)
            framePeak = max(framePeak, raw[i])
        }
        peak = max(MIN_PEAK, max(framePeak, peak * PEAK_DECAY))
        synchronized(magnitudes) {
            for (i in 0 until BARS) {
                val target = min(1f, raw[i] / peak)
                // Rise fast, fall gently.
                val k = if (target > magnitudes[i]) RISE else SMOOTHING
                magnitudes[i] = magnitudes[i] * k + target * (1f - k)
            }
        }
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val gap = w / BARS * 0.35f
        val barWidth = w / BARS - gap
        val radius = barWidth / 2f
        var x = gap / 2f
        synchronized(magnitudes) { magnitudes.copyInto(drawn) }
        for (i in 0 until BARS) {
            val barHeight = (drawn[i] * h).coerceAtLeast(barWidth)
            val top = h - barHeight
            canvas.drawRoundRect(x, top, x + barWidth, h, radius, radius, paint)
            x += barWidth + gap
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        setActive(false)
    }

    companion object {
        private const val BARS = 32
        private const val SMOOTHING = 0.72f
        private const val RISE = 0.25f
        private const val PEAK_DECAY = 0.985f
        private const val MIN_PEAK = 4f
    }
}
