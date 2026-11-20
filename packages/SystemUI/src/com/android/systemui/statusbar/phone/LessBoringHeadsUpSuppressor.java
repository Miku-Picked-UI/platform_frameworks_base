/*
 * SPDX-FileCopyrightText: 2026 Miku-Picked UI
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.statusbar.phone;

import android.app.Notification;
import android.content.Context;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.provider.Settings;
import android.provider.Telephony.Sms;
import android.telecom.TelecomManager;
import android.text.TextUtils;

import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.interruption.NotificationInterruptSuppressor;

import java.util.Locale;

/**
 * "Less boring heads up" (crDroid / Lunaris): while the screen is on, only calls, alarms, the
 * default dialer and SMS app, and messaging apps may pop up as heads-up. Everything else still
 * lands in the shade silently. Settings.System.LESS_BORING_HEADS_UP.
 */
final class LessBoringHeadsUpSuppressor implements NotificationInterruptSuppressor {

    private static final String TAG = "LessBoringHeadsUp";

    // Package name fragments that are always allowed (as in Lunaris).
    private static final String[] ALLOWED_FRAGMENTS = {
            "dialer", "messaging", "messenger", "clock",
    };

    private final Context mContext;
    private volatile boolean mEnabled;

    LessBoringHeadsUpSuppressor(Context context) {
        mContext = context;
        final ContentObserver observer = new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override
            public void onChange(boolean selfChange) {
                update();
            }
        };
        context.getContentResolver().registerContentObserver(
                Settings.System.getUriFor(Settings.System.LESS_BORING_HEADS_UP), false,
                observer, UserHandle.USER_ALL);
        update();
    }

    private void update() {
        mEnabled = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.LESS_BORING_HEADS_UP, 0, UserHandle.USER_CURRENT) == 1;
    }

    /** Re-read the setting for a new user. */
    void onUserSwitched() {
        update();
    }

    @Override
    public String getName() {
        return TAG;
    }

    @Override
    public boolean suppressAwakeHeadsUp(NotificationEntry entry) {
        if (!mEnabled) return false;
        final Notification n = entry.getSbn().getNotification();
        if (Notification.CATEGORY_CALL.equals(n.category)
                || Notification.CATEGORY_ALARM.equals(n.category)) {
            return false;
        }
        final String pkg = entry.getSbn().getPackageName();
        if (pkg == null) return false;
        if (pkg.equals(defaultDialer()) || pkg.equals(defaultSms())) return false;
        final String lower = pkg.toLowerCase(Locale.ROOT);
        for (String f : ALLOWED_FRAGMENTS) {
            if (lower.contains(f)) return false;
        }
        return true;
    }

    private String defaultDialer() {
        try {
            final TelecomManager tm = mContext.getSystemService(TelecomManager.class);
            return tm != null ? tm.getDefaultDialerPackage() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String defaultSms() {
        try {
            final String pkg = Sms.getDefaultSmsPackage(mContext);
            return TextUtils.isEmpty(pkg) ? null : pkg;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
