/*
 * SPDX-FileCopyrightText: 2026 Miku-Picked UI
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.picked.charging

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.graphics.PointF
import android.os.BatteryManager
import android.os.Handler
import android.os.PowerManager
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.WindowManager
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.picked.water.WaterRipple
import com.android.systemui.statusbar.policy.KeyguardStateController
import javax.inject.Inject

/**
 * Miku-Picked UI: full-screen charging animation on the lockscreen when a charger is plugged in
 * (wired or wireless), with the battery level, live power and the time to full.
 *
 * Settings.System picked_charging_animation: 0 = off (AOSP behaviour), 1 = liquid, 2 = ring,
 * 3 = wave, 4 = water (Glass Layer's water ripple, picked/water). While it is on, AOSP's
 * wireless charging ripple is skipped (CentralSurfacesImpl).
 */
@SysUISingleton
class ChargingAnimationController
@Inject
constructor(
    private val context: Context,
    @Main private val handler: Handler,
    private val windowManager: WindowManager,
    private val keyguardStateController: KeyguardStateController,
) : CoreStartable {

    private var view: ChargingAnimationView? = null
    private var lastShown = 0L

    private val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.action != Intent.ACTION_POWER_CONNECTED) return
                // Plugging in usually wakes the screen; give the lockscreen a moment to show.
                handler.postDelayed({ maybeShow() }, SHOW_DELAY_MS)
            }
        }

    override fun start() {
        context.registerReceiverForAllUsers(
            receiver, IntentFilter(Intent.ACTION_POWER_CONNECTED), null, handler)
    }

    private fun maybeShow() {
        val style = style(context)
        if (style == STYLE_OFF || view != null) return
        val now = System.currentTimeMillis()
        if (now - lastShown < DEBOUNCE_MS) return // flaky cable
        val power = context.getSystemService(PowerManager::class.java)
        if (power?.isInteractive != true || !keyguardStateController.isShowing) return
        val battery =
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return
        val level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        if (level < 0) return
        val plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        lastShown = now
        val wireless = plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS
        if (style == STYLE_WATER) {
            val center = WaterRipple.screenCenter(context)
            // The USB-C port is at the bottom middle; Glass Layer plays it 1.5x as long.
            val origin = if (wireless) center else PointF(center.x, center.y * 2f)
            WaterRipple.play(context, origin, 1.5f)
            return
        }
        show(style, level * 100 / scale, wireless)
    }

    private fun show(style: Int, level: Int, wireless: Boolean) {
        val v = ChargingAnimationView(context, style, level, wireless) { remove() }
        val lp =
            WindowManager.LayoutParams().apply {
                width = WindowManager.LayoutParams.MATCH_PARENT
                height = WindowManager.LayoutParams.MATCH_PARENT
                type = WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG
                format = PixelFormat.TRANSLUCENT
                flags =
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                fitInsetsTypes = 0
                title = "PickedChargingAnimation"
                packageName = context.opPackageName
            }
        try {
            windowManager.addView(v, lp)
            view = v
            v.start()
        } catch (e: RuntimeException) {
            Log.w(TAG, "Cannot show the charging animation", e)
        }
    }

    private fun remove() {
        val v = view ?: return
        view = null
        if (v.isAttachedToWindow) windowManager.removeViewImmediate(v)
        v.visibility = View.GONE
    }

    companion object {
        private const val TAG = "PickedChargingAnim"
        const val SETTING = "picked_charging_animation"
        const val STYLE_OFF = 0
        const val STYLE_LIQUID = 1
        const val STYLE_RING = 2
        const val STYLE_WAVE = 3
        /** Glass Layer's water ripple from the charging port (or the middle, wireless). */
        const val STYLE_WATER = 4
        private const val SHOW_DELAY_MS = 350L
        private const val DEBOUNCE_MS = 5000L

        @JvmStatic
        fun style(context: Context): Int =
            Settings.System.getIntForUser(
                context.contentResolver, SETTING, STYLE_LIQUID, UserHandle.USER_CURRENT)

        /** Whether this replaces AOSP's wireless charging ripple. */
        @JvmStatic fun isEnabled(context: Context): Boolean = style(context) != STYLE_OFF
    }
}
