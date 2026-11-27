/*
 * SPDX-FileCopyrightText: 2026 DrDisagree, Miku-Picked UI
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Ported from Glass Layer (github.com/Mahmud0808/GlassLayer, GPL-3.0): ViewSwitchMotion, in Java.
 */

package com.android.internal.graphics.picked;

import android.os.SystemClock;

/**
 * The motion of a Glass Layer thumb, as Glass Layer does it: grabbed or moving, it eases up into
 * the lens (70 ms); its speed stretches it along the track; let go and still, it eases back down.
 *
 * @hide
 */
final class GlassMotion {

    private static final float FRAME_MS = 16f;
    private static final float RESPONSE_MS = 70f;
    /** Movement per frame (of the 0..1 position) that counts as moving. */
    private static final float MOVING = 0.002f;
    private static final float SETTLED = 0.01f;
    /** Stretch per unit of velocity (KyantThumb.STRETCH). */
    private static final float STRETCH = 0.6f;

    private float mPress;
    private float mVelocity;
    private float mLastPosition = Float.NaN;
    private long mLastAt;
    private boolean mAnimating;

    /** 0 at rest (white thumb), 1 held (glass lens). */
    float press() {
        return mPress;
    }

    /** How much the thumb stretches along the track, before the caller's cap. */
    float stretch() {
        return Math.abs(mVelocity) * STRETCH;
    }

    boolean animating() {
        return mAnimating;
    }

    /**
     * Steps the motion to now.
     *
     * @param position the thumb's position along its track, 0..1
     * @param held whether the finger is on it
     */
    void update(float position, boolean held) {
        long now = SystemClock.uptimeMillis();
        float elapsed = mLastAt == 0L ? FRAME_MS : Math.max(1L, now - mLastAt);
        mVelocity = Float.isNaN(mLastPosition) ? 0f
                : (position - mLastPosition) * FRAME_MS / elapsed;
        mLastPosition = position;
        mLastAt = now;
        boolean moving = Math.abs(mVelocity) > MOVING;
        float goal = held || moving ? 1f : 0f;
        mPress += (goal - mPress) * (1f - (float) Math.exp(-elapsed / RESPONSE_MS));
        if (Math.abs(goal - mPress) < SETTLED) mPress = goal;
        mAnimating = mPress != goal || moving;
    }
}
