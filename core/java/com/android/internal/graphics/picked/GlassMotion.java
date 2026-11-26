/*
 * SPDX-FileCopyrightText: 2026 Miku-Picked UI
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.internal.graphics.picked;

/**
 * The jelly motion of a Glass Layer thumb: it springs up when grabbed (overshooting a little),
 * squashes and stretches with how fast it's dragged and wobbles back when the drag slows, and
 * springs back down, bouncing, when let go. Two spring-damper systems stepped per frame.
 *
 * @hide
 */
final class GlassMotion {

    // Press: quick, a little bouncy.
    private static final float PRESS_STIFFNESS = 520f;
    private static final float PRESS_DAMPING = 0.42f;
    // Wobble: looser, so it jiggles after the finger stops.
    private static final float STRETCH_STIFFNESS = 260f;
    private static final float STRETCH_DAMPING = 0.24f;
    /** Drag speed (px/s per dp of density) that gives the full stretch. */
    private static final float FULL_SPEED_DP = 900f;
    private static final float MAX_STRETCH = 0.32f;
    private static final float SUB_STEP_S = 0.004f;
    private static final long MAX_STEP_MS = 48L;
    private static final float SETTLED = 0.0015f;

    private float mPress;
    private float mPressVelocity;
    private float mStretch;
    private float mStretchVelocity;
    private float mLastPx = Float.NaN;
    private float mSpeed;
    private long mLastAt;
    private boolean mAnimating;

    /** 0 at rest, 1 held (overshoots past 1 and below 0 on the way). */
    float press() {
        return mPress;
    }

    /** Positive = stretched along the drag, negative = squashed (the wobble). */
    float stretch() {
        return mStretch;
    }

    boolean animating() {
        return mAnimating;
    }

    /**
     * Steps the motion to now.
     *
     * @param positionPx the thumb's position along its track, in pixels
     * @param held whether the finger is on it
     */
    void update(float positionPx, boolean held, float density) {
        long now = android.os.SystemClock.uptimeMillis();
        float dt = mLastAt == 0L ? 0.016f
                : Math.max(1L, Math.min(MAX_STEP_MS, now - mLastAt)) / 1000f;
        mLastAt = now;
        float rawSpeed = Float.isNaN(mLastPx) ? 0f : Math.abs(positionPx - mLastPx) / dt;
        mLastPx = positionPx;
        // A little smoothing: touch events don't line up with frames.
        mSpeed += (rawSpeed - mSpeed) * Math.min(1f, dt / 0.03f);

        float stretchGoal = Math.min(MAX_STRETCH,
                mSpeed / (FULL_SPEED_DP * density) * MAX_STRETCH);
        float pressGoal = held || mSpeed > 30f * density ? 1f : 0f;

        float pressDamping = 2f * PRESS_DAMPING * (float) Math.sqrt(PRESS_STIFFNESS);
        float stretchDamping = 2f * STRETCH_DAMPING * (float) Math.sqrt(STRETCH_STIFFNESS);
        float left = dt;
        while (left > 0f) {
            float step = Math.min(left, SUB_STEP_S);
            mPressVelocity += (-PRESS_STIFFNESS * (mPress - pressGoal)
                    - pressDamping * mPressVelocity) * step;
            mPress += mPressVelocity * step;
            mStretchVelocity += (-STRETCH_STIFFNESS * (mStretch - stretchGoal)
                    - stretchDamping * mStretchVelocity) * step;
            mStretch += mStretchVelocity * step;
            left -= step;
        }
        boolean pressSettled = Math.abs(mPress - pressGoal) < SETTLED
                && Math.abs(mPressVelocity) < SETTLED * 10f;
        boolean stretchSettled = Math.abs(mStretch - stretchGoal) < SETTLED
                && Math.abs(mStretchVelocity) < SETTLED * 10f;
        if (pressSettled) {
            mPress = pressGoal;
            mPressVelocity = 0f;
        }
        if (stretchSettled && stretchGoal == 0f) {
            mStretch = 0f;
            mStretchVelocity = 0f;
        }
        mAnimating = !pressSettled || !stretchSettled || mSpeed > 1f;
        if (!mAnimating) mSpeed = 0f;
    }
}
