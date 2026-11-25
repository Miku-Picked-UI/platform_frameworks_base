/*
 * SPDX-FileCopyrightText: 2026 The PenguinOS Project, Miku-Picked UI
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.picked.lockicon

import android.content.Context
import android.os.UserHandle
import android.provider.Settings
import com.android.systemui.keyguard.ui.view.DeviceEntryIconView

/**
 * [PenguinOS] Lock icon on top: like ColorOS, the lock screen's padlock sits at the top, under the
 * status bar. Settings.System ls_lock_icon_top (Penguin Laboratory > Experiments); PenguinOS has
 * it on by default, Miku-Picked UI keeps the stock place unless chosen.
 *
 * The device entry icon stays where it is, so the under-display fingerprint sensor still shows
 * its fingerprint hint, but its padlock is hidden there (DeviceEntryIconViewBinder) and drawn at
 * the top instead (TopPadlockSection). The clocks move down to make room.
 */
object LockIconOnTop {
    const val SETTING = "ls_lock_icon_top"

    /** The padlock's circle and its gap under the status bar, as in PenguinOS. */
    const val RADIUS_DP = 18
    const val MARGIN_DP = 4

    @JvmStatic
    fun isOn(context: Context): Boolean =
        Settings.System.getIntForUser(context.contentResolver, SETTING, 0, UserHandle.USER_CURRENT) !=
            0

    /** How far the clocks move down for the padlock, 0 while it is off. */
    @JvmStatic
    fun spacePx(context: Context): Int =
        if (isOn(context)) {
            ((MARGIN_DP + RADIUS_DP * 2) * context.resources.displayMetrics.density).toInt()
        } else {
            0
        }

    /** Whether the device entry icon hides its glyph: everything but the fingerprint hint. */
    @JvmStatic
    fun hidesGlyph(context: Context, type: DeviceEntryIconView.IconType): Boolean =
        type != DeviceEntryIconView.IconType.FINGERPRINT && isOn(context)
}
