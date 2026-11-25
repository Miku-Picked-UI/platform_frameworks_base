/*
 * Copyright (C) 2026 The PenguinOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.globalactions;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.systemui.res.R;

public class SlideToActView extends FrameLayout {

    public interface OnSlideCompleteListener {
        void onSlideComplete(@NonNull SlideToActView view);
    }

    private static final float COMPLETE_THRESHOLD = 0.6f;
    private static final float FLING_THRESHOLD = 0.25f;
    private static final float FLING_VELOCITY_DP_PER_S = 600f;
    private static final long SETTLE_DURATION_MS = 200L;
    private static final long SHIMMER_DURATION_MS = 2200L;

    private final FrameLayout mThumb;
    private final ImageView mThumbIcon;
    private final TextView mThumbText;
    private final TextView mLabel;

    private final int mTrackPadding;
    private final int mThumbSize;
    private final int mTouchSlop;
    private final float mFlingVelocity;

    @Nullable
    private OnSlideCompleteListener mListener;
    @Nullable
    private ValueAnimator mShimmerAnimator;
    @Nullable
    private VelocityTracker mVelocityTracker;

    private int mLabelColor = Color.WHITE;
    private int mShimmerWidth;
    private boolean mDragging;
    private boolean mCompleted;
    private float mDownX;
    private float mDownTranslation;

    public SlideToActView(Context context, AttributeSet attrs) {
        super(context, attrs);

        final Resources res = context.getResources();
        mTrackPadding = res.getDimensionPixelSize(R.dimen.power_menu_ios_track_padding);
        mThumbSize = res.getDimensionPixelSize(R.dimen.power_menu_ios_thumb_size);
        mTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        mFlingVelocity = FLING_VELOCITY_DP_PER_S * res.getDisplayMetrics().density;

        setBackgroundResource(R.drawable.power_menu_ios_track);
        setClipChildren(false);
        setClickable(true);
        setFocusable(true);

        mLabel = new TextView(context);
        mLabel.setSingleLine(true);
        mLabel.setGravity(Gravity.CENTER);
        mLabel.setTextSize(24f);
        mLabel.setPaddingRelative(mThumbSize + mTrackPadding, 0, mTrackPadding, 0);
        addView(mLabel, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));

        mThumb = new FrameLayout(context);
        mThumb.setBackgroundResource(R.drawable.power_menu_ios_thumb);

        mThumbIcon = new ImageView(context);
        mThumbIcon.setScaleType(ImageView.ScaleType.CENTER);
        mThumbIcon.setVisibility(GONE);
        mThumb.addView(mThumbIcon, new LayoutParams(LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT));

        mThumbText = new TextView(context);
        mThumbText.setGravity(Gravity.CENTER);
        mThumbText.setTextSize(22f);
        mThumbText.setTypeface(mThumbText.getTypeface(), android.graphics.Typeface.BOLD);
        mThumbText.setVisibility(GONE);
        mThumb.addView(mThumbText, new LayoutParams(LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT));

        addView(mThumb, new LayoutParams(mThumbSize, mThumbSize,
                Gravity.START | Gravity.CENTER_VERTICAL));
        mThumb.setTranslationX(0f);

        setPaddingRelative(mTrackPadding, mTrackPadding, mTrackPadding, mTrackPadding);
    }

    public void setLabel(@Nullable CharSequence label) {
        mLabel.setText(label);
        setContentDescription(label);
    }

    public void setThumbIcon(@Nullable Drawable icon) {
        mThumbIcon.setImageDrawable(icon);
        mThumbIcon.setVisibility(icon == null ? GONE : VISIBLE);
        if (icon != null) {
            mThumbText.setVisibility(GONE);
        }
    }

    public void setThumbText(@Nullable CharSequence text) {
        mThumbText.setText(text);
        mThumbText.setVisibility(text == null ? GONE : VISIBLE);
        if (text != null) {
            mThumbIcon.setVisibility(GONE);
        }
    }

    public void setColors(int trackColor, int thumbColor, int foregroundColor) {
        setBackgroundTintList(ColorStateList.valueOf(trackColor));
        mThumb.setBackgroundTintList(ColorStateList.valueOf(thumbColor));
        mThumbIcon.setImageTintList(ColorStateList.valueOf(foregroundColor));
        mThumbText.setTextColor(foregroundColor);
        mLabelColor = foregroundColor;
        mLabel.setTextColor(foregroundColor);
        restartShimmer();
    }

    public void setOnSlideCompleteListener(@Nullable OnSlideCompleteListener listener) {
        mListener = listener;
    }

    private float getMaxTravel() {
        return Math.max(0f, getWidth() - getPaddingStart() - getPaddingEnd() - mThumbSize);
    }

    private boolean isRtl() {
        return getLayoutDirection() == LAYOUT_DIRECTION_RTL;
    }

    private float getProgress() {
        final float max = getMaxTravel();
        return max == 0f ? 0f : Math.abs(mThumb.getTranslationX()) / max;
    }

    private void setTravel(float travel) {
        final float max = getMaxTravel();
        final float clamped = Math.max(0f, Math.min(max, travel));
        mThumb.setTranslationX(isRtl() ? -clamped : clamped);
        mLabel.setAlpha(1f - (max == 0f ? 0f : clamped / max));
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (mCompleted || !isEnabled()) {
            return true;
        }
        if (mVelocityTracker == null) {
            mVelocityTracker = VelocityTracker.obtain();
        }
        mVelocityTracker.addMovement(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mDownX = event.getX();
                mDownTranslation = Math.abs(mThumb.getTranslationX());
                mDragging = false;
                stopShimmer();
                disallowInterceptTouchEvent(true);
                return true;
            case MotionEvent.ACTION_MOVE: {
                final float dx = isRtl() ? mDownX - event.getX() : event.getX() - mDownX;
                if (!mDragging && Math.abs(dx) > mTouchSlop) {
                    mDragging = true;
                }
                if (mDragging) {
                    setTravel(mDownTranslation + dx);
                }
                return true;
            }
            case MotionEvent.ACTION_UP: {
                final float progress = getProgress();
                if (progress >= COMPLETE_THRESHOLD
                        || (progress >= FLING_THRESHOLD && isFlingingForward())) {
                    complete();
                } else {
                    settleBack();
                }
                releaseVelocityTracker();
                disallowInterceptTouchEvent(false);
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                settleBack();
                releaseVelocityTracker();
                disallowInterceptTouchEvent(false);
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    private boolean isFlingingForward() {
        if (mVelocityTracker == null) {
            return false;
        }
        mVelocityTracker.computeCurrentVelocity(1000);
        final float velocity = mVelocityTracker.getXVelocity();
        return isRtl() ? velocity <= -mFlingVelocity : velocity >= mFlingVelocity;
    }

    private void releaseVelocityTracker() {
        if (mVelocityTracker != null) {
            mVelocityTracker.recycle();
            mVelocityTracker = null;
        }
    }

    private void disallowInterceptTouchEvent(boolean disallow) {
        if (getParent() != null) {
            getParent().requestDisallowInterceptTouchEvent(disallow);
        }
    }

    private void settleBack() {
        mDragging = false;
        final float from = Math.abs(mThumb.getTranslationX());
        if (from == 0f) {
            restartShimmer();
            return;
        }
        final ValueAnimator animator = ValueAnimator.ofFloat(from, 0f);
        animator.setDuration(SETTLE_DURATION_MS);
        animator.addUpdateListener(a -> setTravel((float) a.getAnimatedValue()));
        animator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                restartShimmer();
            }
        });
        animator.start();
    }

    private void complete() {
        mDragging = false;
        mCompleted = true;
        setTravel(getMaxTravel());
        performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM);
        if (mListener != null) {
            mListener.onSlideComplete(this);
        }
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK);
    }

    @Override
    public boolean performAccessibilityAction(int action, android.os.Bundle arguments) {
        if (action == AccessibilityNodeInfo.ACTION_CLICK && !mCompleted) {
            complete();
            return true;
        }
        return super.performAccessibilityAction(action, arguments);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        if (mLabel.getWidth() != mShimmerWidth) {
            mShimmerWidth = mLabel.getWidth();
            restartShimmer();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        stopShimmer();
        releaseVelocityTracker();
        super.onDetachedFromWindow();
    }

    private void restartShimmer() {
        stopShimmer();
        final float width = mLabel.getWidth();
        if (width <= 0f || mLabel.length() == 0) {
            return;
        }
        final float band = width / 3f;
        final int dim = Color.argb(0x8A, Color.red(mLabelColor), Color.green(mLabelColor),
                Color.blue(mLabelColor));
        final LinearGradient gradient = new LinearGradient(0f, 0f, band, 0f,
                new int[] {dim, mLabelColor, dim}, new float[] {0f, 0.5f, 1f},
                Shader.TileMode.CLAMP);
        final Matrix matrix = new Matrix();
        mLabel.getPaint().setShader(gradient);

        mShimmerAnimator = ValueAnimator.ofFloat(-band, width + band);
        mShimmerAnimator.setDuration(SHIMMER_DURATION_MS);
        mShimmerAnimator.setRepeatCount(ValueAnimator.INFINITE);
        mShimmerAnimator.addUpdateListener(a -> {
            matrix.setTranslate((float) a.getAnimatedValue(), 0f);
            gradient.setLocalMatrix(matrix);
            mLabel.invalidate();
        });
        mShimmerAnimator.start();
    }

    private void stopShimmer() {
        if (mShimmerAnimator != null) {
            mShimmerAnimator.cancel();
            mShimmerAnimator = null;
        }
        mLabel.getPaint().setShader(null);
        mLabel.invalidate();
    }

    @Override
    protected void onVisibilityChanged(@NonNull View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility != VISIBLE) {
            stopShimmer();
        }
    }
}
