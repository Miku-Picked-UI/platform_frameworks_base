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
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadata
import android.os.Handler
import android.os.UserHandle
import android.provider.Settings
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.keyguard.shared.model.KeyguardSection
import com.android.systemui.media.NotificationMediaManager
import com.android.systemui.picked.nowbar.LockscreenArt
import com.android.systemui.picked.nowbar.MotionArt
import com.android.systemui.picked.nowbar.MotionArtPlayer
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.res.R
import com.android.systemui.statusbar.StatusBarState
import java.io.File
import java.util.concurrent.Executor
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

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
 *
 * [PenguinOS] Full screen art: while [LockscreenArt.fullArt] (the Now Bar folded, or its art
 * tapped with iOS-style media art on), the art is drawn sharp across the screen instead, with
 * a soft shade at the top and bottom for the clock and the Now Bar, also while paused. With
 * animated album art on, Apple Music's motion artwork ([MotionArt]) loops over it, only while
 * the lock screen itself shows.
 */
class KeyguardMediaArtSection
@Inject
constructor(
    private val context: Context,
    private val mediaManager: NotificationMediaManager,
    private val statusBarStateController: StatusBarStateController,
    @Main private val mainHandler: Handler,
    @Background private val bgExecutor: Executor,
    @Application private val applicationScope: CoroutineScope,
) : KeyguardSection() {

    private var container: FrameLayout? = null
    private var view: ImageView? = null
    private var shade: View? = null
    private var video: TextureView? = null
    private var videoPlayer: MotionArtPlayer? = null
    private var videoFile: File? = null
    private var lookup: Job? = null
    private var lookupKey: String? = null
    private var fullArtJob: Job? = null

    private var enabled = false
    private var playing = false
    private var fullArt = false
    private var observing = false
    private var metadata: MediaMetadata? = null

    private val blur = RenderEffect.createBlurEffect(70f, 70f, Shader.TileMode.CLAMP)

    private val observer = object : ContentObserver(mainHandler) {
        override fun onChange(selfChange: Boolean) = refresh()
    }

    private val stateListener = object : StatusBarStateController.StateListener {
        override fun onDozingChanged(isDozing: Boolean) = updateVisibility()

        override fun onStateChanged(newState: Int) = updateVisibility()
    }

    private val mediaListener = object : NotificationMediaManager.MediaListener {
        override fun onPrimaryMetadataOrStateChanged(metadata: MediaMetadata?, state: Int) {
            playing = NotificationMediaManager.isPlayingState(state)
            onMedia(metadata)
        }
    }

    override fun addViews(constraintLayout: ConstraintLayout) {
        val art = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val gradient = View(context).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(
                    Color.argb(110, 0, 0, 0),
                    Color.TRANSPARENT,
                    Color.TRANSPARENT,
                    Color.argb(150, 0, 0, 0),
                ),
            )
            visibility = View.GONE
        }
        container = FrameLayout(context).apply {
            id = R.id.picked_media_art_bg
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            isClickable = false
            isFocusable = false
            visibility = View.GONE
            addView(art, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(gradient, FrameLayout.LayoutParams(MATCH, MATCH))
        }
        view = art
        shade = gradient
        constraintLayout.addView(container)
    }

    override fun bindData(constraintLayout: ConstraintLayout) {
        if (!observing) {
            LockscreenArt.init(context)
            context.contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(KEY_ENABLED), false, observer, UserHandle.USER_ALL)
            statusBarStateController.addCallback(stateListener)
            mediaManager.addCallback(mediaListener)
            fullArtJob = applicationScope.launch {
                LockscreenArt.fullArt.collect { full ->
                    mainHandler.post {
                        fullArt = full
                        refresh()
                    }
                }
            }
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
            fullArtJob?.cancel()
            fullArtJob = null
            observing = false
        }
        stopVideo()
        lookup?.cancel()
        lookup = null
        lookupKey = null
        videoFile = null
        container?.let(constraintLayout::removeView)
        container = null
        view = null
        shade = null
    }

    private fun refresh() {
        enabled = Settings.Secure.getIntForUser(
            context.contentResolver, KEY_ENABLED, 0, UserHandle.USER_CURRENT) == 1
        onMedia(mediaManager.mediaMetadata)
    }

    private fun onMedia(metadata: MediaMetadata?) {
        this.metadata = metadata
        if (!wantsArt()) {
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

    /** The full screen art also shows paused music; the blurred background only playing. */
    private fun wantsArt() = fullArt || (enabled && playing)

    private fun artwork(md: MediaMetadata?): Bitmap? {
        if (md == null) return null
        return md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
    }

    private fun updateVisibility() {
        val c = container ?: return
        val v = view ?: return
        val show = wantsArt() && v.drawable != null && !statusBarStateController.isDozing
        c.visibility = if (show) View.VISIBLE else View.GONE
        if (fullArt) {
            v.setRenderEffect(null)
            v.clearColorFilter()
            shade?.visibility = View.VISIBLE
        } else {
            // Darken for legibility of the clock and notifications on top.
            v.setRenderEffect(blur)
            v.setColorFilter(Color.argb(120, 0, 0, 0), PorterDuff.Mode.SRC_ATOP)
            shade?.visibility = View.GONE
        }
        updateVideo(show && fullArt)
    }

    // region Motion art

    private fun updateVideo(fullArtShowing: Boolean) {
        val onLockScreen = statusBarStateController.state == StatusBarState.KEYGUARD
        val md = metadata
        val artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
        val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        if (!fullArtShowing || !onLockScreen || !LockscreenArt.motionArtEnabled ||
            artist.isBlank() || title.isBlank()) {
            stopVideo()
            return
        }
        val key = "$artist\u0000$title"
        if (key == lookupKey) {
            // Same song: show what was found, if anything. A song without motion art (or whose
            // video failed) is looked up again only when it comes back after another one.
            videoFile?.let { startVideo(it) }
            return
        }
        stopVideo()
        lookup?.cancel()
        lookupKey = key
        videoFile = null
        lookup = applicationScope.launch {
            val file =
                MotionArt.find(context, artist, title, MotionArt.Kind.TALL)
                    ?: MotionArt.find(context, artist, title, MotionArt.Kind.SQUARE)
            mainHandler.post {
                if (lookupKey != key) return@post
                videoFile = file
                // The lock screen may have gone meanwhile; the next update starts it then.
                if (file != null && container?.visibility == View.VISIBLE && fullArt &&
                    statusBarStateController.state == StatusBarState.KEYGUARD &&
                    !statusBarStateController.isDozing) {
                    startVideo(file)
                }
            }
        }
    }

    private fun startVideo(file: File) {
        val c = container ?: return
        if (video != null) return
        val texture = TextureView(context).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        videoPlayer = MotionArtPlayer(texture, file) {
            mainHandler.post {
                videoFile = null
                stopVideo()
            }
        }
        video = texture
        // Over the still art, under the shade.
        c.addView(texture, 1, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    /** Stops the player; what was found for the current song is kept, so it can resume. */
    private fun stopVideo() {
        videoPlayer?.release()
        videoPlayer = null
        video?.let { container?.removeView(it) }
        video = null
    }

    // endregion

    companion object {
        const val KEY_ENABLED = "picked_lockscreen_media_art"
        private const val MATCH = FrameLayout.LayoutParams.MATCH_PARENT
    }
}
