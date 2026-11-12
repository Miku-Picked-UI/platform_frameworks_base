/*
 * SPDX-FileCopyrightText: 2026 Miku-Picked UI
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.composefragment

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue

/**
 * Miku-Picked UI: Quick Settings' fade while the split shade switches to or from it
 * (QuickSettingsControllerImpl). Applied inside Compose, so the tiles' Liquid Glass (a
 * SurfaceFlinger blur region the view's own alpha can't reach) fades along.
 */
object PickedPanelSwitch {
    var qsAlpha by mutableFloatStateOf(1f)
        private set

    @JvmStatic
    fun setQsFade(alpha: Float) {
        qsAlpha = alpha.coerceIn(0f, 1f)
    }
}
