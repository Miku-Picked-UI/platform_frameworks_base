/*
 * SPDX-FileCopyrightText: 2026 Miku-Picked UI
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.server.net;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.Slog;

import java.util.Set;

/**
 * Miku-Picked UI: per-app network firewall (Settings > Picked features > Network firewall).
 *
 * Settings.Global lists of package names (";" separated), applied to the app in every user:
 * - {@link #KEY_MOBILE}: no mobile data (and no metered Wi-Fi). Enforced as an admin metered
 *   restriction in NetworkPolicyManagerService, so it holds in the foreground and through every
 *   rule recompute.
 * - {@link #KEY_ALL}: no network at all, through the OEM deny firewall chain 3, which nothing
 *   else in the platform uses.
 *
 * The lists are resolved to uids at boot and whenever they, the installed packages or the users
 * change; NetworkPolicyManagerService then applies the difference.
 */
final class PickedFirewall {

    private static final String TAG = "PickedFirewall";

    static final String KEY_MOBILE = "picked_firewall_mobile";
    static final String KEY_ALL = "picked_firewall_all";

    interface Callback {
        /** Called on the handler with the resolved uid sets. */
        void onPickedFirewallChanged(Set<Integer> mobileUids, Set<Integer> allUids);
    }

    private final Context mContext;
    private final Handler mHandler;
    private final Callback mCallback;
    private final Runnable mUpdate = this::update;

    PickedFirewall(Context context, Handler handler, Callback callback) {
        mContext = context;
        mHandler = handler;
        mCallback = callback;
    }

    void start() {
        final ContentObserver observer = new ContentObserver(mHandler) {
            @Override
            public void onChange(boolean selfChange) {
                scheduleUpdate();
            }
        };
        mContext.getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(KEY_MOBILE), false, observer, UserHandle.USER_ALL);
        mContext.getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(KEY_ALL), false, observer, UserHandle.USER_ALL);

        final BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                scheduleUpdate();
            }
        };
        final IntentFilter packages = new IntentFilter();
        packages.addAction(Intent.ACTION_PACKAGE_ADDED);
        packages.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packages.addDataScheme("package");
        mContext.registerReceiverAsUser(receiver, UserHandle.ALL, packages, null, mHandler);
        final IntentFilter users = new IntentFilter();
        users.addAction(Intent.ACTION_USER_ADDED);
        users.addAction(Intent.ACTION_USER_REMOVED);
        mContext.registerReceiverAsUser(receiver, UserHandle.ALL, users, null, mHandler);

        scheduleUpdate();
    }

    private void scheduleUpdate() {
        mHandler.removeCallbacks(mUpdate);
        mHandler.postDelayed(mUpdate, 300);
    }

    private void update() {
        final Set<Integer> mobile = resolve(KEY_MOBILE);
        final Set<Integer> all = resolve(KEY_ALL);
        Slog.i(TAG, "mobile=" + mobile + " all=" + all);
        mCallback.onPickedFirewallChanged(mobile, all);
    }

    private Set<Integer> resolve(String key) {
        final Set<Integer> uids = new ArraySet<>();
        final String value = Settings.Global.getString(mContext.getContentResolver(), key);
        if (TextUtils.isEmpty(value)) return uids;
        final PackageManager pm = mContext.getPackageManager();
        final UserManager um = mContext.getSystemService(UserManager.class);
        for (String pkg : value.split(";")) {
            pkg = pkg.trim();
            if (pkg.isEmpty()) continue;
            for (UserInfo user : um.getAliveUsers()) {
                try {
                    final int uid = pm.getPackageUidAsUser(pkg, 0, user.id);
                    // Never firewall system uids (shared uids like android.uid.system).
                    if (UserHandle.isApp(uid)) uids.add(uid);
                } catch (PackageManager.NameNotFoundException ignored) {
                }
            }
        }
        return uids;
    }
}
