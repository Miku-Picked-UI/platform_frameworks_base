/*
 * SPDX-FileCopyrightText: 2026 Miku-Picked UI
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.server.audio;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.audiopolicy.AudioMix;
import android.media.audiopolicy.AudioMixingRule;
import android.media.audiopolicy.AudioPolicy;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Slog;
import android.util.SparseArray;

import java.util.ArrayList;
import java.util.List;

/**
 * Miku-Picked UI: per-app audio, in system_server next to AudioService.
 *
 * Per-app volume — Settings.System picked_app_volume "pkg=percent;pkg=percent" (0..100): each
 * app's players get that volume multiplier (PlaybackActivityMonitor, through IPlayer.setVolume),
 * on top of the normal stream volume.
 *
 * Separate app sound — Settings.System picked_separate_sound_enabled (0/1),
 * picked_separate_sound_apps "pkg;pkg" and picked_separate_sound_device (0 = phone speaker,
 * 1 = the connected audio device: Bluetooth, wired or USB). The apps' audio is routed to that
 * device by a dynamic AudioPolicy with uid mixing rules while the device is connected; when it is
 * not, the policy is dropped and the apps play wherever everything else plays.
 *
 * Both follow the current user and installs/updates of the listed packages.
 */
final class PickedAppAudio {

    private static final String TAG = "PickedAppAudio";

    static final String KEY_VOLUME = "picked_app_volume";
    static final String KEY_SEPARATE_ENABLED = "picked_separate_sound_enabled";
    static final String KEY_SEPARATE_APPS = "picked_separate_sound_apps";
    static final String KEY_SEPARATE_DEVICE = "picked_separate_sound_device";

    private final Context mContext;
    private final PlaybackActivityMonitor mMonitor;
    private final Handler mHandler;
    private final AudioManager mAudioManager;
    private final Runnable mUpdate = this::update;

    private AudioPolicy mPolicy;
    /** What the current policy routes: device id and uids, to skip identical re-registrations. */
    private String mPolicyKey;

    PickedAppAudio(Context context, PlaybackActivityMonitor monitor) {
        mContext = context;
        mMonitor = monitor;
        final HandlerThread thread = new HandlerThread(TAG);
        thread.start();
        mHandler = new Handler(thread.getLooper());
        mAudioManager = context.getSystemService(AudioManager.class);
    }

    void start() {
        final ContentObserver observer = new ContentObserver(mHandler) {
            @Override
            public void onChange(boolean selfChange) {
                schedule();
            }
        };
        for (String key : new String[] {KEY_VOLUME, KEY_SEPARATE_ENABLED, KEY_SEPARATE_APPS,
                KEY_SEPARATE_DEVICE}) {
            mContext.getContentResolver().registerContentObserver(
                    Settings.System.getUriFor(key), false, observer, UserHandle.USER_ALL);
        }
        final BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                schedule();
            }
        };
        final IntentFilter packages = new IntentFilter();
        packages.addAction(Intent.ACTION_PACKAGE_ADDED);
        packages.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packages.addDataScheme("package");
        mContext.registerReceiverAsUser(receiver, UserHandle.ALL, packages, null, mHandler);
        mContext.registerReceiverAsUser(receiver, UserHandle.ALL,
                new IntentFilter(Intent.ACTION_USER_SWITCHED), null, mHandler);
        mAudioManager.registerAudioDeviceCallback(new AudioDeviceCallback() {
            @Override
            public void onAudioDevicesAdded(AudioDeviceInfo[] added) {
                schedule();
            }

            @Override
            public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) {
                schedule();
            }
        }, mHandler);
        schedule();
    }

    private void schedule() {
        mHandler.removeCallbacks(mUpdate);
        mHandler.postDelayed(mUpdate, 250);
    }

    private String get(String key, int user) {
        return Settings.System.getStringForUser(mContext.getContentResolver(), key, user);
    }

    private int uid(String pkg, int user) {
        try {
            return mContext.getPackageManager().getPackageUidAsUser(pkg, 0, user);
        } catch (PackageManager.NameNotFoundException e) {
            return -1;
        }
    }

    private void update() {
        final int user = ActivityManager.getCurrentUser();
        updateVolumes(user);
        updateSeparateSound(user);
    }

    private void updateVolumes(int user) {
        final SparseArray<Float> volumes = new SparseArray<>();
        final String value = get(KEY_VOLUME, user);
        if (!TextUtils.isEmpty(value)) {
            for (String entry : value.split(";")) {
                final String[] kv = entry.split("=");
                if (kv.length != 2) continue;
                try {
                    final int pct = Math.max(0, Math.min(100, Integer.parseInt(kv[1].trim())));
                    final int uid = uid(kv[0].trim(), user);
                    if (uid >= 0 && UserHandle.isApp(uid) && pct != 100) {
                        volumes.put(uid, pct / 100f);
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }
        mMonitor.setPickedAppVolumes(volumes);
    }

    private AudioDeviceInfo targetDevice(int mode) {
        final AudioDeviceInfo[] outputs = mAudioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
        final int[] wanted = mode == 0
                ? new int[] {AudioDeviceInfo.TYPE_BUILTIN_SPEAKER}
                : new int[] {AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET,
                        AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET,
                        AudioDeviceInfo.TYPE_USB_DEVICE};
        for (int type : wanted) {
            for (AudioDeviceInfo d : outputs) {
                if (d.getType() == type) return d;
            }
        }
        return null;
    }

    private void updateSeparateSound(int user) {
        final boolean enabled = "1".equals(get(KEY_SEPARATE_ENABLED, user));
        final List<Integer> uids = new ArrayList<>();
        final String apps = get(KEY_SEPARATE_APPS, user);
        if (enabled && !TextUtils.isEmpty(apps)) {
            for (String pkg : apps.split(";")) {
                final int uid = uid(pkg.trim(), user);
                if (uid >= 0 && UserHandle.isApp(uid) && !uids.contains(uid)) uids.add(uid);
            }
        }
        int mode = 1;
        try {
            mode = Integer.parseInt(get(KEY_SEPARATE_DEVICE, user));
        } catch (NumberFormatException ignored) {
        }
        final AudioDeviceInfo device = uids.isEmpty() ? null : targetDevice(mode);
        final String key = device == null ? null : device.getId() + ":" + uids;
        if (TextUtils.equals(key, mPolicyKey)) return;

        if (mPolicy != null) {
            mAudioManager.unregisterAudioPolicy(mPolicy);
            mPolicy = null;
        }
        mPolicyKey = null;
        if (device == null) return;
        try {
            final AudioMixingRule.Builder rule = new AudioMixingRule.Builder();
            for (int uid : uids) rule.addMixRule(AudioMixingRule.RULE_MATCH_UID, uid);
            final AudioMix mix = new AudioMix.Builder(rule.build())
                    .setRouteFlags(AudioMix.ROUTE_FLAG_RENDER)
                    .setDevice(device)
                    .build();
            final AudioPolicy policy = new AudioPolicy.Builder(mContext)
                    .addMix(mix)
                    .setLooper(mHandler.getLooper())
                    .build();
            if (mAudioManager.registerAudioPolicy(policy) == AudioManager.SUCCESS) {
                mPolicy = policy;
                mPolicyKey = key;
                Slog.i(TAG, "separate app sound: " + uids + " -> " + device.getProductName()
                        + " (type " + device.getType() + ")");
            } else {
                Slog.w(TAG, "separate app sound: policy registration failed");
            }
        } catch (RuntimeException e) {
            Slog.e(TAG, "separate app sound: cannot route", e);
        }
    }
}
