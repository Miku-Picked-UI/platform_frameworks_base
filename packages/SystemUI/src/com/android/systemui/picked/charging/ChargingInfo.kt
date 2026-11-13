/*
 * SPDX-FileCopyrightText: 2026 Miku-Picked UI
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.picked.charging

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.UserHandle
import android.provider.Settings
import com.android.systemui.res.R
import java.util.Locale
import kotlin.math.abs

/**
 * Miku-Picked UI: live charging details for the lockscreen charging indication, after
 * Evolution X / AxionOS' "lockscreen battery info": current, power, voltage and battery
 * temperature, e.g. "1.2A · 5.9W · 4.36V · 33.1°C".
 *
 * Read straight from BatteryManager (current) and the sticky battery broadcast (voltage,
 * temperature), so nothing in SettingsLib' BatteryStatus has to change.
 */
object ChargingInfo {

    /** Settings.System: show the line (on by default). */
    const val SETTING = "picked_lockscreen_charging_info"

    /** How often the line refreshes while plugged in and the lockscreen is on. */
    const val REFRESH_MS = 2000L

    fun isEnabled(context: Context): Boolean =
        Settings.System.getIntForUser(context.contentResolver, SETTING, 1, UserHandle.USER_CURRENT) !=
            0

    /**
     * The details, or an empty string when there is nothing sensible to show. The current
     * from the health HAL is µA on most devices, but some (Samsung) pass on the kernel's mA;
     * config_currentInfoDivider says how many raw units make one mA (1000 or 1).
     */
    fun line(context: Context): String {
        if (!isEnabled(context)) return ""
        val battery =
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return ""
        val divider =
            context.resources.getInteger(R.integer.config_currentInfoDivider).coerceAtLeast(1)

        val bm = context.getSystemService(BatteryManager::class.java)
        val rawCurrent = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        val currentMa =
            if (rawCurrent == null || rawCurrent == Int.MIN_VALUE) 0f
            else abs(rawCurrent.toFloat()) / divider
        val voltageV = battery.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) / 1000f
        val temperatureC = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f

        val details = ArrayList<String>(4)
        // Above 20 A it is a wrong divider, not a reading.
        if (currentMa > 0f && currentMa < 20_000f) {
            details +=
                if (currentMa >= 1000f) String.format(Locale.US, "%.1fA", currentMa / 1000f)
                else String.format(Locale.US, "%.0fmA", currentMa)
            if (voltageV > 0f) {
                details += String.format(Locale.US, "%.1fW", currentMa / 1000f * voltageV)
            }
        }
        if (voltageV > 0f) details += String.format(Locale.US, "%.2fV", voltageV)
        if (temperatureC > 0f) details += String.format(Locale.US, "%.1f°C", temperatureC)
        return if (details.isEmpty()) "" else details.joinToString(" · ")
    }
}
