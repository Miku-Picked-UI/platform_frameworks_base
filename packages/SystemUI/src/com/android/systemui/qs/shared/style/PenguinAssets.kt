/*
 * SPDX-FileCopyrightText: 2026 Miku-Picked UI
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.shared.style

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.util.Log

/**
 * Miku-Picked UI: optional assets that are not part of Miku-Picked UI, looked up at runtime in
 * the PenguinAssetsExtra package when a build includes it (a private repo synced with
 * manifesto/local_manifests/penguin-assets-extra.xml): PenguinOS' Liquid Glass tile glyphs and
 * its ColorOS animations. Everything here returns null without that package, and callers then
 * keep SystemUI's own icons and animations.
 */
object PenguinAssets {
    const val PACKAGE = "com.mikupicked.penguinassets"
    private const val TAG = "PenguinAssets"

    @Volatile private var checked = false
    @Volatile private var assetsContext: Context? = null

    /** The assets package's context, or null when it is not installed. */
    fun context(context: Context): Context? {
        if (!checked) {
            synchronized(this) {
                if (!checked) {
                    assetsContext =
                        try {
                            context.createPackageContext(PACKAGE, 0)
                        } catch (e: PackageManager.NameNotFoundException) {
                            null
                        }
                    checked = true
                }
            }
        }
        return assetsContext
    }

    // Quick Settings tile spec -> glyph (PenguinOS' LiquidGlassGlyphs).
    private val glyphBySpec =
        mapOf(
            "wifi" to "lg_glyph_wifi",
            "internet" to "lg_glyph_wifi",
            "cell" to "lg_glyph_antenna_radiowaves_left_right",
            "cast" to "lg_glyph_rectangle_on_rectangle",
            "dnd" to "lg_glyph_moon_fill",
            "wallet" to "lg_glyph_creditcard_fill",
            "airplane" to "lg_glyph_airplane",
            "rotation" to "lg_glyph_lock_rotation",
            "battery" to "lg_glyph_battery_25",
            "alarm" to "lg_glyph_alarm_fill",
            "controls" to "lg_glyph_house_fill",
            "screenrecord" to "lg_glyph_smallcircle_fill_circle",
            "hotspot" to "lg_glyph_personalhotspot",
            "location" to "lg_glyph_location_fill",
            "mictoggle" to "lg_glyph_mic_fill",
            "cameratoggle" to "lg_glyph_camera_fill",
            "qr_code_scanner" to "lg_glyph_qrcode_viewfinder",
            "dark" to "lg_glyph_circle_lefthalf_fill",
        )

    private val glyphCache = HashMap<String, Drawable.ConstantState?>()

    /** The Liquid Glass glyph for a tile spec, or null. */
    fun glyph(context: Context, spec: String): Drawable? {
        val name = glyphBySpec[spec] ?: return null
        val assets = context(context) ?: return null
        val state =
            synchronized(glyphCache) {
                glyphCache.getOrPut(name) {
                    val id = assets.resources.getIdentifier(name, "drawable", PACKAGE)
                    if (id == 0) null
                    else
                        try {
                            assets.getDrawable(id)?.constantState
                        } catch (t: Throwable) {
                            Log.w(TAG, "Could not load $name", t)
                            null
                        }
                }
            }
        return state?.newDrawable(assets.resources)?.mutate()
    }

    private val textCache = HashMap<String, String?>()

    /** An asset file as text (e.g. a Lottie animation under cos/), or null. */
    fun text(context: Context, path: String): String? {
        val assets = context(context) ?: return null
        return synchronized(textCache) {
            textCache.getOrPut(path) {
                try {
                    assets.assets.open(path).bufferedReader().use { it.readText() }
                } catch (t: Throwable) {
                    null
                }
            }
        }
    }
}
