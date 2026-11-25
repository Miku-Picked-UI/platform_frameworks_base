/*
 * Copyright (C) 2026 The PenguinOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.doze;

import static android.hardware.biometrics.BiometricSourceType.FINGERPRINT;

import android.app.AlarmManager;
import android.hardware.biometrics.BiometricSourceType;
import android.hardware.display.AmbientDisplayConfiguration;
import android.os.Handler;

import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.doze.dagger.DozeScope;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.phone.DozeParameters;
import com.android.systemui.util.settings.SecureSettings;

import java.io.PrintWriter;

import javax.inject.Inject;

/**
 * [PenguinOS] Keeps the screen in AOD with only the fingerprint icon while full AOD is off, as
 * long as the under-display sensor is listening. Without a listening sensor the screen goes dark
 * instead. Settings.Secure doze_udfps_icon, optionally only between the hours of
 * {@link DozeUdfpsIconSchedule}.
 */
@DozeScope
public class DozeUdfpsIconController implements DozeMachine.Part {
    /** Fingerprint listening restarts briefly around screen off; don't blink the screen for it. */
    private static final long STOPPED_DEBOUNCE_MS = 1000;
    private static final String SCHEDULE_ALARM_TAG = "DozeUdfpsIconSchedule";

    private final AmbientDisplayConfiguration mConfig;
    private final UserTracker mUserTracker;
    private final DozeParameters mDozeParameters;
    private final KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    private final Handler mHandler;
    private final Runnable mUpdate = this::update;
    private final SecureSettings mSecureSettings;
    private final AlarmManager mAlarmManager;
    private final AlarmManager.OnAlarmListener mScheduleAlarm = this::onScheduleBoundary;
    private boolean mScheduleAlarmSet;

    private DozeMachine mMachine;
    private boolean mRegistered;
    private boolean mPulsing;

    private final KeyguardUpdateMonitorCallback mCallback = new KeyguardUpdateMonitorCallback() {
        @Override
        public void onBiometricRunningStateChanged(boolean running,
                BiometricSourceType biometricSourceType) {
            if (biometricSourceType == FINGERPRINT) {
                scheduleUpdate(running ? 0 : STOPPED_DEBOUNCE_MS);
            }
        }
    };

    @Inject
    DozeUdfpsIconController(
            AmbientDisplayConfiguration config,
            UserTracker userTracker,
            DozeParameters dozeParameters,
            KeyguardUpdateMonitor keyguardUpdateMonitor,
            @Main Handler handler,
            SecureSettings secureSettings,
            AlarmManager alarmManager) {
        mConfig = config;
        mUserTracker = userTracker;
        mDozeParameters = dozeParameters;
        mKeyguardUpdateMonitor = keyguardUpdateMonitor;
        mHandler = handler;
        mSecureSettings = secureSettings;
        mAlarmManager = alarmManager;
    }

    @Override
    public void setDozeMachine(DozeMachine dozeMachine) {
        mMachine = dozeMachine;
    }

    @Override
    public void transitionTo(DozeMachine.State oldState, DozeMachine.State newState) {
        switch (newState) {
            case INITIALIZED:
                if (!mRegistered) {
                    mKeyguardUpdateMonitor.registerCallback(mCallback);
                    mRegistered = true;
                }
                updateScheduleAlarm();
                return;
            case FINISH:
                stop();
                return;
            default:
                break;
        }
        mPulsing = newState == DozeMachine.State.DOZE_REQUEST_PULSE
                || newState == DozeMachine.State.DOZE_PULSING
                || newState == DozeMachine.State.DOZE_PULSING_BRIGHT;
        // Hide the rest of the AOD content before the frame for the new state is drawn, then
        // correct the doze state once every part has seen this transition.
        mDozeParameters.setUdfpsIconState(isIconWanted(), mPulsing);
        scheduleUpdate(0);
    }

    @Override
    public void destroy() {
        stop();
    }

    private void stop() {
        mHandler.removeCallbacks(mUpdate);
        cancelScheduleAlarm();
        if (mRegistered) {
            mKeyguardUpdateMonitor.removeCallback(mCallback);
            mRegistered = false;
        }
        mPulsing = false;
        mDozeParameters.setUdfpsIconState(false, false);
    }

    private void scheduleUpdate(long delayMs) {
        mHandler.removeCallbacks(mUpdate);
        mHandler.postDelayed(mUpdate, delayMs);
    }

    private int user() {
        return mUserTracker.getUserId();
    }

    private boolean isIconEnabled() {
        return mConfig.screenOffUdfpsIconEnabled(user());
    }

    private boolean isIconWanted() {
        return mKeyguardUpdateMonitor.isUdfpsEnrolled() && isIconEnabled() && isInSchedule();
    }

    /** Outside the chosen hours the icon stays off. */
    private boolean isInSchedule() {
        if (!DozeUdfpsIconSchedule.scheduleEnabled(mSecureSettings, user())) return true;
        return DozeUdfpsIconSchedule.inWindow(
                DozeUdfpsIconSchedule.minutesNow(System.currentTimeMillis()),
                DozeUdfpsIconSchedule.start(mSecureSettings, user()),
                DozeUdfpsIconSchedule.end(mSecureSettings, user()));
    }

    /** While dozing, re-check the icon when the schedule window opens or closes. */
    private void updateScheduleAlarm() {
        if (!DozeUdfpsIconSchedule.scheduleEnabled(mSecureSettings, user()) || !isIconEnabled()) {
            cancelScheduleAlarm();
            return;
        }
        final long next = DozeUdfpsIconSchedule.nextBoundary(System.currentTimeMillis(),
                DozeUdfpsIconSchedule.start(mSecureSettings, user()),
                DozeUdfpsIconSchedule.end(mSecureSettings, user()));
        // Two alarms a day at most; RTC_WAKEUP so the icon switches even while suspended.
        mAlarmManager.setExact(AlarmManager.RTC_WAKEUP, next, SCHEDULE_ALARM_TAG, mScheduleAlarm,
                mHandler);
        mScheduleAlarmSet = true;
    }

    private void cancelScheduleAlarm() {
        if (mScheduleAlarmSet) {
            mAlarmManager.cancel(mScheduleAlarm);
            mScheduleAlarmSet = false;
        }
    }

    private void onScheduleBoundary() {
        mScheduleAlarmSet = false;
        update();
        if (mMachine != null && !mMachine.isUninitializedOrFinished()) {
            updateScheduleAlarm();
        }
    }

    private void update() {
        if (mMachine == null || mMachine.isUninitializedOrFinished()) {
            return;
        }
        mDozeParameters.setUdfpsIconState(isIconWanted(), mPulsing);
    }

    @Override
    public void dump(PrintWriter pw) {
        pw.println("DozeUdfpsIconController:");
        pw.println(" enabled=" + isIconEnabled());
        pw.println(" inSchedule=" + isInSchedule());
        pw.println(" active=" + mDozeParameters.isUdfpsIconActive());
        pw.println(" pulsing=" + mPulsing);
    }
}
