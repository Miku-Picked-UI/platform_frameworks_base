/*
 * Copyright (C) 2026 The PenguinOS Project
 * SPDX-FileCopyrightText: 2026 Miku-Picked UI
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.server.wm;

import android.content.ContentResolver;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;

/**
 * Mirrors the Liquid Glass settings into the properties SurfaceFlinger's renderengine reads
 * (after PenguinOS' WindowManagerService.syncLiquidGlass). Settings.Secure is the source of
 * truth: Penguin Laboratory and Settings > Picked features write it. Apps can't set
 * persist.sys.* themselves, so system_server does it for them.
 *
 * Miku-Picked UI ships glass on through a product property, while the Secure keys start out
 * unset, so a key that isn't set yet is first seeded from the property instead of turning the
 * glass off.
 */
final class PickedLiquidGlassSync extends ContentObserver {

    private static final String SETTING = "penguin_liquid_glass";
    private static final String PROPERTY = "persist.sys.penguin.liquid_glass";
    // 0 iOS, 1 ColorOS
    private static final String STYLE_SETTING = "penguin_liquid_glass_style";
    private static final String STYLE_PROPERTY = "persist.sys.penguin.liquid_glass_style";
    // How strongly the iOS style's rims refract, 0-100% of the strongest bend
    private static final String REFRACTION_SETTING = "penguin_liquid_glass_refraction";
    private static final String REFRACTION_PROPERTY =
            "persist.sys.penguin.liquid_glass_refraction";
    private static final int REFRACTION_DEFAULT = 60;
    // [PenguinOS] Luminous: ViewPropertyAnimator runs view animations on the render thread.
    // Property 0 off, 1 system apps, 2 every app (com.android.internal.penguin.luminous).
    private static final String LUMINOUS_SETTING = "penguin_luminous";
    private static final String LUMINOUS_ALL_APPS_SETTING = "penguin_luminous_all_apps";
    private static final String LUMINOUS_PROPERTY = "persist.sys.penguin.luminous";

    private final ContentResolver mResolver;

    private PickedLiquidGlassSync(ContentResolver resolver, Handler handler) {
        super(handler);
        mResolver = resolver;
    }

    static void start(ContentResolver resolver, Handler handler) {
        final PickedLiquidGlassSync sync = new PickedLiquidGlassSync(resolver, handler);
        sync.seed();
        for (String key : new String[] {SETTING, STYLE_SETTING, REFRACTION_SETTING,
                LUMINOUS_SETTING, LUMINOUS_ALL_APPS_SETTING}) {
            resolver.registerContentObserver(Settings.Secure.getUriFor(key), false, sync,
                    UserHandle.USER_ALL);
        }
        sync.sync();
    }

    @Override
    public void onChange(boolean selfChange) {
        sync();
    }

    /** Copies the properties into any key that isn't set yet. */
    private void seed() {
        if (get(SETTING) == null) {
            put(SETTING, SystemProperties.getBoolean(PROPERTY, false) ? 1 : 0);
        }
        if (get(STYLE_SETTING) == null) {
            put(STYLE_SETTING, "cos".equals(SystemProperties.get(STYLE_PROPERTY)) ? 1 : 0);
        }
        if (get(REFRACTION_SETTING) == null) {
            put(REFRACTION_SETTING,
                    clamp(SystemProperties.getInt(REFRACTION_PROPERTY, REFRACTION_DEFAULT)));
        }
    }

    private void sync() {
        try {
            final boolean on = Settings.Secure.getIntForUser(mResolver, SETTING, 0,
                    UserHandle.USER_CURRENT) != 0;
            set(PROPERTY, on ? "1" : "0");
            final int style = Settings.Secure.getIntForUser(mResolver, STYLE_SETTING, 0,
                    UserHandle.USER_CURRENT);
            set(STYLE_PROPERTY, style == 1 ? "cos" : "ios");
            final int refraction = Settings.Secure.getIntForUser(mResolver, REFRACTION_SETTING,
                    REFRACTION_DEFAULT, UserHandle.USER_CURRENT);
            set(REFRACTION_PROPERTY, Integer.toString(clamp(refraction)));
            final boolean luminous = Settings.Secure.getIntForUser(mResolver, LUMINOUS_SETTING,
                    0, UserHandle.USER_CURRENT) != 0;
            final boolean luminousAll = Settings.Secure.getIntForUser(mResolver,
                    LUMINOUS_ALL_APPS_SETTING, 0, UserHandle.USER_CURRENT) != 0;
            set(LUMINOUS_PROPERTY, luminous ? (luminousAll ? "2" : "1") : "0");
        } catch (RuntimeException e) {
            // A bad value must never take system_server down
        }
    }

    private static void set(String property, String value) {
        if (!value.equals(SystemProperties.get(property))) {
            SystemProperties.set(property, value);
        }
    }

    private String get(String key) {
        return Settings.Secure.getStringForUser(mResolver, key, UserHandle.USER_SYSTEM);
    }

    private void put(String key, int value) {
        Settings.Secure.putIntForUser(mResolver, key, value, UserHandle.USER_SYSTEM);
    }

    private static int clamp(int percent) {
        return Math.max(0, Math.min(100, percent));
    }
}
