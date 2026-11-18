/*
 * Copyright (C) 2026 The PenguinOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.penguin

import androidx.compose.foundation.OverscrollFactory
import androidx.compose.runtime.Composable
import com.android.compose.animation.scene.ObservableTransitionState
import com.android.compose.animation.scene.SceneTransitionsBuilder
import com.android.compose.animation.scene.TransitionBuilder
import com.android.compose.animation.scene.TransitionKey

val CosShadeSwitch = TransitionKey("CosShadeSwitch")

// Miku-Picked UI: Android 16's ObservableTransitionState carries no transition key, and this
// tree has no ColorOS shade switch.
@Suppress("UnusedReceiverParameter")
fun ObservableTransitionState.isCosShadeSwitch(): Boolean = false

/** Part of a tablet panel switch over which the panel being left fades out. */
const val PANEL_SWITCH_FADE_OUT_END = 0.5f

/** Part of a tablet panel switch after which the panel switched to fades in. */
const val PANEL_SWITCH_FADE_IN_START = 0.3f

/** Alpha of a tablet panel at [progress] of a switch it is [leaving] or switching to. */
fun panelSwitchAlpha(leaving: Boolean, progress: Float): Float =
    if (leaving) {
        1f - (progress / PANEL_SWITCH_FADE_OUT_END).coerceIn(0f, 1f)
    } else {
        ((progress - PANEL_SWITCH_FADE_IN_START) / (1f - PANEL_SWITCH_FADE_IN_START))
            .coerceIn(0f, 1f)
    }

interface ShadeMotion {
    @Composable fun key(): Any?

    /**
     * Whether the shade overlays reveal and dismiss with AOSP's own transitions rather than this
     * motion. Read after [key] has been composed.
     */
    fun useStockMotion(): Boolean = false

    fun TransitionBuilder.shadeToQuickSettings(animateQsTilesAsShared: () -> Boolean)

    fun TransitionBuilder.quickSettingsToShade(animateQsTilesAsShared: () -> Boolean)

    fun TransitionBuilder.toQuickSettingsShade(durationScale: Double)

    fun TransitionBuilder.fromQuickSettingsShade()

    fun TransitionBuilder.toNotificationsShade(durationScale: Double, enableSharedElements: Boolean)

    fun SceneTransitionsBuilder.overlaySwitchTransitions()

    @Composable fun quickSettingsShadeOverscroll(): OverscrollFactory?
}
