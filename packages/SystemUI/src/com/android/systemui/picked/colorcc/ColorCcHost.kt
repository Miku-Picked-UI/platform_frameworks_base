/*
 * SPDX-FileCopyrightText: 2026 Miku-Picked UI
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.picked.colorcc

import android.content.ComponentName
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.android.systemui.animation.ActivityTransitionAnimator
import com.android.systemui.animation.DialogCuj
import com.android.systemui.animation.DialogTransitionAnimator
import com.android.systemui.animation.Expandable
import com.android.systemui.qs.QSHost
import com.android.systemui.qs.composefragment.viewmodel.QSFragmentComposeViewModel
import com.android.systemui.statusbar.connectivity.AccessPointController
import dev.colorcc.Hook

/**
 * [Miku-Picked UI] Hosts ColorCC (by Guest, ported from its LSPosed module for One UI) as the
 * Quick Settings panel style "ColorCC" (Settings.Secure qs_panel_style = 7).
 *
 * ColorCC is a View tree that draws the whole expanded control centre. [container] sits over
 * QSFragmentCompose's ComposeView, which keeps drawing Quick Quick Settings and leaves its
 * expanded scene empty while this style is on. The host answers what the module used to read
 * out of One UI's SystemUI by reflection.
 */
class ColorCcHost(
    private val container: FrameLayout,
    private val viewModel: QSFragmentComposeViewModel,
    private val qsHost: QSHost,
    private val accessPointController: AccessPointController,
) : Hook.Bridge {

    /** Whether ColorCC currently draws the expanded panel (compose state). */
    var active by mutableStateOf(false)
        private set

    private var panel: View? = null

    /** Shows ColorCC when [wanted] (the chosen style), the stock panel otherwise. */
    fun setWanted(wanted: Boolean) {
        if (wanted && panel == null) {
            panel = Hook.attach(container, this)
            if (panel == null) Log.w(TAG, "ColorCC not available, the stock panel stays")
        } else if (!wanted && panel != null) {
            Hook.detach(container)
            panel = null
        }
        active = panel != null
        ColorCcSwitch.active = active
        if (!active) ColorCcHeaderCover.panel = 0f
    }

    /** The control centre moved (QQS to QS, or the whole shade). */
    fun onExpansionChanged() {
        if (!active) return
        // The stock header goes as soon as QS (not QQS) is what opens: on a direct pull to QS the
        // QS progress is already 1, so it never shows; QQS -> QS fades it with the progress.
        ColorCcHeaderCover.panel =
            if (viewModel.isQsVisibleAndAnyShadeExpanded) qsProgress() else 0f
        Hook.onShadeExpansion(expansion())
    }

    fun canScroll(down: Boolean): Boolean = active && Hook.canScrollCards(down)

    override fun qsHost(): Any = qsHost

    override fun accessPointController(): Any = accessPointController

    /**
     * How far ColorCC is open, following the finger. On One UI this was one fraction for the
     * whole pull. Here the QQS -> QS progress is already 1 when the shade is pulled straight to
     * QS (and only the shade's own fraction moves), so the panel got 1 at the first touch and
     * popped in with its canned open animation instead of cascading in under the finger. The
     * product moves with whichever of the two is moving.
     */
    override fun expansion(): Float =
        if (ColorCcSwitch.expansion >= 0f) {
            // The split shade is switching to or from QS: open or close along the switch.
            ColorCcSwitch.expansion
        } else if (viewModel.isQsVisibleAndAnyShadeExpanded) {
            qsProgress() * viewModel.panelExpansionFraction.coerceIn(0f, 1f)
        } else {
            0f
        }

    private fun qsProgress(): Float = viewModel.expansionState.progress.coerceIn(0f, 1f)

    override fun stockScreen(): Boolean = viewModel.isEditing

    override fun openPowerMenu() {
        // Not Expandable.fromView(container): the dialog animator refuses to launch from a view
        // that is not a LaunchableView (IllegalArgumentException, so the button did nothing).
        // ColorCC's buttons are plain views, so the power menu opens without the launch animation.
        viewModel.footerActionsViewModel.power?.onClick(NoLaunchAnimation)
    }

    override fun onPanelGone() {
        // Panel.die(): back to the stock panel until the style is chosen again.
        container.post {
            Hook.detach(container)
            panel = null
            active = false
            ColorCcSwitch.active = false
            ColorCcHeaderCover.panel = 0f
        }
    }

    private object NoLaunchAnimation : Expandable {
        override fun activityTransitionController(
            launchCujType: Int?,
            cookie: ActivityTransitionAnimator.TransitionCookie?,
            component: ComponentName?,
            returnCujType: Int?,
            isEphemeral: Boolean,
        ): ActivityTransitionAnimator.Controller? = null

        override fun dialogTransitionController(
            cuj: DialogCuj?
        ): DialogTransitionAnimator.Controller? = null
    }

    private companion object {
        const val TAG = "ColorCcHost"
    }
}
