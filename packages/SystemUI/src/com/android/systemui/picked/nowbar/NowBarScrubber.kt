/*
 * SPDX-FileCopyrightText: 2026 Miku-Picked UI
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.picked.nowbar

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.PathInterpolator

/**
 * Miku-Picked UI: the slider of the Now Bar's iOS player, like the scrubber and volume bar on
 * iOS' lock screen: a thin rounded track that thickens while it is held, and moves by how far
 * the finger slides (it doesn't jump to where it touched), so it can be grabbed anywhere.
 */
class NowBarScrubber(context: Context) : View(context) {

    interface Listener {
        fun onStart() {}

        fun onChange(value: Float) {}

        fun onStop(value: Float) {}
    }

    var listener: Listener? = null

    var max = 1f
        set(v) {
            field = v.coerceAtLeast(0.0001f)
            invalidate()
        }

    var value = 0f
        set(v) {
            val c = v.coerceIn(0f, max)
            if (field != c) {
                field = c
                invalidate()
            }
        }

    /** Whether it can be dragged; when not, it only shows the value. */
    var seekable = true

    var dragging = false
        private set

    private var grow = 0f
    private var growAnim: ValueAnimator? = null
    private var downX = 0f
    private var downValue = 0f
    private val rect = RectF()
    private val trackPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(0x40, 0xFF, 0xFF, 0xFF) }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    override fun onDraw(canvas: Canvas) {
        val h = dp(THIN_DP) + (dp(THICK_DP) - dp(THIN_DP)) * grow
        // It also spreads a little to the sides while held, as on iOS.
        val inset = dp(2f) * (1f - grow)
        val cy = height / 2f
        val r = h / 2
        rect.set(inset, cy - h / 2, width - inset, cy + h / 2)
        canvas.drawRoundRect(rect, r, r, trackPaint)
        val frac = (value / max).coerceIn(0f, 1f)
        if (frac > 0f) {
            rect.right = rect.left + (rect.width() * frac).coerceAtLeast(h)
            fillPaint.alpha = (0xCC + (0xFF - 0xCC) * grow).toInt()
            canvas.drawRoundRect(rect, r, r, fillPaint)
        }
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!seekable || !isEnabled) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Ours from the first touch: neither the Now Bar's page swipe nor the lock
                // screen's swipes take it over.
                parent?.requestDisallowInterceptTouchEvent(true)
                dragging = true
                downX = ev.x
                downValue = value
                animateGrow(1f)
                performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                listener?.onStart()
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return false
                val w = (width - dp(4f)).coerceAtLeast(1f)
                value = downValue + (ev.x - downX) / w * max
                listener?.onChange(value)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!dragging) return false
                dragging = false
                animateGrow(0f)
                listener?.onStop(value)
            }
        }
        return true
    }

    private fun animateGrow(target: Float) {
        growAnim?.cancel()
        growAnim =
            ValueAnimator.ofFloat(grow, target).apply {
                duration = if (target > grow) 160 else 300
                interpolator = EASE
                addUpdateListener {
                    grow = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
    }

    private fun dp(v: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    private companion object {
        const val THIN_DP = 6f
        const val THICK_DP = 12f
        val EASE = PathInterpolator(0.2f, 0f, 0f, 1f)
    }
}
