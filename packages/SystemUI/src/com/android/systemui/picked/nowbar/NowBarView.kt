/*
 * SPDX-FileCopyrightText: 2026 Miku-Picked UI
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.picked.nowbar

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.text.format.DateUtils
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.android.systemui.Dependency
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.res.R
import com.android.systemui.tuner.TunerService

/**
 * Miku-Picked UI: Now Bar, a pill at the bottom of the lockscreen (in the style of Samsung's
 * One UI Now Bar) that shows what is playing with previous / play-pause / next buttons.
 *
 * Tapping the pill expands it in place into a large player (big album art, seek bar with
 * times, large controls), like One UI. In the large player, tapping the art or the song, or
 * the open button, opens the app's player (after unlocking); the arrow collapses it again.
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

    // Compact pill
    private val compactRow = LinearLayout(context)
    private val art = ImageView(context)
    private val title = TextView(context)
    private val artist = TextView(context)
    private val prev = ImageButton(context)
    private val playPause = ImageButton(context)
    private val next = ImageButton(context)

    // Large player
    private val expandedPanel = LinearLayout(context)
    private val bigArt = ImageView(context)
    private val bigTitle = TextView(context)
    private val bigArtist = TextView(context)
    private val openButton = ImageButton(context)
    private val collapseButton = ImageButton(context)
    private val seekBar = SeekBar(context)
    private val elapsedText = TextView(context)
    private val durationText = TextView(context)
    private val bigPrev = ImageButton(context)
    private val bigPlayPause = ImageButton(context)
    private val bigNext = ImageButton(context)

    private var controller: MediaController? = null

    /**
     * Every active session, in the priority order MediaSessionManager reports, each with its
     * own callback. A player often registers its session before it sets a playback state or
     * metadata, so picking only when the session list changes would miss it; watching each
     * session re-picks whenever any of them starts playing or loads a track.
     */
    private val watched =
        LinkedHashMap<MediaSession.Token, Pair<MediaController, MediaController.Callback>>()
    private var nowBarEnabled = true
    private var dozing = false
    private var attached = false
    private var expanded = false
    private var userSeeking = false

    private val progressUpdater = object : Runnable {
        override fun run() {
            updateProgress()
            if (expanded && visibility == VISIBLE && isPlaying(controller?.playbackState)) {
                mainHandler.postDelayed(this, PROGRESS_INTERVAL_MS)
            }
        }
    }

    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { sessions ->
            watchSessions(sessions ?: emptyList())
        }

    private val stateListener = object : StatusBarStateController.StateListener {
        override fun onDozingChanged(isDozing: Boolean) {
            dozing = isDozing
            if (isDozing) setExpanded(false, animate = false)
            refresh()
        }
    }

    init {
        id = R.id.picked_now_bar
        orientation = VERTICAL
        visibility = GONE
        background = GradientDrawable().apply {
            cornerRadius = dp(32).toFloat()
            setColor(Color.argb(0xB3, 0x16, 0x16, 0x1A))
        }

        buildCompactRow()
        buildExpandedPanel()
        expandedPanel.visibility = GONE
        addView(compactRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(expandedPanel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    private fun buildCompactRow() {
        compactRow.apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
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
        compactRow.addView(art, LayoutParams(dp(44), dp(44)))

        val texts = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(12), 0, dp(4), 0)
        }
        styleTitle(title, 14f)
        styleSubtitle(artist, 12f)
        texts.addView(title)
        texts.addView(artist)
        compactRow.addView(texts, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        setupButton(compactRow, prev, R.drawable.picked_now_bar_prev, 44) { skipPrevious() }
        setupButton(compactRow, playPause, R.drawable.picked_now_bar_play, 44) { togglePlay() }
        setupButton(compactRow, next, R.drawable.picked_now_bar_next, 44) { skipNext() }
        prev.contentDescription = context.getString(R.string.picked_now_bar_previous)
        next.contentDescription = context.getString(R.string.picked_now_bar_next)

        // Tapping the pill (anywhere but its buttons) opens the large player, as on One UI.
        val expand = OnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            setExpanded(true, animate = true)
        }
        compactRow.setOnClickListener(expand)
        art.setOnClickListener(expand)
        texts.setOnClickListener(expand)
    }

    private fun buildExpandedPanel() {
        expandedPanel.apply {
            orientation = VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(16))
        }

        // Header: big art, song, open and collapse buttons.
        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        bigArt.apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(16).toFloat())
                }
            }
            setBackgroundColor(Color.argb(0x40, 0xFF, 0xFF, 0xFF))
        }
        header.addView(bigArt, LayoutParams(dp(88), dp(88)))

        val texts = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(16), 0, dp(4), 0)
        }
        styleTitle(bigTitle, 18f)
        styleSubtitle(bigArtist, 14f)
        texts.addView(bigTitle)
        texts.addView(bigArtist)
        header.addView(texts, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        val headerButtons = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        setupButton(headerButtons, collapseButton, R.drawable.picked_now_bar_collapse, 40) {
            setExpanded(false, animate = true)
        }
        setupButton(headerButtons, openButton, R.drawable.picked_now_bar_open, 40) {
            openPlayer()
        }
        collapseButton.contentDescription = context.getString(R.string.picked_now_bar_collapse)
        openButton.contentDescription = context.getString(R.string.picked_now_bar_open)
        header.addView(headerButtons, LayoutParams(dp(40), LayoutParams.WRAP_CONTENT))

        val open = OnClickListener { openPlayer() }
        bigArt.setOnClickListener(open)
        texts.setOnClickListener(open)
        expandedPanel.addView(header,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // Seek bar and times.
        seekBar.apply {
            progressTintList = ColorStateList.valueOf(Color.WHITE)
            thumbTintList = ColorStateList.valueOf(Color.WHITE)
            progressBackgroundTintList = ColorStateList.valueOf(Color.argb(0x55, 0xFF, 0xFF, 0xFF))
            setPadding(dp(4), 0, dp(4), 0)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) elapsedText.text = formatTime(progress.toLong())
                }

                override fun onStartTrackingTouch(bar: SeekBar) {
                    userSeeking = true
                }

                override fun onStopTrackingTouch(bar: SeekBar) {
                    userSeeking = false
                    controller?.transportControls?.seekTo(bar.progress.toLong())
                }
            })
        }
        val seekLp = LayoutParams(LayoutParams.MATCH_PARENT, dp(32))
        seekLp.topMargin = dp(16)
        expandedPanel.addView(seekBar, seekLp)

        val times = FrameLayout(context)
        listOf(elapsedText, durationText).forEach {
            it.setTextColor(Color.argb(0xB3, 0xFF, 0xFF, 0xFF))
            it.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            it.fontFeatureSettings = "tnum"
        }
        times.addView(elapsedText, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.START))
        times.addView(durationText, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.END))
        times.setPadding(dp(4), 0, dp(4), 0)
        expandedPanel.addView(times,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // Large controls.
        val controls = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
        }
        setupButton(controls, bigPrev, R.drawable.picked_now_bar_prev, 64) { skipPrevious() }
        setupButton(controls, bigPlayPause, R.drawable.picked_now_bar_play, 64) { togglePlay() }
        setupButton(controls, bigNext, R.drawable.picked_now_bar_next, 64) { skipNext() }
        bigPrev.contentDescription = context.getString(R.string.picked_now_bar_previous)
        bigNext.contentDescription = context.getString(R.string.picked_now_bar_next)
        listOf(bigPrev, bigNext).forEach { it.scaleX = 1.4f; it.scaleY = 1.4f }
        bigPlayPause.apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
            }
            setColorFilter(Color.argb(0xFF, 0x16, 0x16, 0x1A))
            (layoutParams as LayoutParams).setMargins(dp(24), 0, dp(24), 0)
        }
        val controlsLp = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        controlsLp.topMargin = dp(8)
        expandedPanel.addView(controls, controlsLp)
    }

    private fun styleTitle(view: TextView, sizeSp: Float) {
        view.setTextColor(Color.WHITE)
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        view.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        view.setSingleLine()
        view.ellipsize = TextUtils.TruncateAt.END
    }

    private fun styleSubtitle(view: TextView, sizeSp: Float) {
        view.setTextColor(Color.argb(0xB3, 0xFF, 0xFF, 0xFF))
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        view.setSingleLine()
        view.ellipsize = TextUtils.TruncateAt.END
    }

    private fun setupButton(
        parent: ViewGroup,
        button: ImageButton,
        icon: Int,
        sizeDp: Int,
        action: () -> Unit,
    ) {
        button.setImageResource(icon)
        button.setColorFilter(Color.WHITE)
        button.scaleType = ImageView.ScaleType.CENTER
        button.background = null
        button.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            action()
        }
        parent.addView(button, LayoutParams(dp(sizeDp), dp(sizeDp)))
    }

    private fun skipPrevious() {
        controller?.transportControls?.skipToPrevious()
    }

    private fun skipNext() {
        controller?.transportControls?.skipToNext()
    }

    private fun togglePlay() {
        val c = controller ?: return
        if (isPlaying(c.playbackState)) c.transportControls.pause() else c.transportControls.play()
    }

    /** Opens the app's player once the device is unlocked. */
    private fun openPlayer() {
        val intent = controller?.sessionActivity ?: return
        activityStarter.startPendingIntentDismissingKeyguard(intent)
    }

    private fun setExpanded(value: Boolean, animate: Boolean) {
        if (expanded == value) return
        expanded = value
        if (animate) {
            (parent as? ViewGroup)?.let {
                TransitionManager.beginDelayedTransition(it, AutoTransition().setDuration(250))
            }
        }
        compactRow.visibility = if (value) GONE else VISIBLE
        expandedPanel.visibility = if (value) VISIBLE else GONE
        (background as? GradientDrawable)?.apply {
            cornerRadius = dp(if (value) 28 else 32).toFloat()
            setColor(if (value) Color.argb(0xD9, 0x16, 0x16, 0x1A)
                else Color.argb(0xB3, 0x16, 0x16, 0x1A))
        }
        refresh()
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
        watchSessions(activeSessions())
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        attached = false
        tunerService.removeTunable(this)
        statusBarStateController.removeCallback(stateListener)
        sessionManager?.removeOnActiveSessionsChangedListener(sessionsListener)
        mainHandler.removeCallbacks(progressUpdater)
        setExpanded(false, animate = false)
        watchSessions(emptyList())
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

    private fun watchSessions(sessions: List<MediaController>) {
        val wanted = if (attached) sessions else emptyList()
        val tokens = wanted.mapTo(HashSet()) { it.sessionToken }
        val old = LinkedHashMap(watched)
        watched.clear()
        old.forEach { (token, entry) ->
            if (token !in tokens) entry.first.unregisterCallback(entry.second)
        }
        for (c in wanted) {
            val token = c.sessionToken
            if (watched.containsKey(token)) continue
            val entry = old[token] ?: Pair(c, sessionCallback(token).also {
                c.registerCallback(it, mainHandler)
            })
            watched[token] = entry
        }
        update()
    }

    private fun sessionCallback(token: MediaSession.Token) = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = update()

        override fun onMetadataChanged(metadata: MediaMetadata?) = update()

        override fun onSessionDestroyed() {
            watched.remove(token)?.let { it.first.unregisterCallback(it.second) }
            update()
        }
    }

    /** Re-picks the session to show from the watched ones and redraws. */
    private fun update() {
        controller = pickController(watched.values.map { it.first })
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
            mainHandler.removeCallbacks(progressUpdater)
            if (expanded) setExpanded(false, animate = false)
            visibility = GONE
            return
        }

        val titleText = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        val artistText = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
        title.text = titleText
        bigTitle.text = titleText
        artist.text = artistText
        bigArtist.text = artistText
        val artistVisibility = if (artistText.isNullOrEmpty()) GONE else VISIBLE
        artist.visibility = artistVisibility
        bigArtist.visibility = artistVisibility

        val bitmap: Bitmap? = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        setArt(art, bitmap, dp(10))
        setArt(bigArt, bitmap, dp(24))

        val playing = isPlaying(state)
        val playIcon = if (playing) R.drawable.picked_now_bar_pause else R.drawable.picked_now_bar_play
        val playDesc = context.getString(
            if (playing) R.string.picked_now_bar_pause else R.string.picked_now_bar_play
        )
        playPause.setImageResource(playIcon)
        playPause.contentDescription = playDesc
        bigPlayPause.setImageResource(playIcon)
        bigPlayPause.contentDescription = playDesc

        val actions = state?.actions ?: 0L
        val canPrev = actions == 0L || (actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS) != 0L
        val canNext = actions == 0L || (actions and PlaybackState.ACTION_SKIP_TO_NEXT) != 0L
        listOf(prev, bigPrev).forEach { it.isEnabled = canPrev; it.alpha = if (canPrev) 1f else 0.4f }
        listOf(next, bigNext).forEach { it.isEnabled = canNext; it.alpha = if (canNext) 1f else 0.4f }
        openButton.visibility = if (c.sessionActivity != null) VISIBLE else INVISIBLE

        visibility = VISIBLE

        mainHandler.removeCallbacks(progressUpdater)
        if (expanded) progressUpdater.run()
    }

    private fun setArt(view: ImageView, bitmap: Bitmap?, iconPadding: Int) {
        if (bitmap != null) {
            view.setImageBitmap(bitmap)
            view.setPadding(0, 0, 0, 0)
            view.clearColorFilter()
        } else {
            view.setImageResource(R.drawable.picked_now_bar_play)
            view.setPadding(iconPadding, iconPadding, iconPadding, iconPadding)
            view.setColorFilter(Color.WHITE)
        }
    }

    private fun updateProgress() {
        val c = controller ?: return
        val state = c.playbackState ?: return
        val duration = c.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        if (duration <= 0L) {
            seekBar.visibility = INVISIBLE
            elapsedText.text = ""
            durationText.text = ""
            return
        }
        seekBar.visibility = VISIBLE
        seekBar.isEnabled = (state.actions and PlaybackState.ACTION_SEEK_TO) != 0L
        var position = state.position
        if (state.state == PlaybackState.STATE_PLAYING && state.lastPositionUpdateTime > 0) {
            position += ((SystemClock.elapsedRealtime() - state.lastPositionUpdateTime) *
                state.playbackSpeed).toLong()
        }
        position = position.coerceIn(0L, duration)
        if (!userSeeking) {
            seekBar.max = duration.toInt()
            seekBar.progress = position.toInt()
            elapsedText.text = formatTime(position)
        }
        durationText.text = formatTime(duration)
    }

    private fun formatTime(ms: Long): String = DateUtils.formatElapsedTime(ms / 1000)

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics)
            .toInt()

    companion object {
        private const val TAG = "NowBarView"
        private const val NOW_BAR_KEY = "system:" + Settings.System.NOW_BAR_ENABLED
        private const val PROGRESS_INTERVAL_MS = 500L

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
