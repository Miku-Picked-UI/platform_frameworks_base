/*
 * SPDX-FileCopyrightText: 2026 Miku-Picked UI
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.picked.nowbar

import android.content.Context
import android.view.ViewGroup
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import com.android.systemui.keyguard.shared.model.KeyguardSection
import com.android.systemui.res.R
import javax.inject.Inject

/**
 * Miku-Picked UI: puts the [NowBarView] at the bottom of the lockscreen, above the device
 * entry (fingerprint / lock) icon. The lockscreen notifications end above it while it shows
 * (see NotificationStackScrollLayoutSection).
 */
class NowBarSection @Inject constructor(private val context: Context) : KeyguardSection() {

    private var nowBar: NowBarView? = null

    override fun addViews(constraintLayout: ConstraintLayout) {
        constraintLayout.findViewById<NowBarView?>(R.id.picked_now_bar)?.let {
            (it.parent as? ViewGroup)?.removeView(it)
        }
        nowBar = NowBarView(context).also {
            it.layoutParams = ConstraintLayout.LayoutParams(
                ConstraintLayout.LayoutParams.MATCH_CONSTRAINT,
                ConstraintLayout.LayoutParams.WRAP_CONTENT,
            )
            constraintLayout.addView(it)
        }
    }

    override fun bindData(constraintLayout: ConstraintLayout) {}

    override fun applyConstraints(constraintSet: ConstraintSet) {
        val side = context.resources.getDimensionPixelSize(R.dimen.picked_now_bar_side_margin)
        val bottom = context.resources.getDimensionPixelSize(R.dimen.picked_now_bar_bottom_margin)
        constraintSet.apply {
            constrainWidth(R.id.picked_now_bar, ConstraintSet.MATCH_CONSTRAINT)
            constrainHeight(R.id.picked_now_bar, ConstraintSet.WRAP_CONTENT)
            constrainMaxWidth(R.id.picked_now_bar,
                context.resources.getDimensionPixelSize(R.dimen.picked_now_bar_max_width))
            connect(R.id.picked_now_bar, ConstraintSet.START, ConstraintSet.PARENT_ID,
                ConstraintSet.START, side)
            connect(R.id.picked_now_bar, ConstraintSet.END, ConstraintSet.PARENT_ID,
                ConstraintSet.END, side)
            connect(R.id.picked_now_bar, ConstraintSet.BOTTOM, R.id.device_entry_icon_view,
                ConstraintSet.TOP, bottom)
        }
    }

    override fun removeViews(constraintLayout: ConstraintLayout) {
        nowBar?.let { (it.parent as? ViewGroup)?.removeView(it) }
        nowBar = null
    }
}
