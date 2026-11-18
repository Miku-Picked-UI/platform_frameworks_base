/*
 * SPDX-FileCopyrightText: 2026 Miku-Picked UI
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.picked.colorcc

import android.util.Log
import android.view.View
import android.widget.FrameLayout
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
    }

    /** The control centre moved (QQS to QS, or the whole shade). */
    fun onExpansionChanged() {
        if (active) Hook.onShadeExpansion(expansion())
    }

    fun canScroll(down: Boolean): Boolean = active && Hook.canScrollCards(down)

    override fun qsHost(): Any = qsHost

    override fun accessPointController(): Any = accessPointController

    override fun expansion(): Float =
        if (viewModel.isQsVisibleAndAnyShadeExpanded) {
            viewModel.expansionState.progress.coerceIn(0f, 1f)
        } else {
            0f
        }

    override fun stockScreen(): Boolean = viewModel.isEditing

    override fun openPowerMenu() {
        viewModel.footerActionsViewModel.power?.let { it.onClick(Expandable.fromView(container)) }
    }

    override fun onPanelGone() {
        // Panel.die(): back to the stock panel until the style is chosen again.
        container.post {
            Hook.detach(container)
            panel = null
            active = false
        }
    }

    private companion object {
        const val TAG = "ColorCcHost"
    }
}
