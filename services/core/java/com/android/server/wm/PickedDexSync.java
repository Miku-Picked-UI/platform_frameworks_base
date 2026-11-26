/*
 * Copyright (C) 2026 The PenguinOS Project
 * SPDX-FileCopyrightText: 2026 Miku-Picked UI
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.server.wm;

import static android.provider.Settings.Global.DEVELOPMENT_ENABLE_FREEFORM_WINDOWS_SUPPORT;
import static android.provider.Settings.Secure.MIRROR_BUILT_IN_DISPLAY;

import android.content.ContentResolver;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Slog;
import android.window.DesktopExperienceFlags;

/**
 * DeX (PenguinOS, Penguin Laboratory > DeX): a connected screen gets its own desktop while
 * Settings.Secure mirror_built_in_display is 0, as on PenguinOS.
 *
 * On Android 16 the desktop on a connected screen only exists with the desktop experience
 * flags, which follow persist.wm.debug.desktop_experience_devopts from boot (Settings > Picked
 * features > Desktop mode on a monitor sets it too). Turning DeX on sets the property, so DeX
 * starts after the next restart; DisplayManagerService then hosts tasks on a connected screen
 * while mirroring is off. DisplayManagerService reads an unset key as "don't mirror", PenguinOS
 * as "mirror", so the key is written out at boot from whether the desktop experience is on.
 */
final class PickedDexSync extends ContentObserver {

    private static final String TAG = "PickedDexSync";
    static final String DESKTOP_EXPERIENCE_PROPERTY =
            DesktopExperienceFlags.SYSTEM_PROPERTY_NAME;

    private final ContentResolver mResolver;
    private final WindowManagerService mWm;

    private PickedDexSync(ContentResolver resolver, Handler handler, WindowManagerService wm) {
        super(handler);
        mResolver = resolver;
        mWm = wm;
    }

    static void start(ContentResolver resolver, Handler handler, WindowManagerService wm) {
        final PickedDexSync sync = new PickedDexSync(resolver, handler, wm);
        if (Settings.Secure.getStringForUser(resolver, MIRROR_BUILT_IN_DISPLAY,
                UserHandle.USER_SYSTEM) == null) {
            final boolean desktop = SystemProperties.getBoolean(DESKTOP_EXPERIENCE_PROPERTY, false);
            Settings.Secure.putIntForUser(resolver, MIRROR_BUILT_IN_DISPLAY, desktop ? 0 : 1,
                    UserHandle.USER_SYSTEM);
        }
        resolver.registerContentObserver(Settings.Secure.getUriFor(MIRROR_BUILT_IN_DISPLAY),
                false, sync, UserHandle.USER_ALL);
        wm.mDexEnabled = isDexEnabled(resolver);
    }

    static boolean isDexEnabled(ContentResolver resolver) {
        return Settings.Secure.getIntForUser(resolver, MIRROR_BUILT_IN_DISPLAY, 0,
                UserHandle.USER_CURRENT) == 0;
    }

    @Override
    public void onChange(boolean selfChange) {
        final boolean dex = isDexEnabled(mResolver);
        mWm.mDexEnabled = dex;
        mWm.updateDexGlassDisplays();
        if (dex && !SystemProperties.getBoolean(DESKTOP_EXPERIENCE_PROPERTY, false)) {
            try {
                SystemProperties.set(DESKTOP_EXPERIENCE_PROPERTY, "1");
                Settings.Global.putInt(mResolver, DEVELOPMENT_ENABLE_FREEFORM_WINDOWS_SUPPORT, 1);
                Slog.i(TAG, "DeX turned on: desktop experience on from the next boot");
            } catch (RuntimeException e) {
                Slog.w(TAG, "Couldn't turn the desktop experience on for DeX", e);
            }
        }
    }
}
