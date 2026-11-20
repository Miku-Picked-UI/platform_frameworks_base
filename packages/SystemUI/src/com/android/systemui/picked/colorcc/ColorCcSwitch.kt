/*
 * SPDX-FileCopyrightText: 2026 Miku-Picked UI
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.picked.colorcc

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue

/**
 * [Miku-Picked UI] The split shade's switch between the notifications and Quick Settings
 * (QuickSettingsControllerImpl.pickedSwitchSplitPanels) while ColorCC is the QS panel.
 *
 * ColorCC opens and closes by following one expansion fraction, as it did under the finger on
 * One UI. A switch flips QS expanded at once, so ColorCC saw 0 -> 1 (or 1 -> 0) in one frame and
 * played its canned open/close while the stock switch slid the QS frame around it: two motions
 * on top of each other. During a switch the controller instead hands ColorCC the switch's own
 * progress here, and leaves the QS frame in place, so ColorCC opens or closes along the switch.
 *
 * Main thread only.
 */
object ColorCcSwitch {
    /** Whether ColorCC currently draws the QS panel (set by [ColorCcHost]). */
    @JvmStatic
    var active = false
        internal set

    /** ColorCC's expansion during a switch, 0 to 1; negative when no switch is running. */
    var expansion by mutableFloatStateOf(-1f)
        private set

    @JvmStatic
    fun setSwitchExpansion(value: Float) {
        expansion = value.coerceIn(0f, 1f)
    }

    @JvmStatic
    fun endSwitch() {
        expansion = -1f
    }
}
