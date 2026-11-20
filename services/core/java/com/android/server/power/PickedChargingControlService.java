/*
 * SPDX-FileCopyrightText: 2026 Miku-Picked UI
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.server.power;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.text.format.DateUtils;
import android.util.Slog;

import com.android.internal.R;
import com.android.server.SystemService;

import vendor.lineage.health.ChargingControlSupportedMode;
import vendor.lineage.health.ChargingLimitInfo;
import vendor.lineage.health.IChargingControl;

import java.util.Calendar;

/**
 * Miku-Picked UI: charging control, after LineageOS' ChargingControlController (lineage-sdk
 * HealthInterfaceService), which Miku can't use without lineage-sdk. Drives the Lineage health
 * HAL's IChargingControl, which the device trees already ship.
 *
 * Modes (Settings.System {@link Settings.System#PICKED_CHARGING_CONTROL_MODE}):
 * <ul>
 * <li>{@link #MODE_LIMIT}: stop charging at {@link Settings.System#PICKED_CHARGING_CONTROL_LIMIT}.
 * <li>{@link #MODE_ALARM}: overnight, hold the battery at {@link #HOLD_LEVEL} and top it up so it
 *     is full at the next alarm (from {@link #ALARM_WINDOW_MS} before it).
 * <li>{@link #MODE_CUSTOM}: the same between a start and a "full by" time of day.
 * </ul>
 *
 * The cap goes to the HAL's limit mode where it has one (Samsung: batt_full_capacity, which
 * leaves the device running from the charger at the limit), otherwise charging is toggled off
 * at the cap and back on {@link #TOGGLE_RECHARGE_MARGIN}% below it. While a cap is active and the
 * phone is plugged in, a notification offers to charge to 100% once (until unplugged).
 */
public class PickedChargingControlService extends SystemService {

    private static final String TAG = "PickedChargingControl";

    static final int MODE_LIMIT = 1;
    static final int MODE_ALARM = 2;
    static final int MODE_CUSTOM = 3;

    private static final int DEFAULT_MODE = MODE_LIMIT;
    private static final int DEFAULT_LIMIT = 80;
    private static final int DEFAULT_START_TIME = 22 * 3600; // seconds of day
    private static final int DEFAULT_TARGET_TIME = 6 * 3600;

    /** Level the time based modes hold the battery at until the top-up. */
    private static final int HOLD_LEVEL = 80;
    /** Topping up from HOLD_LEVEL is planned at this many minutes per percent... */
    private static final long TOP_UP_MS_PER_PERCENT = 3 * DateUtils.MINUTE_IN_MILLIS;
    /** ...plus this margin, so slow chargers still make it. */
    private static final long TOP_UP_MARGIN_MS = 30 * DateUtils.MINUTE_IN_MILLIS;
    /** In alarm mode, charging is only held during this time before the alarm. */
    private static final long ALARM_WINDOW_MS = 9 * DateUtils.HOUR_IN_MILLIS;
    /** Without a limit mode in the HAL, charging resumes this far below the cap. */
    private static final int TOGGLE_RECHARGE_MARGIN = 5;

    private static final String CHANNEL_ID = "picked_charging_control";
    private static final int NOTIFICATION_ID = 0x50434843; // "PCHC"
    private static final String ACTION_CHARGE_FULL =
            "com.android.server.power.PICKED_CHARGE_FULL_ONCE";

    private Handler mHandler;
    private IChargingControl mHal;
    private boolean mHasLimitMode;
    private boolean mHasBypass;

    // State, on mHandler.
    private boolean mPlugged;
    private int mLevel = -1;
    private boolean mFullOnce;
    private int mAppliedCap = -1;
    private Boolean mToggleEnabled; // last value sent in toggle mode
    private long mAlarmAt;
    private boolean mNotificationShown;
    private String mNotificationText;

    private final AlarmManager.OnAlarmListener mAlarmListener = () -> {
        mAlarmAt = 0;
        update();
    };

    public PickedChargingControlService(Context context) {
        super(context);
    }

    @Override
    public void onStart() {
        final HandlerThread thread = new HandlerThread(TAG, Process.THREAD_PRIORITY_BACKGROUND);
        thread.start();
        mHandler = new Handler(thread.getLooper());
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == PHASE_BOOT_COMPLETED) {
            mHandler.post(this::init);
        }
    }

    private void init() {
        final String name = IChargingControl.DESCRIPTOR + "/default";
        if (!ServiceManager.isDeclared(name)) {
            Slog.i(TAG, "No charging control HAL");
            return;
        }
        mHal = IChargingControl.Stub.asInterface(ServiceManager.waitForDeclaredService(name));
        if (mHal == null) {
            Slog.w(TAG, "Charging control HAL did not start");
            return;
        }
        try {
            final int modes = mHal.getSupportedMode();
            mHasLimitMode = (modes & ChargingControlSupportedMode.LIMIT) != 0;
            mHasBypass = (modes & ChargingControlSupportedMode.BYPASS) != 0;
            if (!mHasLimitMode && (modes & ChargingControlSupportedMode.TOGGLE) == 0) {
                Slog.i(TAG, "HAL supports neither limit nor toggle mode: " + modes);
                mHal = null;
                return;
            }
            Slog.i(TAG, "HAL modes " + modes);
        } catch (RemoteException | RuntimeException e) {
            Slog.e(TAG, "Cannot query the HAL", e);
            mHal = null;
            return;
        }

        final Context context = getContext();
        final NotificationManager nm = context.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID,
                context.getString(R.string.picked_charging_control_channel),
                NotificationManager.IMPORTANCE_LOW));

        final ContentObserver observer = new ContentObserver(mHandler) {
            @Override
            public void onChange(boolean selfChange) {
                update();
            }
        };
        final ContentResolver cr = context.getContentResolver();
        for (String key : new String[] {
                Settings.System.PICKED_CHARGING_CONTROL_ENABLED,
                Settings.System.PICKED_CHARGING_CONTROL_MODE,
                Settings.System.PICKED_CHARGING_CONTROL_LIMIT,
                Settings.System.PICKED_CHARGING_CONTROL_START_TIME,
                Settings.System.PICKED_CHARGING_CONTROL_TARGET_TIME}) {
            cr.registerContentObserver(Settings.System.getUriFor(key), false, observer,
                    UserHandle.USER_ALL);
        }

        final IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_BATTERY_CHANGED);
        filter.addAction(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED);
        filter.addAction(Intent.ACTION_TIME_CHANGED);
        filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        filter.addAction(Intent.ACTION_USER_SWITCHED);
        // The next alarm clock change is sent to its user only.
        context.registerReceiverAsUser(new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                if (Intent.ACTION_BATTERY_CHANGED.equals(intent.getAction())) {
                    onBatteryChanged(intent);
                } else {
                    update();
                }
            }
        }, UserHandle.ALL, filter, null, mHandler, Context.RECEIVER_EXPORTED);

        // From the notification action, a PendingIntent of "android" to itself.
        context.registerReceiverAsUser(new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                Slog.i(TAG, "Charging to full once");
                mFullOnce = true;
                update();
            }
        }, UserHandle.ALL, new IntentFilter(ACTION_CHARGE_FULL), null, mHandler,
                Context.RECEIVER_NOT_EXPORTED);
        // The sticky battery broadcast delivered on registration runs the first update().
    }

    private void onBatteryChanged(Intent intent) {
        final boolean plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
        final int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        final int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        final int pct = level < 0 || scale <= 0 ? -1 : Math.round(level * 100f / scale);
        if (mPlugged && !plugged) mFullOnce = false; // "charge to full" lasts until unplugged
        final boolean changed = plugged != mPlugged || pct != mLevel;
        mPlugged = plugged;
        mLevel = pct;
        if (changed) update();
    }

    // ---------------------------------------------------------------------------------------

    private int getInt(String key, int def) {
        return Settings.System.getIntForUser(getContext().getContentResolver(), key, def,
                UserHandle.USER_CURRENT);
    }

    private void update() {
        if (mHal == null) return;
        final boolean enabled = getInt(Settings.System.PICKED_CHARGING_CONTROL_ENABLED, 0) != 0;
        final int mode = getInt(Settings.System.PICKED_CHARGING_CONTROL_MODE, DEFAULT_MODE);
        final long now = System.currentTimeMillis();

        int cap = 100;
        long nextChange = 0;
        long fullBy = 0;
        if (enabled && !mFullOnce) {
            if (mode == MODE_LIMIT) {
                cap = clamp(getInt(Settings.System.PICKED_CHARGING_CONTROL_LIMIT, DEFAULT_LIMIT),
                        50, 100);
            } else if (mode == MODE_ALARM || mode == MODE_CUSTOM) {
                final long[] window = mode == MODE_ALARM ? alarmWindow() : customWindow(now);
                if (window != null) {
                    final long start = window[0], target = window[1];
                    final long topUp = target - (100 - HOLD_LEVEL) * TOP_UP_MS_PER_PERCENT
                            - TOP_UP_MARGIN_MS;
                    if (now < start) {
                        nextChange = start;
                    } else if (now < topUp) {
                        cap = HOLD_LEVEL;
                        fullBy = target;
                        nextChange = topUp;
                    } else if (now < target) {
                        nextChange = target;
                    }
                }
            }
        }

        applyCap(cap);
        // Timed changes only matter while plugged in; plugging in runs update() again.
        scheduleAlarm(mPlugged ? nextChange : 0);
        updateNotification(cap, fullBy);
    }

    /** Alarm mode: [alarm - ALARM_WINDOW_MS, alarm), or null without an alarm. */
    private long[] alarmWindow() {
        final AlarmManager am = getContext().getSystemService(AlarmManager.class);
        final AlarmManager.AlarmClockInfo info = am.getNextAlarmClock(UserHandle.USER_CURRENT);
        if (info == null) return null;
        final long target = info.getTriggerTime();
        return new long[] {target - ALARM_WINDOW_MS, target};
    }

    /** Custom mode: the current or next [start, target) window. */
    private long[] customWindow(long now) {
        long start = timeOfDay(now, getInt(Settings.System.PICKED_CHARGING_CONTROL_START_TIME,
                DEFAULT_START_TIME));
        long target = timeOfDay(now, getInt(Settings.System.PICKED_CHARGING_CONTROL_TARGET_TIME,
                DEFAULT_TARGET_TIME));
        if (start == target) return null;
        if (start > target) {
            // Over midnight, e.g. 22:00 -> 06:00.
            if (now >= target) {
                target += DateUtils.DAY_IN_MILLIS;
            } else {
                start -= DateUtils.DAY_IN_MILLIS;
            }
        } else if (now >= target) {
            start += DateUtils.DAY_IN_MILLIS;
            target += DateUtils.DAY_IN_MILLIS;
        }
        return new long[] {start, target};
    }

    /** Today's {@code secondOfDay} in the local time zone, in epoch ms. */
    private static long timeOfDay(long now, int secondOfDay) {
        final Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        c.add(Calendar.SECOND, clamp(secondOfDay, 0, 24 * 3600 - 1));
        return c.getTimeInMillis();
    }

    private void applyCap(int cap) {
        try {
            if (mHasLimitMode) {
                if (cap == mAppliedCap) return;
                final ChargingLimitInfo limit = new ChargingLimitInfo();
                limit.max = cap;
                limit.min = cap >= 100 ? 0 : Math.max(0, cap - (mHasBypass ? 1 : 10));
                mHal.setChargingLimit(limit);
                mAppliedCap = cap;
                Slog.i(TAG, "Charging limit " + cap + "%");
                return;
            }
            // Toggle: off at the cap, on again TOGGLE_RECHARGE_MARGIN below it.
            mAppliedCap = cap;
            boolean enable;
            if (cap >= 100 || mLevel < 0) {
                enable = true;
            } else if (mLevel >= cap) {
                enable = false;
            } else if (mLevel <= cap - TOGGLE_RECHARGE_MARGIN) {
                enable = true;
            } else {
                enable = mToggleEnabled == null || mToggleEnabled;
            }
            if (mToggleEnabled == null || mToggleEnabled != enable) {
                mHal.setChargingEnabled(enable);
                mToggleEnabled = enable;
                Slog.i(TAG, "Charging " + (enable ? "enabled" : "disabled") + " at " + mLevel
                        + "% (cap " + cap + "%)");
            }
        } catch (RemoteException | RuntimeException e) {
            Slog.e(TAG, "Cannot apply the charging cap " + cap, e);
            mAppliedCap = -1;
            mToggleEnabled = null;
        }
    }

    private void scheduleAlarm(long at) {
        if (at == mAlarmAt) return;
        final AlarmManager am = getContext().getSystemService(AlarmManager.class);
        if (mAlarmAt != 0) am.cancel(mAlarmListener);
        mAlarmAt = at;
        if (at != 0) {
            am.setExact(AlarmManager.RTC_WAKEUP, at, TAG, mAlarmListener, mHandler);
        }
    }

    private void updateNotification(int cap, long fullBy) {
        final Context context = getContext();
        final NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (!mPlugged || cap >= 100) {
            if (mNotificationShown) {
                nm.cancelAsUser(null, NOTIFICATION_ID, UserHandle.ALL);
                mNotificationShown = false;
                mNotificationText = null;
            }
            return;
        }
        final boolean atCap = mLevel >= cap;
        final String text;
        if (fullBy != 0) {
            final String time = DateFormat.getTimeFormat(context).format(fullBy);
            text = context.getString(atCap ? R.string.picked_charging_control_held_until
                    : R.string.picked_charging_control_charging_until, cap, time);
        } else {
            text = context.getString(atCap ? R.string.picked_charging_control_limited
                    : R.string.picked_charging_control_limiting, cap);
        }
        if (mNotificationShown && text.equals(mNotificationText)) return;

        final PendingIntent full = PendingIntent.getBroadcastAsUser(context, 0,
                new Intent(ACTION_CHARGE_FULL).setPackage(context.getPackageName()),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT,
                UserHandle.SYSTEM);
        final Notification n = new Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_lock_idle_charging)
                .setContentTitle(context.getString(R.string.picked_charging_control_title))
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setLocalOnly(true)
                .setColor(context.getColor(R.color.system_notification_accent_color))
                .addAction(new Notification.Action.Builder(null,
                        context.getString(R.string.picked_charging_control_charge_full),
                        full).build())
                .build();
        nm.notifyAsUser(null, NOTIFICATION_ID, n, UserHandle.ALL);
        mNotificationShown = true;
        mNotificationText = text;
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
