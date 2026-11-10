/*
 * SPDX-FileCopyrightText: 2026 Miku-Picked UI
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.picked.nowbar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.android.systemui.Dependency
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.res.R
import com.android.systemui.tuner.TunerService

/**
 * Miku-Picked UI: Now Bar, a pill at the bottom of the lockscreen (in the style of Samsung's
 * One UI Now Bar) that shows what is playing and lets the user go to the previous or next
 * track and play / pause without unlocking. Tapping the song opens the player (after
 * unlocking).
 *
 * It follows the active media sessions itself (SystemUI holds MEDIA_CONTENT_CONTROL), hides
 * while nothing is playing or paused, on AOD, and when Settings.System.NOW_BAR_ENABLED is 0.
 */
class NowBarView(
    context: Context,
    private val activityStarter: ActivityStarter,
) : LinearLayout(context), TunerService.Tunable {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val sessionManager = context.getSystemService(MediaSessionManager::class.java)
    private val tunerService: TunerService = Dependency.get(TunerService::class.java)
    private val statusBarStateController: StatusBarStateController =
        Dependency.get(StatusBarStateController::class.java)

    private val art = ImageView(context)
    private val title = TextView(context)
    private val artist = TextView(context)
    private val prev = ImageButton(context)
    private val playPause = ImageButton(context)
    private val next = ImageButton(context)

    private var controller: MediaController? = null
    private var nowBarEnabled = true
    private var dozing = false
    private var attached = false

    private val controllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = refresh()

        override fun onMetadataChanged(metadata: MediaMetadata?) = refresh()

        override fun onSessionDestroyed() {
            setController(pickController(activeSessions()))
        }
    }

    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { sessions ->
            setController(pickController(sessions ?: emptyList()))
        }

    private val stateListener = object : StatusBarStateController.StateListener {
        override fun onDozingChanged(isDozing: Boolean) {
            dozing = isDozing
            refresh()
        }
    }

    init {
        id = R.id.picked_now_bar
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        visibility = GONE
        val padV = dp(8)
        setPadding(dp(8), padV, dp(8), padV)
        background = GradientDrawable().apply {
            cornerRadius = dp(32).toFloat()
            setColor(Color.argb(0xB3, 0x16, 0x16, 0x1A))
        }

        art.apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
            setBackgroundColor(Color.argb(0x40, 0xFF, 0xFF, 0xFF))
        }
        addView(art, LayoutParams(dp(44), dp(44)))

        val texts = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(12), 0, dp(4), 0)
        }
        title.apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = android.graphics.Typeface.create("sans-serif-medium", 0)
            setSingleLine()
            ellipsize = TextUtils.TruncateAt.END
        }
        artist.apply {
            setTextColor(Color.argb(0xB3, 0xFF, 0xFF, 0xFF))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setSingleLine()
            ellipsize = TextUtils.TruncateAt.END
        }
        texts.addView(title)
        texts.addView(artist)
        addView(texts, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        setupButton(prev, R.drawable.picked_now_bar_prev) {
            controller?.transportControls?.skipToPrevious()
        }
        setupButton(playPause, R.drawable.picked_now_bar_play) {
            val c = controller ?: return@setupButton
            if (isPlaying(c.playbackState)) c.transportControls.pause()
            else c.transportControls.play()
        }
        setupButton(next, R.drawable.picked_now_bar_next) {
            controller?.transportControls?.skipToNext()
        }
        prev.contentDescription = context.getString(R.string.picked_now_bar_previous)
        next.contentDescription = context.getString(R.string.picked_now_bar_next)

        // Tapping the song opens the player once the device is unlocked.
        val openPlayer = OnClickListener {
            val intent = controller?.sessionActivity ?: return@OnClickListener
            activityStarter.startPendingIntentDismissingKeyguard(intent)
        }
        art.setOnClickListener(openPlayer)
        texts.setOnClickListener(openPlayer)
    }

    private fun setupButton(button: ImageButton, icon: Int, action: () -> Unit) {
        button.setImageResource(icon)
        button.setColorFilter(Color.WHITE)
        button.scaleType = ImageView.ScaleType.CENTER
        button.background = null
        button.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            action()
        }
        addView(button, LayoutParams(dp(44), dp(44)))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attached = true
        tunerService.addTunable(this, NOW_BAR_KEY)
        statusBarStateController.addCallback(stateListener)
        dozing = statusBarStateController.isDozing
        try {
            sessionManager?.addOnActiveSessionsChangedListener(sessionsListener, null, mainHandler)
        } catch (e: SecurityException) {
            Log.e(TAG, "No access to media sessions", e)
        }
        setController(pickController(activeSessions()))
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        attached = false
        tunerService.removeTunable(this)
        statusBarStateController.removeCallback(stateListener)
        sessionManager?.removeOnActiveSessionsChangedListener(sessionsListener)
        setController(null)
    }

    override fun onTuningChanged(key: String?, newValue: String?) {
        if (key == NOW_BAR_KEY) {
            nowBarEnabled = TunerService.parseIntegerSwitch(newValue, true)
            refresh()
        }
    }

    private fun activeSessions(): List<MediaController> =
        try {
            sessionManager?.getActiveSessions(null) ?: emptyList()
        } catch (e: SecurityException) {
            emptyList()
        }

    /** The playing session if there is one, otherwise the first one with something loaded. */
    private fun pickController(sessions: List<MediaController>): MediaController? =
        sessions.firstOrNull { isPlaying(it.playbackState) }
            ?: sessions.firstOrNull { hasMedia(it) && it.playbackState?.state != PlaybackState.STATE_NONE }

    private fun setController(newController: MediaController?) {
        if (controller?.sessionToken == newController?.sessionToken && controller != null) {
            refresh()
            return
        }
        controller?.unregisterCallback(controllerCallback)
        controller = if (attached) newController else null
        controller?.registerCallback(controllerCallback, mainHandler)
        refresh()
    }

    private fun refresh() {
        val c = controller
        val metadata = c?.metadata
        val state = c?.playbackState
        val show = nowBarEnabled && !dozing && c != null && hasMedia(c) &&
            state != null && state.state != PlaybackState.STATE_NONE &&
            state.state != PlaybackState.STATE_STOPPED && state.state != PlaybackState.STATE_ERROR
        if (!show) {
            visibility = GONE
            return
        }

        title.text = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        val artistText = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
        artist.text = artistText
        artist.visibility = if (artistText.isNullOrEmpty()) GONE else VISIBLE

        val bitmap: Bitmap? = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        if (bitmap != null) {
            art.setImageBitmap(bitmap)
            art.setPadding(0, 0, 0, 0)
            art.clearColorFilter()
        } else {
            art.setImageResource(R.drawable.picked_now_bar_play)
            art.setPadding(dp(10), dp(10), dp(10), dp(10))
            art.setColorFilter(Color.WHITE)
        }

        val playing = isPlaying(state)
        playPause.setImageResource(
            if (playing) R.drawable.picked_now_bar_pause else R.drawable.picked_now_bar_play
        )
        playPause.contentDescription = context.getString(
            if (playing) R.string.picked_now_bar_pause else R.string.picked_now_bar_play
        )
        val actions = state?.actions ?: 0L
        prev.isEnabled = actions == 0L || (actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS) != 0L
        next.isEnabled = actions == 0L || (actions and PlaybackState.ACTION_SKIP_TO_NEXT) != 0L
        prev.alpha = if (prev.isEnabled) 1f else 0.4f
        next.alpha = if (next.isEnabled) 1f else 0.4f

        visibility = VISIBLE
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics)
            .toInt()

    companion object {
        private const val TAG = "NowBarView"
        private const val NOW_BAR_KEY = "system:" + Settings.System.NOW_BAR_ENABLED

        private fun isPlaying(state: PlaybackState?): Boolean =
            state?.state == PlaybackState.STATE_PLAYING ||
                state?.state == PlaybackState.STATE_BUFFERING

        private fun hasMedia(c: MediaController): Boolean {
            val m = c.metadata ?: return false
            return !m.getString(MediaMetadata.METADATA_KEY_TITLE).isNullOrEmpty() ||
                !m.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE).isNullOrEmpty()
        }
    }
}
