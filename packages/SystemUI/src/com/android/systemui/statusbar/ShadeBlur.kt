/*
 * SPDX-FileCopyrightText: 2026 Miku-Picked UI
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.statusbar

import android.app.ActivityThread
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings

/**
 * Miku-Picked UI: the user's shade blur strength (Settings.System picked_shade_blur, 0-100 %,
 * 100 by default) and the blur radius the shade has right now.
 *
 * BlurUtils scales every window blur it applies with it. Liquid Glass regions over the shade
 * (Quick Settings tiles, notification cards, PIN keys) use the shade's current radius: blur
 * regions of the radius SurfaceFlinger already blurred the layer with reuse that pass, while any
 * other radius costs another full blur of the screen, which made the shade stutter.
 */
object ShadeBlur {
    const val SETTING = "picked_shade_blur"

    @Volatile private var scale = 1f
    @Volatile private var registered = false

    /** The blur radius last applied to a SystemUI window, in px (0 when none). */
    @Volatile
    var radius = 0
        private set

    /** The user's strength, 0..1. */
    fun scale(): Float {
        ensureRegistered()
        return scale
    }

    /** [radius] scaled by the user's strength. */
    fun scaled(radius: Int): Int {
        val s = scale()
        return if (s >= 1f) radius else (radius * s).toInt()
    }

    internal fun onApplied(appliedRadius: Int) {
        radius = appliedRadius
    }

    /**
     * The radius for a glass blur region: the shade's while it blurs, otherwise [fallback]
     * scaled by the user's strength (0 means no glass blur at all).
     */
    @JvmStatic
    fun glassRegionRadius(fallback: Int): Int =
        if (radius > 0) radius else (fallback * scale()).toInt()

    private fun ensureRegistered() {
        if (registered) return
        val app = ActivityThread.currentApplication() ?: return
        synchronized(this) {
            if (registered) return
            registered = true
            val resolver = app.contentResolver
            val read = {
                scale =
                    Settings.System.getIntForUser(resolver, SETTING, 100, UserHandle.USER_CURRENT)
                        .coerceIn(0, 100) / 100f
            }
            read()
            resolver.registerContentObserver(
                Settings.System.getUriFor(SETTING),
                false,
                object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) = read()
                },
                UserHandle.USER_ALL,
            )
        }
    }
}
