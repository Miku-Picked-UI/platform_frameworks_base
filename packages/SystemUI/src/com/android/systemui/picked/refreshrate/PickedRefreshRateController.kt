/*
 * SPDX-FileCopyrightText: 2026 Miku-Picked UI
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.picked.refreshrate

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.shared.system.ActivityManagerWrapper
import com.android.systemui.shared.system.TaskStackChangeListener
import com.android.systemui.shared.system.TaskStackChangeListeners
import javax.inject.Inject

/**
 * [Miku-Picked UI] Per-app refresh rate, for battery. The user chooses a cap (e.g. 60 Hz) for
 * chosen apps, and optionally a global cap for everything else. While such an app is in the
 * foreground, peak_refresh_rate is clamped to the cap; the user's own setting is restored when a
 * capped app leaves the foreground.
 *
 * - picked_app_refresh_rate (Settings.System): "pkg=rate;pkg=rate", rate in Hz.
 * - picked_default_refresh_rate (Settings.System): a cap for apps not individually listed; 0 = off.
 */
@SysUISingleton
class PickedRefreshRateController
@Inject
constructor(
    private val context: Context,
    @Main private val handler: Handler,
) : CoreStartable {

    private val resolver = context.contentResolver

    private var perApp: Map<String, Int> = emptyMap()
    private var globalCap: Int = 0

    // Whether we are currently overriding the user's peak, and the value to put back.
    private var overriding = false
    private var basePeak: String? = null

    private val observer =
        object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                loadConfig()
                apply(topPackage())
            }
        }

    private val taskListener =
        object : TaskStackChangeListener {
            override fun onTaskStackChanged() {
                handler.post { apply(topPackage()) }
            }
        }

    override fun start() {
        for (key in arrayOf(KEY_PER_APP, KEY_GLOBAL)) {
            resolver.registerContentObserver(
                Settings.System.getUriFor(key), false, observer, UserHandle.USER_ALL)
        }
        loadConfig()
        TaskStackChangeListeners.getInstance().registerTaskStackListener(taskListener)
        apply(topPackage())
    }

    private fun loadConfig() {
        val list =
            Settings.System.getStringForUser(resolver, KEY_PER_APP, UserHandle.USER_CURRENT)
        val map = HashMap<String, Int>()
        list?.split(";")?.forEach { entry ->
            val parts = entry.split("=")
            if (parts.size == 2) {
                val rate = parts[1].trim().toIntOrNull()
                if (rate != null && rate > 0) map[parts[0].trim()] = rate
            }
        }
        perApp = map
        globalCap =
            Settings.System.getIntForUser(resolver, KEY_GLOBAL, 0, UserHandle.USER_CURRENT)
    }

    private fun topPackage(): String? =
        try {
            ActivityManagerWrapper.getInstance().runningTask?.topActivity?.packageName
        } catch (e: Exception) {
            null
        }

    /** The cap for [pkg]: its own if listed, else the global cap (0 = none). */
    private fun capFor(pkg: String?): Int {
        if (pkg == null) return 0
        perApp[pkg]?.let {
            return it
        }
        return globalCap
    }

    private fun apply(pkg: String?) {
        val cap = capFor(pkg)
        if (cap > 0) {
            if (!overriding) {
                basePeak =
                    Settings.System.getStringForUser(
                        resolver, Settings.System.PEAK_REFRESH_RATE, UserHandle.USER_CURRENT)
                overriding = true
            }
            setPeak(cap.toFloat().toString())
        } else if (overriding) {
            setPeak(basePeak) // restore the user's own value (null clears it)
            overriding = false
            basePeak = null
        }
    }

    private fun setPeak(value: String?) {
        try {
            Settings.System.putStringForUser(
                resolver, Settings.System.PEAK_REFRESH_RATE, value, UserHandle.USER_CURRENT)
        } catch (e: Exception) {
            Log.w(TAG, "Can't set peak refresh rate", e)
        }
    }

    companion object {
        private const val TAG = "PickedRefreshRate"
        const val KEY_PER_APP = "picked_app_refresh_rate"
        const val KEY_GLOBAL = "picked_default_refresh_rate"
    }
}
