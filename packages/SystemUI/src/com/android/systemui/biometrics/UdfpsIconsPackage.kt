/*
 * SPDX-FileCopyrightText: 2026 Miku-Picked UI
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.biometrics

import android.content.Context
import android.content.pm.PackageManager

/**
 * The fingerprint icon resource package (vendor/miku/udfps_icons, from Evolution X), read by
 * [UdfpsIconDrawable]. Replaces Evolution X's com.android.internal.util.evolution.Utils
 * .isPackageInstalled for these checks.
 */
object UdfpsIconsPackage {
    const val NAME = "org.evolution.udfps.icons"

    /** Whether the icon package is installed and enabled. */
    @JvmStatic
    fun isInstalled(context: Context): Boolean =
        try {
            context.packageManager.getApplicationInfo(NAME, 0).enabled
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
}
