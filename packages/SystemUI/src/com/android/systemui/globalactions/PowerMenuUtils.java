/*
 * SPDX-FileCopyrightText: 2017-2024 The LineageOS Project
 * SPDX-FileCopyrightText: 2026 Miku-Picked UI
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.globalactions;

import android.app.KeyguardManager;
import android.content.Context;
import android.os.UserHandle;
import android.provider.Settings;

/**
 * Imported from lineage-sdk (org.lineageos.internal.util.PowerMenuUtils).
 * Uses {@link Settings.Secure#ADVANCED_REBOOT} instead of LineageSettings.
 */
public final class PowerMenuUtils {
    private PowerMenuUtils() {}

    public static boolean isAdvancedRestartPossible(final Context context) {
        KeyguardManager km = context.getSystemService(KeyguardManager.class);
        boolean keyguardLocked = km.inKeyguardRestrictedInputMode() && km.isKeyguardSecure();
        boolean advancedRestartEnabled = Settings.Secure.getInt(context.getContentResolver(),
                Settings.Secure.ADVANCED_REBOOT, 0) == 1;
        boolean isPrimaryUser = UserHandle.getCallingUserId() == UserHandle.USER_SYSTEM;

        return advancedRestartEnabled && !keyguardLocked && isPrimaryUser;
    }
}
