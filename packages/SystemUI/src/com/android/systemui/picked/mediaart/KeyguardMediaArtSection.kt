/*
 * SPDX-FileCopyrightText: 2026 Miku-Picked UI
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.picked.mediaart

import android.content.Context
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.RenderEffect
import android.graphics.Shader
import android.media.MediaMetadata
import android.os.Handler
import android.os.UserHandle
import android.provider.Settings
import android.view.View
import android.widget.ImageView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.keyguard.shared.model.KeyguardSection
import com.android.systemui.media.NotificationMediaManager
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.res.R
import java.util.concurrent.Executor
import javax.inject.Inject

/**
 * Miku-Picked UI: lockscreen media art background (#12).
 *
 * While audio is playing, the current track's album art is drawn blurred and darkened as the
 * lockscreen background, replacing the wallpaper. Reacts to [NotificationMediaManager]; the blur
 * is a static [RenderEffect] so it is cheap. Added first in the blueprint so it sits at the very
 * back, behind the clock, notifications and everything else. Hidden while dozing (no art on AOD),
 * when nothing is playing, or when the toggle is off (then the normal wallpaper shows through).
 *
 * Toggle: Settings.Secure "picked_lockscreen_media_art" (0/1).
 */
class KeyguardMediaArtSection
@Inject
constructor(
    private val context: Context,
    private val mediaManager: NotificationMediaManager,
    private val statusBarStateController: StatusBarStateController,
    @Main private val mainHandler: Handler,
    @Background private val bgExecutor: Executor,
) : KeyguardSection() {

    private var view: ImageView? = null
    private var enabled = false
    private var playing = false
    private var observing = false

    private val observer = object : ContentObserver(mainHandler) {
        override fun onChange(selfChange: Boolean) = refresh()
    }

    private val stateListener = object : StatusBarStateController.StateListener {
        override fun onDozingChanged(isDozing: Boolean) = updateVisibility()
    }

    private val mediaListener = object : NotificationMediaManager.MediaListener {
        override fun onPrimaryMetadataOrStateChanged(metadata: MediaMetadata?, state: Int) {
            playing = NotificationMediaManager.isPlayingState(state)
            onMedia(metadata)
        }
    }

    override fun addViews(constraintLayout: ConstraintLayout) {
        view = ImageView(context).apply {
            id = R.id.picked_media_art_bg
            scaleType = ImageView.ScaleType.CENTER_CROP
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            isClickable = false
            isFocusable = false
            visibility = View.GONE
            // Darken for legibility of the clock and notifications on top.
            setColorFilter(Color.argb(120, 0, 0, 0), PorterDuff.Mode.SRC_ATOP)
            setRenderEffect(
                RenderEffect.createBlurEffect(70f, 70f, Shader.TileMode.CLAMP)
            )
        }
        constraintLayout.addView(view)
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
        constraintSet.apply {
            connect(R.id.picked_media_art_bg, ConstraintSet.TOP, ConstraintSet.PARENT_ID,
                ConstraintSet.TOP)
            connect(R.id.picked_media_art_bg, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID,
                ConstraintSet.BOTTOM)
            connect(R.id.picked_media_art_bg, ConstraintSet.START, ConstraintSet.PARENT_ID,
                ConstraintSet.START)
            connect(R.id.picked_media_art_bg, ConstraintSet.END, ConstraintSet.PARENT_ID,
                ConstraintSet.END)
            constrainWidth(R.id.picked_media_art_bg, ConstraintSet.MATCH_CONSTRAINT)
            constrainHeight(R.id.picked_media_art_bg, ConstraintSet.MATCH_CONSTRAINT)
            setVisibilityMode(R.id.picked_media_art_bg, ConstraintSet.VISIBILITY_MODE_IGNORE)
        }
    }

    override fun removeViews(constraintLayout: ConstraintLayout) {
        if (observing) {
            context.contentResolver.unregisterContentObserver(observer)
            statusBarStateController.removeCallback(stateListener)
            mediaManager.removeCallback(mediaListener)
            observing = false
        }
        view?.let(constraintLayout::removeView)
        view = null
    }

    private fun refresh() {
        enabled = Settings.Secure.getIntForUser(
            context.contentResolver, KEY_ENABLED, 0, UserHandle.USER_CURRENT) == 1
        onMedia(mediaManager.mediaMetadata)
    }

    private fun onMedia(metadata: MediaMetadata?) {
        if (!enabled || !playing) {
            view?.setImageDrawable(null)
            updateVisibility()
            return
        }
        bgExecutor.execute {
            val art = artwork(metadata)
            mainHandler.post {
                view?.setImageBitmap(art)
                updateVisibility()
            }
        }
    }

    private fun artwork(md: MediaMetadata?): Bitmap? {
        if (md == null) return null
        return md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
    }

    private fun updateVisibility() {
        val v = view ?: return
        val show = enabled && playing && v.drawable != null && !statusBarStateController.isDozing
        v.visibility = if (show) View.VISIBLE else View.GONE
    }

    companion object {
        const val KEY_ENABLED = "picked_lockscreen_media_art"
    }
}
