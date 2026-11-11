/*
 * Copyright (C) 2026 The PenguinOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.composefragment

import android.content.Context
import android.graphics.Canvas
import android.graphics.RuntimeShader
import android.graphics.drawable.Drawable
import android.util.Log
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.penguin.systemui.light.CardLight
import com.penguin.systemui.light.EdgeSweep
import com.penguin.systemui.light.GlassRim
import com.penguin.systemui.light.SeekLight
import kotlin.math.PI
import kotlin.math.pow

object CosHooks {
    private const val TAG = "CosHooks"

    private inline fun <T> build(what: String, create: () -> T): T? =
        runCatching(create).onFailure { Log.e(TAG, "Could not create the $what", it) }.getOrNull()

    @Suppress("UNUSED_PARAMETER")
    fun createMediaLight(context: Context): Drawable? = build("media light") { CardLight() }

    fun setMediaLightCorner(light: Drawable, radius: Float) =
        (light as CardLight).setCornerRadius(radius)

    fun applyMediaLight(light: Drawable, color: Int, night: Boolean) =
        (light as CardLight).setColor(color, night)

    fun applyMediaLightSpread(light: Drawable, x: Float, y: Float) =
        (light as CardLight).playRipple(x, y)

    fun clearMediaLight(light: Drawable) = (light as CardLight).clear()

    class StrokeGlow internal constructor(private val sweep: EdgeSweep) {
        fun setStartAngle(angle: Float) = sweep.setStartAngle(angle)

        fun setProgress(progress: Float) = sweep.setProgress(progress)

        fun draw(canvas: Canvas, width: Int, height: Int, cornerRadius: Float, capsule: Boolean) =
            sweep.draw(canvas, width, height, cornerRadius, capsule)
    }

    fun newStrokeGlow(): StrokeGlow? = build("edge sweep") { StrokeGlow(EdgeSweep()) }

    class ConicLight internal constructor(private val light: SeekLight) {
        fun setColor(color: Int) = light.setColor(color)

        fun setShape(
            height: Float,
            heightFalloff: Float,
            coneK: Float,
            secondConeK: Float,
            w0: Float,
            alpha1: Float,
            alpha2: Float,
        ) = light.setShape(SeekLight.Shape(height, heightFalloff, coneK, secondConeK, w0, alpha1, alpha2))

        fun draw(
            canvas: Canvas,
            progressStart: Float,
            centerY: Float,
            length: Float,
            fraction: Float,
            rtl: Boolean,
        ) = light.draw(canvas, progressStart, centerY, length, fraction, rtl)
    }

    fun newConicLight(): ConicLight? = build("seek light") { ConicLight(SeekLight()) }

    fun createRim(): RuntimeShader? = build("glass rim") { GlassRim.create() }

    fun updateRim(
        shader: RuntimeShader,
        width: Float,
        height: Float,
        radius: Float,
        active: Boolean,
        dark: Boolean,
    ) = GlassRim.update(shader, width, height, radius, active, dark)

    class Spring internal constructor(start: Float, onUpdate: (Float) -> Unit) {
        private val holder = FloatValueHolder(start)
        private val animation =
            SpringAnimation(holder).apply {
                spring = SpringForce().setDampingRatio(1f)
                setMinimumVisibleChange(0.001f)
                addUpdateListener { _, value, _ -> onUpdate(value) }
            }
        private val update = onUpdate

        fun animateTo(target: Float, response: Float, bounce: Float = 0f) {
            animation.spring
                .setStiffness((2 * PI / response).pow(2).toFloat())
                .setDampingRatio(1f - bounce)
            if (!animation.isRunning) animation.setStartValue(holder.value)
            animation.animateToFinalPosition(target)
        }

        fun snapTo(value: Float) {
            animation.cancel()
            holder.value = value
            update(value)
        }
    }

    fun newSpring(start: Float, onUpdate: (Float) -> Unit): Spring = Spring(start, onUpdate)
}
