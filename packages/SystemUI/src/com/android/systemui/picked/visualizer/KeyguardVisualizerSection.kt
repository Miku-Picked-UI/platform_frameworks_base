/*
 * SPDX-FileCopyrightText: 2026 Miku-Picked UI
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.picked.visualizer

import android.content.Context
import android.database.ContentObserver
import android.graphics.Color
import android.media.MediaMetadata
import android.os.Handler
import android.os.UserHandle
import android.provider.Settings
import android.view.View
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.keyguard.shared.model.KeyguardSection
import com.android.systemui.media.NotificationMediaManager
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.res.R
import javax.inject.Inject

/**
 * Miku-Picked UI: lockscreen music visualizer (#11).
 *
 * Hosts a [LockscreenVisualizerView] across the bottom of the lockscreen, just above the
 * indication area. The audio tap only runs while the toggle is on, something is playing, and the
 * screen is on (not dozing); it is released otherwise to protect battery.
 *
 * Toggle: Settings.Secure "picked_lockscreen_visualizer" (0/1).
 */
class KeyguardVisualizerSection
@Inject
constructor(
    private val context: Context,
    private val mediaManager: NotificationMediaManager,
    private val statusBarStateController: StatusBarStateController,
    @Main private val mainHandler: Handler,
) : KeyguardSection() {

    private var view: LockscreenVisualizerView? = null
    private var layout: ConstraintLayout? = null
    private var enabled = false
    private var playing = false
    private var observing = false

    private val observer = object : ContentObserver(mainHandler) {
        override fun onChange(selfChange: Boolean) = refresh()
    }

    private val stateListener = object : StatusBarStateController.StateListener {
        override fun onDozingChanged(isDozing: Boolean) = update()
    }

    private val mediaListener = object : NotificationMediaManager.MediaListener {
        override fun onPrimaryMetadataOrStateChanged(metadata: MediaMetadata?, state: Int) {
            playing = NotificationMediaManager.isPlayingState(state)
            update()
        }
    }

    override fun addViews(constraintLayout: ConstraintLayout) {
        view = LockscreenVisualizerView(context).apply {
            id = R.id.picked_lockscreen_visualizer
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            isClickable = false
            isFocusable = false
            visibility = View.GONE
            setColor(accentColor())
        }
        constraintLayout.addView(view)
        layout = constraintLayout
    }

    override fun bindData(constraintLayout: ConstraintLayout) {
        if (!observing) {
            context.contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(KEY_ENABLED), false, observer, UserHandle.USER_ALL)
            statusBarStateController.addCallback(stateListener)
            mediaManager.addCallback(mediaListener)
            observing = true
        }
        refresh()
    }

    override fun applyConstraints(constraintSet: ConstraintSet) {
        val height = (64 * context.resources.displayMetrics.density).toInt()
        val margin = (32 * context.resources.displayMetrics.density).toInt()
        val gap = (8 * context.resources.displayMetrics.density).toInt()
        constraintSet.apply {
            constrainWidth(R.id.picked_lockscreen_visualizer, ConstraintSet.MATCH_CONSTRAINT)
            constrainHeight(R.id.picked_lockscreen_visualizer, height)
            connect(R.id.picked_lockscreen_visualizer, ConstraintSet.START,
                ConstraintSet.PARENT_ID, ConstraintSet.START, margin)
            connect(R.id.picked_lockscreen_visualizer, ConstraintSet.END,
                ConstraintSet.PARENT_ID, ConstraintSet.END, margin)
            // The Now Bar sits right above the indication area while music plays; sit on top of it
            // so the bars are not hidden behind its card.
            if (layout?.findViewById<View?>(R.id.picked_now_bar) != null) {
                connect(R.id.picked_lockscreen_visualizer, ConstraintSet.BOTTOM,
                    R.id.picked_now_bar, ConstraintSet.TOP, gap)
            } else {
                connect(R.id.picked_lockscreen_visualizer, ConstraintSet.BOTTOM,
                    R.id.keyguard_indication_area, ConstraintSet.TOP)
            }
            setVisibilityMode(R.id.picked_lockscreen_visualizer, ConstraintSet.VISIBILITY_MODE_IGNORE)
        }
    }

    override fun removeViews(constraintLayout: ConstraintLayout) {
        if (observing) {
            context.contentResolver.unregisterContentObserver(observer)
            statusBarStateController.removeCallback(stateListener)
            mediaManager.removeCallback(mediaListener)
            observing = false
        }
        view?.setActive(false)
        view?.let(constraintLayout::removeView)
        view = null
        layout = null
    }

    private fun refresh() {
        enabled = Settings.Secure.getIntForUser(
            context.contentResolver, KEY_ENABLED, 0, UserHandle.USER_CURRENT) == 1
        // `playing` is kept current by mediaListener, which addCallback() fires immediately on bind.
        update()
    }

    private fun update() {
        val v = view ?: return
        val show = enabled && playing && !statusBarStateController.isDozing
        v.setActive(show)
        v.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun accentColor(): Int =
        try {
            (context.getColor(android.R.color.system_accent1_200) and 0x00FFFFFF) or 0xD9000000.toInt()
        } catch (e: Exception) {
            Color.WHITE
        }

    companion object {
        const val KEY_ENABLED = "picked_lockscreen_visualizer"
    }
}
