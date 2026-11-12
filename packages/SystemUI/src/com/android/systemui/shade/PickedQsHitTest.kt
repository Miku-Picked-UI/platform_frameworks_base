/*
 * SPDX-FileCopyrightText: 2026 Miku-Picked UI
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.shade

import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import kotlin.math.hypot

/**
 * Miku-Picked UI: whether a point is on, or close to, something in Quick Settings that a swipe
 * could be meant for (a tile, button, slider, pager...). The sideways swipe back to the
 * notifications only starts on empty space, so it never fights a control.
 *
 * Quick Settings is Compose, which has no child views to test, so its semantics tree gives the
 * bounds of every clickable, scrollable and adjustable element. Plain views (if any) count when
 * they are clickable.
 */
object PickedQsHitTest {

    /** True when ([x], [y]) in [root]'s window lies within [radius] px of a control. */
    @JvmStatic
    fun isNearControl(root: View?, x: Float, y: Float, radius: Float): Boolean {
        if (root == null || !root.isShown) return false
        val rects = ArrayList<RectF>()
        collect(root, rects)
        return rects.any { distance(it, x, y) <= radius }
    }

    private fun collect(view: View, out: MutableList<RectF>) {
        if (!view.isShown) return
        if (view is ViewRootForTest) {
            semantics(view.semanticsOwner.unmergedRootSemanticsNode, out)
            return
        }
        if (view.isClickable || view.isLongClickable) {
            val loc = IntArray(2)
            view.getLocationInWindow(loc)
            out +=
                RectF(
                    loc[0].toFloat(),
                    loc[1].toFloat(),
                    (loc[0] + view.width).toFloat(),
                    (loc[1] + view.height).toFloat(),
                )
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) collect(view.getChildAt(i), out)
        }
    }

    private fun semantics(node: SemanticsNode, out: MutableList<RectF>) {
        val config = node.config
        if (
            SemanticsActions.OnClick in config ||
                SemanticsActions.OnLongClick in config ||
                SemanticsActions.ScrollBy in config ||
                SemanticsActions.SetProgress in config
        ) {
            val b = node.boundsInWindow
            // A scrollable container (the whole panel scrolls) would cover everything; only
            // count the things inside it.
            if (SemanticsActions.ScrollBy !in config || node.children.isEmpty()) {
                out += RectF(b.left, b.top, b.right, b.bottom)
            }
        }
        node.children.forEach { semantics(it, out) }
    }

    private fun distance(r: RectF, x: Float, y: Float): Float {
        val dx = maxOf(r.left - x, 0f, x - r.right)
        val dy = maxOf(r.top - y, 0f, y - r.bottom)
        return hypot(dx, dy)
    }
}
