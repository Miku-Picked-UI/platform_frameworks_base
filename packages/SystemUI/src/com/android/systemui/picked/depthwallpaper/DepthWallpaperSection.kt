/*
 * SPDX-FileCopyrightText: 2026 Miku-Picked UI
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.picked.depthwallpaper

import android.content.Context
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.ImageView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.keyguard.shared.model.KeyguardSection
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.res.R
import java.util.concurrent.Executor
import javax.inject.Inject

/**
 * Miku-Picked UI: depth wallpaper. The subject of the lockscreen wallpaper is drawn again on top
 * of the clock, so it looks like it stands in front of it.
 *
 * Settings (Picked features > Style > Depth wallpaper) sets the lockscreen wallpaper to a
 * screen-sized photo and stores the matching screen-sized subject layer (transparent PNG), served
 * to SystemUI by Settings' PickedDepthProvider (read permission STATUS_BAR_SERVICE, direct boot
 * aware so it works before the first unlock). Both are exactly the screen size, so the layer is
 * drawn full screen with no crop and lines up with the wallpaper.
 *
 * Runs right after the clock sections, so it sits above the clock, the date and smartspace but
 * below the notifications, the lock icon and the shortcuts. Hidden while dozing (no wallpaper on
 * AOD).
 */
class DepthWallpaperSection
@Inject
constructor(
    private val context: Context,
    private val statusBarStateController: StatusBarStateController,
    @Main private val mainHandler: Handler,
    @Background private val bgExecutor: Executor,
) : KeyguardSection() {

    private var view: ImageView? = null
    private var enabled = false
    private var loadedVersion: String? = null
    private var observing = false

    private val observer = object : ContentObserver(mainHandler) {
        override fun onChange(selfChange: Boolean) = refresh()
    }

    private val stateListener = object : StatusBarStateController.StateListener {
        override fun onDozingChanged(isDozing: Boolean) = updateVisibility()
    }

    override fun addViews(constraintLayout: ConstraintLayout) {
        view = ImageView(context).apply {
            id = R.id.picked_depth_wallpaper
            scaleType = ImageView.ScaleType.FIT_XY
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            isClickable = false
            isFocusable = false
            visibility = View.GONE
        }
        constraintLayout.addView(view)
    }

    override fun bindData(constraintLayout: ConstraintLayout) {
        if (!observing) {
            val cr = context.contentResolver
            cr.registerContentObserver(Settings.Secure.getUriFor(KEY_ENABLED), false, observer,
                UserHandle.USER_ALL)
            cr.registerContentObserver(Settings.Secure.getUriFor(KEY_VERSION), false, observer,
                UserHandle.USER_ALL)
            statusBarStateController.addCallback(stateListener)
            observing = true
        }
        loadedVersion = null
        refresh()
    }

    override fun applyConstraints(constraintSet: ConstraintSet) {
        // The keyguard root view is full screen, like the wallpaper.
        constraintSet.apply {
            connect(R.id.picked_depth_wallpaper, ConstraintSet.TOP, ConstraintSet.PARENT_ID,
                ConstraintSet.TOP)
            connect(R.id.picked_depth_wallpaper, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID,
                ConstraintSet.BOTTOM)
            connect(R.id.picked_depth_wallpaper, ConstraintSet.START, ConstraintSet.PARENT_ID,
                ConstraintSet.START)
            connect(R.id.picked_depth_wallpaper, ConstraintSet.END, ConstraintSet.PARENT_ID,
                ConstraintSet.END)
            constrainWidth(R.id.picked_depth_wallpaper, ConstraintSet.MATCH_CONSTRAINT)
            constrainHeight(R.id.picked_depth_wallpaper, ConstraintSet.MATCH_CONSTRAINT)
            setVisibilityMode(R.id.picked_depth_wallpaper, ConstraintSet.VISIBILITY_MODE_IGNORE)
        }
    }

    override fun removeViews(constraintLayout: ConstraintLayout) {
        if (observing) {
            context.contentResolver.unregisterContentObserver(observer)
            statusBarStateController.removeCallback(stateListener)
            observing = false
        }
        view?.let(constraintLayout::removeView)
        view = null
    }

    private fun refresh() {
        val cr = context.contentResolver
        enabled = Settings.Secure.getIntForUser(cr, KEY_ENABLED, 0, UserHandle.USER_CURRENT) == 1
        val version = Settings.Secure.getStringForUser(cr, KEY_VERSION, UserHandle.USER_CURRENT)
        if (!enabled) {
            view?.setImageDrawable(null)
            loadedVersion = null
            updateVisibility()
            return
        }
        if (version == loadedVersion && view?.drawable != null) {
            updateVisibility()
            return
        }
        loadedVersion = version
        bgExecutor.execute {
            val bitmap = load()
            mainHandler.post {
                if (loadedVersion != version) return@post
                view?.setImageBitmap(bitmap)
                updateVisibility()
            }
        }
    }

    private fun load(): Bitmap? =
        try {
            context.contentResolver.openFileDescriptor(SUBJECT_URI, "r")?.use {
                BitmapFactory.decodeFileDescriptor(it.fileDescriptor)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cannot load the depth wallpaper subject", e)
            null
        }

    private fun updateVisibility() {
        val v = view ?: return
        val show = enabled && v.drawable != null && !statusBarStateController.isDozing
        v.visibility = if (show) View.VISIBLE else View.GONE
    }

    companion object {
        private const val TAG = "DepthWallpaper"
        const val KEY_ENABLED = "picked_depth_wallpaper_enabled"
        const val KEY_VERSION = "picked_depth_wallpaper_version"
        val SUBJECT_URI: Uri = Uri.parse("content://com.miku.picked.depthwallpaper/subject.png")
    }
}
