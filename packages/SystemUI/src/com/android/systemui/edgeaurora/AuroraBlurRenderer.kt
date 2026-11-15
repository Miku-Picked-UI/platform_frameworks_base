/*
 * SPDX-FileCopyrightText: 2026 Miku-Picked UI
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.edgeaurora

import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.CrossWindowBlurListeners
import android.view.View
import com.android.internal.graphics.drawable.BackgroundBlurDrawable

/**
 * Miku-Picked UI: the blurred popup background of Edge Aurora. Lunaris draws it with AxionOS'
 * AxBlurBackgroundRenderer (ax_blur), which this tree doesn't have; this keeps its API on top
 * of the platform's BackgroundBlurDrawable (a blur region SurfaceFlinger draws behind the
 * view), like the other blurred surfaces here.
 */
internal class AuroraBlurRenderer(private val view: View) {

    private var enabled = false
    private var attached = false
    private var visible = true
    private var blur: BackgroundBlurDrawable? = null

    fun setEnabled(enabled: Boolean) {
        if (this.enabled == enabled) return
        this.enabled = enabled
        if (!enabled) release()
        view.invalidate()
    }

    /**
     * Draws the blur behind [background]'s bounds tinted with [overlayColor]; false when blur
     * isn't available (off, not attached, or cross-window blur disabled by the system), so the
     * caller draws its solid colour instead.
     */
    fun drawBackgroundWithOverlayColor(
        canvas: Canvas,
        background: Drawable,
        overlayColor: Int,
    ): Boolean {
        if (!enabled || !attached || !visible) return false
        if (!CrossWindowBlurListeners.getInstance().isCrossWindowBlurEnabled) return false
        val drawable =
            blur ?: view.viewRootImpl?.createBackgroundBlurDrawable()?.also { blur = it }
                ?: return false
        drawable.setBlurRadius(BLUR_RADIUS)
        drawable.setColor(overlayColor)
        drawable.setCornerRadius((background as? GradientDrawable)?.cornerRadius ?: 0f)
        drawable.bounds = background.bounds
        drawable.setVisible(true, false)
        drawable.draw(canvas)
        return true
    }

    fun onAttachedToWindow() {
        attached = true
    }

    fun onDetachedFromWindow() {
        attached = false
        release()
    }

    fun onVisibilityAggregated(isVisible: Boolean) {
        visible = isVisible
        blur?.setVisible(isVisible, false)
    }

    fun verifyDrawable(who: Drawable) = who === blur

    private fun release() {
        blur?.setVisible(false, false)
        blur = null
    }

    companion object {
        private const val BLUR_RADIUS = 60

        /** Alphas of the tints over the blur (Lunaris takes them from ax_blur's colours). */
        const val SURFACE_TINT_ALPHA = 0xB3
        const val SURFACE_HIGH_TINT_ALPHA = 0x80
    }
}
