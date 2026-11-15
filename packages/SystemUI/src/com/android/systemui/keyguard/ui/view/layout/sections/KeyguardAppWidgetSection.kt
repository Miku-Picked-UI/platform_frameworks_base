/*
 * Copyright (C) 2025 the RisingOS Revived Android Project
 * Copyright (C) 2026 Lunaris-AOSP
 * SPDX-FileCopyrightText: 2026 Miku-Picked UI
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.keyguard.ui.view.layout.sections

import android.content.Context
import android.view.View
import android.view.ViewTreeObserver
import androidx.constraintlayout.widget.Barrier
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import com.android.systemui.communal.domain.interactor.WidgetTrampolineInteractor
import com.android.systemui.keyguard.WakefulnessLifecycle
import com.android.systemui.keyguard.shared.model.KeyguardSection
import com.android.systemui.lockscreen.KeyguardWidgetHostController
import com.android.systemui.lockscreen.WidgetViewport
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.res.R
import com.android.systemui.settings.UserTracker
import com.android.systemui.statusbar.phone.SystemUIDialogFactory
import com.android.systemui.statusbar.policy.KeyguardStateController
import javax.inject.Inject
import com.android.systemui.shared.R as SharedR

/**
 * [Lunaris AOSP] App widgets on the lockscreen, below the clock and smartspace and above the
 * notifications. Adapted from Lunaris' KeyguardWidgetViewSection without its quick-action widget
 * row; on Miku-Picked UI the clock is the Evolution X clock (clock_ls), so this section runs after
 * KeyguardClockStyleSection and widens smart_space_barrier_bottom (which the notifications hang
 * below) to include the widgets.
 */
class KeyguardAppWidgetSection @Inject constructor(
    private val context: Context,
    private val activityStarter: ActivityStarter,
    private val falsingManager: FalsingManager,
    private val userTracker: UserTracker,
    private val keyguardStateController: KeyguardStateController,
    private val trampolineInteractor: WidgetTrampolineInteractor,
    private val dialogFactory: SystemUIDialogFactory,
    private val statusBarStateController: StatusBarStateController,
    private val wakefulnessLifecycle: WakefulnessLifecycle,
) : KeyguardSection() {
    private var appWidgets: WidgetViewport? = null
    private var controller: KeyguardWidgetHostController? = null
    private var layoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null

    private val contentIds = intArrayOf(
        R.id.clock_ls,
        R.id.keyguard_slice_view,
        SharedR.id.bc_smartspace_view,
        SharedR.id.date_smartspace_view,
    )

    override fun addViews(constraintLayout: ConstraintLayout) {
        appWidgets = WidgetViewport(context).apply {
            id = R.id.keyguard_app_widgets
            visibility = View.GONE
            val padding = (16 * resources.displayMetrics.density).toInt()
            setPadding(padding, 0, padding, 0)
            clipToPadding = true
        }
        constraintLayout.addView(appWidgets)
    }

    override fun bindData(constraintLayout: ConstraintLayout) {
        val viewport = appWidgets ?: return
        controller = KeyguardWidgetHostController(context, viewport, activityStarter,
            falsingManager, userTracker, keyguardStateController, trampolineInteractor,
            dialogFactory, statusBarStateController, wakefulnessLifecycle).also { it.attach() }
        // Keep the widgets clear of the lock icon, the indication area and the shortcuts.
        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            if (viewport.visibility != View.VISIBLE) return@OnGlobalLayoutListener
            val reserved = (96 * context.resources.displayMetrics.density).toInt()
            val limit = listOf(R.id.device_entry_icon_view, R.id.keyguard_indication_area,
                R.id.start_button, R.id.picked_now_bar, R.id.ambient_indication_container)
                .mapNotNull { constraintLayout.findViewById<View>(it) }
                .filter { it.visibility != View.GONE && it.top > 0 }
                .minOfOrNull { it.top } ?: (constraintLayout.height - reserved)
            viewport.maximumHeight = minOf((constraintLayout.height * 0.45f).toInt(),
                limit - viewport.top - reserved).coerceAtLeast(0)
        }
        layoutListener = listener
        constraintLayout.viewTreeObserver.addOnGlobalLayoutListener(listener)
    }

    override fun applyConstraints(constraintSet: ConstraintSet) {
        val splitShade = context.resources.getBoolean(R.bool.config_use_split_notification_shade)
        val gap = (8 * context.resources.displayMetrics.density).toInt()
        constraintSet.apply {
            createBarrier(R.id.keyguard_app_widget_anchor, Barrier.BOTTOM, 0, *contentIds)
            getConstraint(R.id.keyguard_app_widget_anchor).layout.mBarrierAllowsGoneWidgets = false
            connect(R.id.keyguard_app_widgets, ConstraintSet.TOP, R.id.keyguard_app_widget_anchor,
                ConstraintSet.BOTTOM, gap)
            connect(R.id.keyguard_app_widgets, ConstraintSet.START, ConstraintSet.PARENT_ID,
                ConstraintSet.START)
            connect(R.id.keyguard_app_widgets, ConstraintSet.END,
                if (splitShade) R.id.split_shade_guideline else ConstraintSet.PARENT_ID,
                ConstraintSet.END)
            constrainWidth(R.id.keyguard_app_widgets, ConstraintSet.MATCH_CONSTRAINT)
            constrainHeight(R.id.keyguard_app_widgets, ConstraintSet.WRAP_CONTENT)
            constrainMaxWidth(R.id.keyguard_app_widgets,
                context.resources.getDimensionPixelSize(R.dimen.shade_panel_width))
            // The controller shows and hides the widgets itself.
            setVisibilityMode(R.id.keyguard_app_widgets, ConstraintSet.VISIBILITY_MODE_IGNORE)
            createBarrier(R.id.smart_space_barrier_bottom, Barrier.BOTTOM, 0,
                *(contentIds + R.id.keyguard_app_widgets))
            getConstraint(R.id.smart_space_barrier_bottom).layout.mBarrierAllowsGoneWidgets = false
        }
    }

    override fun removeViews(constraintLayout: ConstraintLayout) {
        layoutListener?.let { constraintLayout.viewTreeObserver.removeOnGlobalLayoutListener(it) }
        layoutListener = null
        controller?.detach()
        controller = null
        appWidgets?.let(constraintLayout::removeView)
        constraintLayout.findViewById<View>(R.id.keyguard_app_widget_anchor)
            ?.let(constraintLayout::removeView)
        appWidgets = null
    }
}
