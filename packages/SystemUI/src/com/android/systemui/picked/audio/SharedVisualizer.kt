/*
 * SPDX-FileCopyrightText: 2026 Miku-Picked UI
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.picked.audio

import android.media.audiofx.Visualizer
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.max
import kotlin.math.min

/**
 * Miku-Picked UI: one output-mix audio tap shared by everything in SystemUI.
 *
 * Only one [Visualizer] client can enable the shared output-mix engine. A second one, created
 * while the first is running, gets an error from setEnabled() and never receives data. So the
 * lockscreen visualizer and the OneUisland island had to share one tap: whichever started second
 * stayed flat. Consumers [open] a sink here instead. The visualizer exists only while at least one
 * sink is open. It is released [RELEASE_DELAY_MS] after the last one closes, because attaching or
 * detaching an effect on the music output causes a short audible dropout on Exynos.
 *
 * FFT frames use [Visualizer.SCALING_MODE_AS_PLAYED] at up to 30 Hz and arrive on a background
 * thread.
 */
object SharedVisualizer {
    private const val TAG = "PickedSharedVisualizer"
    private const val RELEASE_DELAY_MS = 5_000L
    private const val RETRY_MS = 30_000L

    fun interface Sink {
        fun onFft(fft: ByteArray, samplingRateMilliHz: Int)
    }

    private val sinks = CopyOnWriteArrayList<Sink>()
    private val handler: Handler by lazy {
        Handler(HandlerThread("PickedAudioTap").apply { start() }.looper)
    }
    private var visualizer: Visualizer? = null
    private var failedAt = 0L

    private val releaseRunnable = Runnable { if (sinks.isEmpty()) release() }

    /** Starts delivering frames to [sink]. Returns [sink] as the handle for [close]. */
    fun open(sink: Sink): Sink {
        sinks.addIfAbsent(sink)
        handler.post {
            handler.removeCallbacks(releaseRunnable)
            if (visualizer == null) create()
        }
        return sink
    }

    fun close(sink: Sink) {
        sinks.remove(sink)
        handler.post {
            if (sinks.isEmpty()) {
                handler.removeCallbacks(releaseRunnable)
                handler.postDelayed(releaseRunnable, RELEASE_DELAY_MS)
            }
        }
    }

    private fun create() {
        if (sinks.isEmpty()) return
        val now = android.os.SystemClock.uptimeMillis()
        if (failedAt != 0L && now - failedAt < RETRY_MS) return
        try {
            val v = Visualizer(0)
            v.setEnabled(false)
            val range = Visualizer.getCaptureSizeRange()
            v.setCaptureSize(max(range[0], min(1024, range[1])))
            v.setScalingMode(Visualizer.SCALING_MODE_AS_PLAYED)
            val rate = min(Visualizer.getMaxCaptureRate(), 30_000)
            v.setDataCaptureListener(
                object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(vis: Visualizer?, wf: ByteArray?, sr: Int) {}

                    override fun onFftDataCapture(vis: Visualizer?, fft: ByteArray?, sr: Int) {
                        if (fft == null) return
                        for (s in sinks) {
                            try {
                                s.onFft(fft, sr)
                            } catch (t: Throwable) {
                                Log.w(TAG, "Sink failed", t)
                            }
                        }
                    }
                },
                rate,
                false,
                true,
            )
            val status = v.setEnabled(true)
            if (status != Visualizer.SUCCESS) {
                // Another client (outside SystemUI) holds the engine. Data would never arrive, so
                // free it and retry later instead of pretending to run.
                v.release()
                failedAt = now
                Log.w(TAG, "Output visualizer is held by another client ($status)")
                return
            }
            visualizer = v
            failedAt = 0L
            Log.d(TAG, "Output visualizer started, rate=$rate")
        } catch (t: Throwable) {
            failedAt = now
            visualizer = null
            Log.w(TAG, "Cannot start the output visualizer", t)
        }
    }

    private fun release() {
        val v = visualizer ?: return
        visualizer = null
        try {
            v.setEnabled(false)
            v.release()
        } catch (t: Throwable) {
            Log.w(TAG, "Cannot release the visualizer", t)
        }
        Log.d(TAG, "Output visualizer released")
    }
}
