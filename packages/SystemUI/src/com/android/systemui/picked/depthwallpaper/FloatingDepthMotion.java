/*
 * Copyright (C) 2026 The PenguinOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.picked.depthwallpaper;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.util.MathUtils;
import android.view.Choreographer;
import android.view.View;
import android.view.ViewGroup;

import com.android.systemui.plugins.keyguard.ui.clocks.ClockViewIds;
import com.android.systemui.res.R;

/**
 * [PenguinOS] Floating depth wallpaper: while the wallpaper's subject stands in front of the
 * clock, the clock drifts a little against the tilt of the phone, so it seems to float between
 * the wallpaper and the subject. Miku-Picked UI also moves Miku's own clock styles (clock_ls).
 */
class FloatingDepthMotion implements SensorEventListener, Choreographer.FrameCallback {

    private static final float MAX_SHIFT_DP = 18f;
    private static final float FULL_TILT_RAD = 0.35f;
    private static final float FOLLOW = 0.18f;
    private static final float RECENTRE = 0.015f;

    private final SensorManager mSensorManager;
    private final Sensor mSensor;
    private final float mMaxShiftPx;

    private final float[] mRotation = new float[9];
    private final float[] mOrientation = new float[3];

    private ViewGroup mRoot;
    private boolean mRunning;
    private boolean mHasBaseline;
    private float mBasePitch, mBaseRoll;
    private float mTargetX, mTargetY;
    private float mX, mY;

    FloatingDepthMotion(Context context) {
        mSensorManager = context.getSystemService(SensorManager.class);
        mSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
        mMaxShiftPx = MAX_SHIFT_DP * context.getResources().getDisplayMetrics().density;
    }

    void start(ViewGroup root) {
        if (mSensor == null || root == null) return;
        mRoot = root;
        if (mRunning) return;
        mRunning = true;
        mHasBaseline = false;
        mSensorManager.registerListener(this, mSensor, SensorManager.SENSOR_DELAY_GAME);
        Choreographer.getInstance().postFrameCallback(this);
    }

    void stop() {
        if (!mRunning) return;
        mRunning = false;
        mSensorManager.unregisterListener(this);
        Choreographer.getInstance().removeFrameCallback(this);
        mX = mY = mTargetX = mTargetY = 0f;
        apply();
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        SensorManager.getRotationMatrixFromVector(mRotation, event.values);
        SensorManager.getOrientation(mRotation, mOrientation);
        float pitch = mOrientation[1];
        float roll = mOrientation[2];
        if (!mHasBaseline) {
            mBasePitch = pitch;
            mBaseRoll = roll;
            mHasBaseline = true;
        }
        // The resting angle slowly follows how the phone is held
        mBasePitch += (pitch - mBasePitch) * RECENTRE;
        mBaseRoll += (roll - mBaseRoll) * RECENTRE;
        mTargetX = -shift(roll - mBaseRoll);
        mTargetY = shift(pitch - mBasePitch);
    }

    private float shift(float tilt) {
        return MathUtils.constrain(tilt / FULL_TILT_RAD, -1f, 1f) * mMaxShiftPx;
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    @Override
    public void doFrame(long frameTimeNanos) {
        if (!mRunning) return;
        mX += (mTargetX - mX) * FOLLOW;
        mY += (mTargetY - mY) * FOLLOW;
        apply();
        Choreographer.getInstance().postFrameCallback(this);
    }

    private void apply() {
        if (mRoot == null) return;
        move(mRoot.findViewById(ClockViewIds.INSTANCE.getLOCKSCREEN_CLOCK_VIEW_LARGE()));
        move(mRoot.findViewById(ClockViewIds.INSTANCE.getLOCKSCREEN_CLOCK_VIEW_SMALL()));
        move(mRoot.findViewById(R.id.clock_ls));
    }

    private void move(View clock) {
        if (clock == null) return;
        clock.setTranslationX(mX);
        clock.setTranslationY(mY);
    }
}
