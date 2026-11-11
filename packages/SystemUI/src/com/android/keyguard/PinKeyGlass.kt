/*
 * SPDX-FileCopyrightText: 2026 Miku-Picked UI
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.keyguard

import android.graphics.Canvas
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import com.android.internal.graphics.drawable.BackgroundBlurDrawable
import com.android.systemui.qs.shared.style.LiquidGlass

/**
 * Miku-Picked UI: Liquid Glass for the PIN pad keys (NumPadKey / NumPadButton), the classic
 * view based counterpart of PenguinOS' glass PIN keys ("SystemUI: Liquid Glass PIN keys with
 * the Quick Settings press", by redducc, for the Compose bouncer).
 *
 * While system-wide Liquid Glass is on, a key draws a blur region the size of its round
 * background (SurfaceFlinger's renderengine turns it into a glass slab), its fill becomes a light
 * glass tint (see NumPadAnimator), PenguinOS' lit rim goes on top, and pressing lifts the key
 * with a spring instead of only morphing it.
 */
class PinKeyGlass(private val view: View) {

    private var blur: BackgroundBlurDrawable? = null
    private val rect = RectF()
    private val radii = FloatArray(8)

    val enabled: Boolean
        get() = LiquidGlass.isEnabled(view.context)

    /** Draws the blur region behind the key's [background]; call before the view draws. */
    fun drawBehind(canvas: Canvas, background: Drawable?) {
        if (!enabled || background == null) {
            blur?.setVisible(false, false)
            return
        }
        val b = background.bounds
        if (b.isEmpty) return
        val drawable =
            blur
                ?: view.viewRootImpl?.createBackgroundBlurDrawable()?.also {
                    // Draw over what the bouncer drew below the key instead of clearing it.
                    it.setXfermode(null)
                    it.setColor(0)
                    blur = it
                }
                ?: return
        drawable.setVisible(true, false)
        drawable.setBlurRadius(BLUR_RADIUS)
        drawable.setCornerRadius(cornerOf(background, b.height()))
        drawable.alpha = 255
        drawable.setBounds(b.left, b.top, b.right, b.bottom)
        drawable.draw(canvas)
    }

    /** Draws the glass rim over the key's [background]; call after the view drew. */
    fun drawRim(canvas: Canvas, background: Drawable?) {
        // The ColorOS style is frosted glass without the iOS-like lit rim.
        if (!enabled || background == null || LiquidGlass.cosStyle(view.context).value) return
        val b = background.bounds
        if (b.isEmpty) return
        rect.set(b)
        radii.fill(cornerOf(background, b.height()))
        LiquidGlass.drawRim(canvas, rect, radii, view.resources.displayMetrics.density)
    }

    /** The press lift: up and a little larger on down, springing back on release. */
    fun onPress(down: Boolean) {
        if (!enabled) return
        view.animate().cancel()
        view.animate()
            .scaleX(if (down) PRESS_SCALE else 1f)
            .scaleY(if (down) PRESS_SCALE else 1f)
            .setDuration(if (down) 120 else 420)
            .setInterpolator(if (down) PRESS_IN else OvershootInterpolator(2.6f))
            .start()
    }

    fun onDetached() {
        blur?.setVisible(false, false)
        blur = null
    }

    private fun cornerOf(background: Drawable, height: Int): Float =
        ((background as? GradientDrawable)?.cornerRadius ?: (height / 2f))
            .coerceAtMost(height / 2f)

    companion object {
        private const val BLUR_RADIUS = 90
        private const val PRESS_SCALE = 1.06f
        private val PRESS_IN = PathInterpolator(0.2f, 0f, 0f, 1f)
    }
}
