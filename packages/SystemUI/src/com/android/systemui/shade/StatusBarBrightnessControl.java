/*
 * SPDX-FileCopyrightText: 2013-2025 The CyanogenMod Project, crDroid Android, Evolution X
 * SPDX-FileCopyrightText: 2026 Miku-Picked UI
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.shade;

import static com.android.settingslib.display.BrightnessUtils.GAMMA_SPACE_MAX;
import static com.android.settingslib.display.BrightnessUtils.convertGammaToLinearFloat;
import static com.android.settingslib.display.BrightnessUtils.convertLinearToGammaFloat;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

/**
 * Status bar brightness control (CyanogenMod, via crDroid and Evolution X): slide a finger
 * sideways along the status bar, or long-press it, to change the screen brightness. The
 * long-press start is skipped when AOSP's "long-press the status bar to expand the shade"
 * is on, since that gesture comes first.
 *
 * The slide is relative to the brightness at touch-down (Evolution X), in the same gamma space
 * as the Quick Settings slider. While sliding the brightness is only previewed
 * (setTemporaryBrightness); it is committed when the finger lifts.
 */
final class StatusBarBrightnessControl {

    private static final int LONG_PRESS_TIMEOUT_MS = 750;
    // Sideways moves needed before a slide counts as a brightness gesture.
    private static final int LINGER_THRESHOLD = 20;

    private final Context mContext;
    private final View mHapticView;
    private final DisplayManager mDisplayManager;
    private final float mMinimum;
    private final float mMaximum;
    private final int mTouchSlop;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mLongPress = this::onLongPress;
    private final boolean mLongPressEnabled;

    private int mInitialTouchX;
    private int mInitialTouchY;
    private int mLinger;
    private boolean mTracking;
    private boolean mActive;
    private boolean mChanged;
    private float mBrightnessAtTouchStart;
    private float mCurrent;

    StatusBarBrightnessControl(Context context, View hapticView, boolean longPressEnabled) {
        mContext = context;
        mLongPressEnabled = longPressEnabled;
        mHapticView = hapticView;
        mDisplayManager = context.getSystemService(DisplayManager.class);
        final PowerManager pm = context.getSystemService(PowerManager.class);
        mMinimum = pm.getBrightnessConstraint(PowerManager.BRIGHTNESS_CONSTRAINT_TYPE_MINIMUM);
        mMaximum = pm.getBrightnessConstraint(PowerManager.BRIGHTNESS_CONSTRAINT_TYPE_MAXIMUM);
        mTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    /** Whether the current touch has turned into a brightness gesture. */
    boolean isActive() {
        return mActive;
    }

    /**
     * Feeds a shade touch event.
     *
     * @param inStatusBar whether the touch is inside the status bar strip
     * @return true if the event belongs to a brightness gesture and the shade must not use it
     */
    boolean onTouchEvent(MotionEvent event, boolean inStatusBar) {
        final int x = (int) event.getRawX();
        final int y = (int) event.getRawY();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                reset();
                if (!inStatusBar) return false;
                mTracking = true;
                mInitialTouchX = x;
                mInitialTouchY = y;
                mBrightnessAtTouchStart = mDisplayManager.getBrightness(mContext.getDisplayId());
                if (Float.isNaN(mBrightnessAtTouchStart)) mBrightnessAtTouchStart = mMaximum;
                mCurrent = mBrightnessAtTouchStart;
                if (mLongPressEnabled) {
                    mHandler.postDelayed(mLongPress, LONG_PRESS_TIMEOUT_MS);
                }
                return false;
            case MotionEvent.ACTION_MOVE:
                if (!mTracking) return false;
                if (mActive) {
                    adjust(x);
                    return true;
                }
                if (!inStatusBar) {
                    // Pulled down out of the bar: an ordinary shade pull, stop watching.
                    cancel();
                    return false;
                }
                final int xDiff = Math.abs(x - mInitialTouchX);
                final int yDiff = Math.abs(y - mInitialTouchY);
                if (xDiff > mTouchSlop || yDiff > mTouchSlop) {
                    mHandler.removeCallbacks(mLongPress);
                }
                if (yDiff > xDiff && yDiff > mTouchSlop) {
                    cancel();
                    return false;
                }
                if (xDiff > yDiff && ++mLinger > LINGER_THRESHOLD) {
                    mActive = true;
                    adjust(x);
                    return true;
                }
                return false;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                final boolean consumed = mActive;
                if (mChanged) {
                    mDisplayManager.setBrightness(mContext.getDisplayId(), mCurrent);
                }
                reset();
                return consumed;
            default:
                return mActive;
        }
    }

    /** Stops tracking the current touch (e.g. Quick Settings took it). */
    void cancel() {
        if (mChanged) {
            mDisplayManager.setBrightness(mContext.getDisplayId(), mCurrent);
        }
        reset();
    }

    private void reset() {
        mHandler.removeCallbacks(mLongPress);
        mTracking = false;
        mActive = false;
        mChanged = false;
        mLinger = 0;
    }

    private void onLongPress() {
        if (!mTracking) return;
        mHapticView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        mActive = true;
    }

    private void adjust(int x) {
        final int width = mContext.getResources().getDisplayMetrics().widthPixels;
        if (width <= 0) return;
        final float delta = ((float) (x - mInitialTouchX)) / width;
        final float startGamma = convertLinearToGammaFloat(mBrightnessAtTouchStart,
                mMinimum, mMaximum) / (float) GAMMA_SPACE_MAX;
        final float gamma = Math.min(1f, Math.max(0f, startGamma + delta));
        final float value = convertGammaToLinearFloat(Math.round(gamma * GAMMA_SPACE_MAX),
                mMinimum, mMaximum);
        if (value == mCurrent && mChanged) return;
        mCurrent = value;
        mChanged = true;
        mDisplayManager.setTemporaryBrightness(mContext.getDisplayId(), value);
    }
}
