/*
 * SPDX-FileCopyrightText: 2026 Miku-Picked UI
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.clocks

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import com.android.systemui.plugins.keyguard.ui.clocks.ClockViewIds
import com.android.systemui.res.R

/**
 * Miku-Picked UI: hides the stock lockscreen clock while a custom clock style is shown.
 *
 * Setting the stock clock's alpha to 0 does not stick: the clock size transitions animate
 * it back to 1. An empty clip area is left alone by the transitions and the constraint
 * sets, so the stock clock keeps its place in the layout (smartspace, date and
 * notifications are still laid out around it) but draws nothing.
 */
object AospClockHider {

    private val CLOCK_IDS =
        intArrayOf(ClockViewIds.LOCKSCREEN_CLOCK_VIEW_LARGE, ClockViewIds.LOCKSCREEN_CLOCK_VIEW_SMALL)

    /** Hides or shows the stock clock views found under [root]. */
    @JvmStatic
    fun apply(root: ViewGroup, hide: Boolean) {
        for (id in CLOCK_IDS) {
            root.findViewById<View?>(id)?.let { view ->
                view.clipBounds = if (hide) Rect() else null
            }
        }
    }

    /**
     * For code that (re)adds the stock clock views: hides them again if the custom clock
     * under [root] is showing a style.
     */
    @JvmStatic
    fun reapply(root: ViewGroup) {
        val custom = root.findViewById<ClockStyle?>(R.id.clock_ls) ?: return
        apply(root, custom.isCustomStyleActive)
    }
}
