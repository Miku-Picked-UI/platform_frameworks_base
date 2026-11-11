/*
 * SPDX-FileCopyrightText: 2026 Miku-Picked UI
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.picked.nowbar

import android.content.Context
import android.view.ViewGroup
import android.view.WindowManager
import androidx.constraintlayout.widget.Barrier
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import com.android.systemui.biometrics.AuthController
import com.android.systemui.biometrics.shared.model.UdfpsOverlayParams
import com.android.systemui.keyguard.shared.model.KeyguardSection
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.res.R
import javax.inject.Inject

/**
 * Miku-Picked UI: puts the [NowBarView] on the lockscreen, placed by where the fingerprint
 * sensor is, so it never covers it:
 *  - under-display sensor in the lower part of the screen: right above the fingerprint icon;
 *  - under-display sensor higher up (around the middle): at the bottom of the screen, above the
 *    indication text;
 *  - no under-display sensor: at the bottom, above the indication text and the lock icon.
 * The lockscreen notifications end above it while it shows (see
 * NotificationStackScrollLayoutSection).
 */
class NowBarSection
@Inject
constructor(
    private val context: Context,
    private val activityStarter: ActivityStarter,
    private val activities: NowBarActivities,
    private val authController: AuthController,
) : KeyguardSection() {

    private var nowBar: NowBarView? = null
    private var barrier: Barrier? = null
    private var layout: ConstraintLayout? = null
    private var placement: Placement? = null

    private enum class Placement {
        ABOVE_FINGERPRINT,
        BOTTOM,
        BOTTOM_NO_UDFPS,
    }

    /** The sensor's location is often only known after the lockscreen is laid out. */
    private val authCallback = object : AuthController.Callback {
        override fun onAllAuthenticatorsRegistered(modality: Int) = replaceIfMoved()

        override fun onFingerprintLocationChanged() = replaceIfMoved()

        override fun onUdfpsLocationChanged(udfpsOverlayParams: UdfpsOverlayParams) =
            replaceIfMoved()
    }

    override fun addViews(constraintLayout: ConstraintLayout) {
        constraintLayout.findViewById<NowBarView?>(R.id.picked_now_bar)?.let {
            (it.parent as? ViewGroup)?.removeView(it)
        }
        constraintLayout.findViewById<Barrier?>(R.id.picked_now_bar_barrier)?.let {
            (it.parent as? ViewGroup)?.removeView(it)
        }
        barrier = Barrier(context).also {
            it.id = R.id.picked_now_bar_barrier
            constraintLayout.addView(it)
        }
        nowBar = NowBarView(context, activityStarter, activities).also {
            it.layoutParams = ConstraintLayout.LayoutParams(
                ConstraintLayout.LayoutParams.MATCH_CONSTRAINT,
                ConstraintLayout.LayoutParams.WRAP_CONTENT,
            )
            constraintLayout.addView(it)
        }
        layout = constraintLayout
        authController.addCallback(authCallback)
    }

    override fun bindData(constraintLayout: ConstraintLayout) {}

    override fun applyConstraints(constraintSet: ConstraintSet) {
        val res = context.resources
        val side = res.getDimensionPixelSize(R.dimen.picked_now_bar_side_margin)
        val bottom = res.getDimensionPixelSize(R.dimen.picked_now_bar_bottom_margin)
        val where = currentPlacement()
        placement = where
        constraintSet.apply {
            // The bar shows and hides itself (NowBarView.refresh). Without this, every
            // re-layout of the lockscreen (clock changes, blueprint transitions) re-applies the
            // visibility cloned into the ConstraintSet and hides it again.
            setVisibilityMode(R.id.picked_now_bar, ConstraintSet.VISIBILITY_MODE_IGNORE)
            constrainWidth(R.id.picked_now_bar, ConstraintSet.MATCH_CONSTRAINT)
            constrainHeight(R.id.picked_now_bar, ConstraintSet.WRAP_CONTENT)
            constrainMaxWidth(R.id.picked_now_bar,
                res.getDimensionPixelSize(R.dimen.picked_now_bar_max_width))
            connect(R.id.picked_now_bar, ConstraintSet.START, ConstraintSet.PARENT_ID,
                ConstraintSet.START, side)
            connect(R.id.picked_now_bar, ConstraintSet.END, ConstraintSet.PARENT_ID,
                ConstraintSet.END, side)
            clear(R.id.picked_now_bar, ConstraintSet.TOP)
            when (where) {
                Placement.ABOVE_FINGERPRINT ->
                    connect(R.id.picked_now_bar, ConstraintSet.BOTTOM,
                        R.id.device_entry_icon_view, ConstraintSet.TOP, bottom)
                Placement.BOTTOM -> {
                    createBarrier(R.id.picked_now_bar_barrier, Barrier.TOP, 0,
                        R.id.keyguard_indication_area)
                    connect(R.id.picked_now_bar, ConstraintSet.BOTTOM,
                        R.id.picked_now_bar_barrier, ConstraintSet.TOP, bottom)
                }
                Placement.BOTTOM_NO_UDFPS -> {
                    // The lock icon sits near the bottom here; stay above whichever is higher.
                    createBarrier(R.id.picked_now_bar_barrier, Barrier.TOP, 0,
                        R.id.keyguard_indication_area, R.id.device_entry_icon_view)
                    connect(R.id.picked_now_bar, ConstraintSet.BOTTOM,
                        R.id.picked_now_bar_barrier, ConstraintSet.TOP, bottom)
                }
            }
        }
    }

    override fun removeViews(constraintLayout: ConstraintLayout) {
        authController.removeCallback(authCallback)
        nowBar?.let { (it.parent as? ViewGroup)?.removeView(it) }
        barrier?.let { (it.parent as? ViewGroup)?.removeView(it) }
        nowBar = null
        barrier = null
        layout = null
    }

    private fun currentPlacement(): Placement {
        if (!authController.isUdfpsSupported) return Placement.BOTTOM_NO_UDFPS
        val sensor = authController.udfpsLocation ?: authController.fingerprintSensorLocation
            ?: return Placement.ABOVE_FINGERPRINT
        val bounds = context.getSystemService(WindowManager::class.java)
            ?.maximumWindowMetrics?.bounds ?: return Placement.ABOVE_FINGERPRINT
        val screenHeight = maxOf(bounds.width(), bounds.height())
        if (screenHeight <= 0) return Placement.ABOVE_FINGERPRINT
        return if (sensor.y >= screenHeight * LOWER_SENSOR_FRACTION) {
            Placement.ABOVE_FINGERPRINT
        } else {
            Placement.BOTTOM
        }
    }

    /** Re-applies the constraints when the sensor turns out to be somewhere else. */
    private fun replaceIfMoved() {
        val root = layout ?: return
        if (currentPlacement() == placement) return
        root.post {
            if (layout !== root) return@post
            ConstraintSet().apply {
                clone(root)
                applyConstraints(this)
                applyTo(root)
            }
        }
    }

    private companion object {
        /** A sensor centred below this fraction of the screen height counts as "at the bottom". */
        const val LOWER_SENSOR_FRACTION = 0.7f
    }
}
