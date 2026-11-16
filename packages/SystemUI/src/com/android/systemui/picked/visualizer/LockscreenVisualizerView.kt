/*
 * SPDX-FileCopyrightText: 2026 Miku-Picked UI
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.picked.visualizer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.media.audiofx.Visualizer
import android.util.AttributeSet
import android.util.Log
import android.view.View
import kotlin.math.hypot
import kotlin.math.min

/**
 * Miku-Picked UI: lockscreen music visualizer (#11).
 *
 * Captures the global output mix with [android.media.audiofx.Visualizer] (needs RECORD_AUDIO +
 * MODIFY_AUDIO_SETTINGS, both already held by SystemUI) and draws [BARS] rounded bars that react
 * to the FFT magnitudes. The Visualizer is a shared, power-hungry audio tap, so it is only linked
 * while [setActive] is true (toggle on, media playing, screen on, not dozing) and fully released
 * otherwise.
 */
class LockscreenVisualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val magnitudes = FloatArray(BARS)
    private var visualizer: Visualizer? = null
    private var active = false
    private var color = 0xFFFFFFFF.toInt()

    fun setColor(c: Int) {
        color = c
        paint.color = c
        invalidate()
    }

    /** Link or release the audio tap. Safe to call repeatedly. */
    fun setActive(shouldBeActive: Boolean) {
        if (shouldBeActive == active) return
        active = shouldBeActive
        if (active) link() else unlink()
    }

    private fun link() {
        if (visualizer != null) return
        try {
            visualizer = Visualizer(0).apply {
                enabled = false
                captureSize = min(Visualizer.getCaptureSizeRange()[1], 1024)
                setDataCaptureListener(
                    object : Visualizer.OnDataCaptureListener {
                        override fun onWaveFormDataCapture(v: Visualizer?, wf: ByteArray?, sr: Int) {}
                        override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, sr: Int) {
                            if (fft != null) processFft(fft)
                        }
                    },
                    Visualizer.getMaxCaptureRate() / 2,
                    false, /* waveform */
                    true, /* fft */
                )
                enabled = true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cannot start the output visualizer", e)
            visualizer = null
        }
    }

    private fun unlink() {
        visualizer?.let {
            try {
                it.enabled = false
                it.release()
            } catch (e: Exception) {
                Log.w(TAG, "Cannot release the visualizer", e)
            }
        }
        visualizer = null
        magnitudes.fill(0f)
        postInvalidate()
    }

    private fun processFft(fft: ByteArray) {
        // fft layout: [DC_re, Nyquist_re, re1, im1, re2, im2, ...]. Fold the usable bins into BARS
        // bands and smooth toward the new magnitude so the bars fall gently.
        val bins = (fft.size / 2) - 1
        if (bins <= 0) return
        val perBar = bins / BARS
        if (perBar <= 0) return
        for (i in 0 until BARS) {
            var sum = 0f
            for (j in 0 until perBar) {
                val k = 2 + (i * perBar + j) * 2
                if (k + 1 >= fft.size) break
                val re = fft[k].toFloat()
                val im = fft[k + 1].toFloat()
                sum += hypot(re, im)
            }
            // Normalize to 0..1 (magnitudes are roughly 0..128) and bias the low end up a touch.
            val target = min(1f, (sum / perBar) / 90f)
            magnitudes[i] = magnitudes[i] * SMOOTHING + target * (1f - SMOOTHING)
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
        for (i in 0 until BARS) {
            val barHeight = (magnitudes[i] * h).coerceAtLeast(barWidth)
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
        private const val TAG = "PickedVisualizer"
        private const val BARS = 32
        private const val SMOOTHING = 0.72f
    }
}
