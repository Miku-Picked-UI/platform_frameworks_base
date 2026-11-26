/*
 * Copyright (C) 2026 The PenguinOS Project
 * SPDX-FileCopyrightText: 2026 Miku-Picked UI
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.statusbar.phone

import android.content.Context
import android.content.Intent
import android.os.UserHandle
import android.provider.Settings
import android.view.Display
import android.view.WindowManagerGlobal

/**
 * [PenguinOS] A DeX screen's status bar opens PenguinDex's Control Centre instead of the shade.
 * Miku-Picked UI: only while Penguin Laboratory's DeX Control Centre switch is on (PenguinOS
 * shows that switch but never reads it); otherwise the shade opens as on any screen.
 */
object DexStatusBar {
    private const val PACKAGE = "com.penguin.dex"
    private const val SERVICE = "com.penguin.dex.DexService"
    private const val ACTION = "com.penguin.dex.action.CONTROL_CENTRE"
    private const val CONTROL_CENTRE = "penguin_dex_control_centre"

    fun isDex(context: Context, displayId: Int): Boolean =
        displayId != Display.DEFAULT_DISPLAY &&
            Settings.Secure.getInt(context.contentResolver, CONTROL_CENTRE, 1) != 0 &&
            runCatching {
                    WindowManagerGlobal.getWindowManagerService()?.isEligibleForDesktopMode(displayId)
                }
                .getOrNull() == true

    fun toggleControlCentre(context: Context, displayId: Int) {
        runCatching {
            context.startServiceAsUser(
                Intent(ACTION).setClassName(PACKAGE, SERVICE).putExtra("display", displayId),
                UserHandle.SYSTEM,
            )
        }
    }
}
