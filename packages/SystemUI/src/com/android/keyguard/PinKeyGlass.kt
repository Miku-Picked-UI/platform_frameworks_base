/*
 * SPDX-FileCopyrightText: 2026 Miku-Picked UI
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.keyguard

import android.graphics.Canvas
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.MotionEvent
import android.view.View
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import kotlin.math.abs
import kotlin.math.hypot
import com.android.internal.graphics.drawable.BackgroundBlurDrawable
import com.android.systemui.qs.shared.style.LiquidGlass

/**
 * Miku-Picked UI: Liquid Glass for the PIN pad keys (NumPadKey / NumPadButton), the classic
 * view based counterpart of PenguinOS' glass PIN keys ("SystemUI: Liquid Glass PIN keys with
 * the Quick Settings press", by redducc, for the Compose bouncer).
 *
 * While system-wide Liquid Glass is on, a key draws a blur region the size of its round
 * background (SurfaceFlinger's renderengine turns it into a glass slab), its fill becomes a light
 * glass tint (see NumPadAnimator), PenguinOS' lit rim goes on top, and the key responds to the
 * finger like PenguinOS' glass press: it lifts (grows a little) on a spring, leans toward where
 * the finger drags with rubber-band resistance, stretches along the drag and narrows across it,
 * and wobbles back into place on a soft spring when let go.
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

    // PenguinOS' glass press, for views: lift 0..1 and the lean offset in px.
    private val lift = FloatValueHolder(0f)
    private val leanX = FloatValueHolder(0f)
    private val leanY = FloatValueHolder(0f)
    private val liftSpring = spring(lift)
    private val leanXSpring = spring(leanX)
    private val leanYSpring = spring(leanY)
    private var downX = 0f
    private var downY = 0f
    private var pressing = false

    private fun spring(holder: FloatValueHolder) =
        SpringAnimation(holder).apply {
            spring = SpringForce(0f)
            setMinimumVisibleChange(SpringAnimation.MIN_VISIBLE_CHANGE_SCALE)
            addUpdateListener { _, _, _ -> applyPress() }
        }

    /** Follows the finger: call from the key's onTouchEvent with every event. */
    fun onTouch(event: MotionEvent) {
        if (!enabled) {
            if (pressing) release()
            return
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressing = true
                downX = event.rawX
                downY = event.rawY
                animate(liftSpring, 1f, PRESS_DAMPING, PRESS_STIFFNESS)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!pressing) return
                val dx = event.rawX - downX
                val dy = event.rawY - downY
                val reach = REACH_DP * view.resources.displayMetrics.density
                val dist = hypot(dx, dy)
                // Rubber band: follows the finger at first, then less and less, up to the reach.
                val pull = if (dist == 0f) 0f else reach * (1f - 1f / (1f + dist / (reach * 3f))) / dist
                leanXSpring.cancel()
                leanYSpring.cancel()
                leanX.value = dx * pull
                leanY.value = dy * pull
                applyPress()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> release()
        }
    }

    private fun release() {
        pressing = false
        animate(liftSpring, 0f, RELEASE_DAMPING, RELEASE_STIFFNESS)
        animate(leanXSpring, 0f, RELEASE_DAMPING, RELEASE_STIFFNESS)
        animate(leanYSpring, 0f, RELEASE_DAMPING, RELEASE_STIFFNESS)
    }

    private fun animate(anim: SpringAnimation, target: Float, damping: Float, stiffness: Float) {
        anim.spring.setDampingRatio(damping).setStiffness(stiffness)
        anim.animateToFinalPosition(target)
    }

    private fun applyPress() {
        val reach = REACH_DP * view.resources.displayMetrics.density
        val l = lift.value
        val ox = leanX.value
        val oy = leanY.value
        val stretchX = abs(ox) / reach * STRETCH
        val stretchY = abs(oy) / reach * STRETCH
        view.scaleX = 1f + LIFT_SCALE * l + stretchX - stretchY * 0.5f
        view.scaleY = 1f + LIFT_SCALE * l + stretchY - stretchX * 0.5f
        view.translationX = ox
        view.translationY = oy
    }

    fun onDetached() {
        blur?.setVisible(false, false)
        blur = null
        liftSpring.cancel()
        leanXSpring.cancel()
        leanYSpring.cancel()
        lift.value = 0f
        leanX.value = 0f
        leanY.value = 0f
        pressing = false
        applyPress()
    }

    private fun cornerOf(background: Drawable, height: Int): Float =
        ((background as? GradientDrawable)?.cornerRadius ?: (height / 2f))
            .coerceAtMost(height / 2f)

    companion object {
        private const val BLUR_RADIUS = 90

        // PenguinOS' glass press (LiquidGlass.kt): 6 % lift, 10 dp lean reach, 3.5 % stretch,
        // PressSpring (0.62, 520) and releaseSpring (0.38, StiffnessMediumLow).
        private const val LIFT_SCALE = 0.06f
        private const val REACH_DP = 10f
        private const val STRETCH = 0.035f
        private const val PRESS_DAMPING = 0.62f
        private const val PRESS_STIFFNESS = 520f
        private const val RELEASE_DAMPING = 0.38f
        private const val RELEASE_STIFFNESS = 400f // Compose Spring.StiffnessMediumLow
    }
}
