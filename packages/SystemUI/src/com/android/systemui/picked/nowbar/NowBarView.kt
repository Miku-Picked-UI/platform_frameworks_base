/*
 * SPDX-FileCopyrightText: 2026 Miku-Picked UI
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.picked.nowbar

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
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
import android.transition.ChangeBounds
import android.transition.Fade
import android.transition.TransitionManager
import android.transition.TransitionSet
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import android.widget.Chronometer
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.systemui.Dependency
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.res.R
import com.android.systemui.statusbar.StatusBarState
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.tuner.TunerService
import java.lang.ref.WeakReference
import kotlin.math.abs

/**
 * Miku-Picked UI: Now Bar, a pill on the lockscreen in the style of Samsung's One UI Now Bar.
 *
 * It has a page for each thing going on, and a horizontal swipe on the pill switches between
 * them (dots show how many there are):
 *  - media: what is playing, with previous / play-pause / next. Tapping it expands the pill in
 *    place into a large player (big art, seek bar with times, large controls); there the art,
 *    the song or the open button opens the app's player (after unlocking) and the arrow
 *    collapses it again, as does a tap anywhere outside it. While it is open it covers an
 *    under-display fingerprint sensor, so lockscreen fingerprint unlock is paused until it
 *    closes (opening the player closes it first, so the finger works for unlocking);
 *  - the flashlight while it is on, with a button that turns it off;
 *  - ongoing live notifications (timers, stopwatches, calls, navigation, recorders), with their
 *    chronometer; tapping one opens it (after unlocking).
 * A page that appears (music starts, flashlight turned on, timer started) is brought to front.
 *
 * The background is liquid glass, frosted glass or black (Settings.System.NOW_BAR_STYLE, see
 * [NowBarBackground]). The bar hides on AOD, while there is nothing to show, and when
 * Settings.System.NOW_BAR_ENABLED is 0. Media sessions are followed directly (SystemUI holds
 * MEDIA_CONTENT_CONTROL); the other activities come from [NowBarActivities].
 */
class NowBarView(
    context: Context,
    private val activityStarter: ActivityStarter,
    private val activities: NowBarActivities,
    private val keyguardUpdateMonitor: KeyguardUpdateMonitor,
) : LinearLayout(context), TunerService.Tunable {

    private sealed class Page {
        object Media : Page()

        object Flashlight : Page()

        data class Ongoing(val key: String) : Page()
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val sessionManager = context.getSystemService(MediaSessionManager::class.java)
    private val tunerService: TunerService = Dependency.get(TunerService::class.java)
    private val statusBarStateController: StatusBarStateController =
        Dependency.get(StatusBarStateController::class.java)
    private val backgroundDrawer = NowBarBackground(this)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    // Compact media pill
    private val compactRow = LinearLayout(context)
    private val art = ImageView(context)
    private val title = TextView(context)
    private val artist = TextView(context)
    private val prev = ImageButton(context)
    private val playPause = ImageButton(context)
    private val next = ImageButton(context)

    // Large media player
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

    // Activity pill (flashlight / live notifications)
    private val activityRow = LinearLayout(context)
    private val activityIcon = ImageView(context)
    private val activityTitle = TextView(context)
    private val activityText = TextView(context)
    private val activityChrono = Chronometer(context)
    private val activityAction = ImageButton(context)

    private val dots = LinearLayout(context)

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

    private var pages: List<Page> = emptyList()
    private var currentPage: Page? = null

    private var downX = 0f
    private var downY = 0f
    private var swiping = false

    private var cornerRadius = 0f
    /** Whether the bar is (or is animating to being) on screen. */
    private var shown = false
    private var effectiveAlpha = -1f

    private val progressUpdater = object : Runnable {
        override fun run() {
            updateProgress()
            if (expanded && visibility == VISIBLE && isPlaying(controller?.playbackState)) {
                mainHandler.postDelayed(this, PROGRESS_INTERVAL_MS)
            }
        }
    }

    /** Keeps the blur in step with the lockscreen fading (alpha set on a parent). */
    private val preDrawListener = ViewTreeObserver.OnPreDrawListener {
        val a = computeEffectiveAlpha()
        if (a != effectiveAlpha) {
            effectiveAlpha = a
            invalidate()
        }
        true
    }

    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { sessions ->
            watchSessions(sessions ?: emptyList())
        }

    private val activitiesListener = NowBarActivities.Listener { refresh() }

    private val stateListener = object : StatusBarStateController.StateListener {
        override fun onStateChanged(newState: Int) {
            // Leaving the lockscreen (unlocked, shade pulled down) closes the large player.
            if (newState != StatusBarState.KEYGUARD) setExpanded(false, animate = false)
        }

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
        setWillNotDraw(false)
        cornerRadius = dp(32).toFloat()

        buildCompactRow()
        buildExpandedPanel()
        buildActivityRow()
        expandedPanel.visibility = GONE
        activityRow.visibility = GONE
        dots.apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            visibility = GONE
            setPadding(0, 0, 0, dp(6))
        }
        addView(compactRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(activityRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(expandedPanel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(dots, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    // region Building

    private fun buildCompactRow() {
        compactRow.apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        circleClip(art)
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
        listOf(bigPrev, bigNext).forEach { it.scaleX = BIG_ICON_SCALE; it.scaleY = BIG_ICON_SCALE }
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

    private fun buildActivityRow() {
        activityRow.apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        activityIcon.apply {
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(11), dp(11), dp(11), dp(11))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(0x40, 0xFF, 0xFF, 0xFF))
            }
        }
        activityRow.addView(activityIcon, LayoutParams(dp(44), dp(44)))

        val texts = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(12), 0, dp(4), 0)
        }
        styleTitle(activityTitle, 14f)
        styleSubtitle(activityText, 12f)
        activityChrono.setTextColor(Color.argb(0xB3, 0xFF, 0xFF, 0xFF))
        activityChrono.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        activityChrono.fontFeatureSettings = "tnum"
        texts.addView(activityTitle)
        texts.addView(activityText)
        texts.addView(activityChrono)
        activityRow.addView(texts, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        setupButton(activityRow, activityAction, R.drawable.picked_now_bar_power, 44) {
            when (currentPage) {
                Page.Flashlight -> activities.turnOffFlashlight()
                else -> {}
            }
        }
        activityAction.contentDescription =
            context.getString(R.string.picked_now_bar_flashlight_off)

        activityRow.setOnClickListener {
            when (val page = currentPage) {
                is Page.Ongoing -> ongoingEntry(page.key)?.sbn?.notification?.contentIntent
                    ?.let { startIntent(it) }
                Page.Flashlight -> activities.turnOffFlashlight()
                else -> {}
            }
        }
    }

    private fun circleClip(view: ImageView) {
        view.scaleType = ImageView.ScaleType.CENTER_CROP
        view.clipToOutline = true
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setOval(0, 0, view.width, view.height)
            }
        }
        view.setBackgroundColor(Color.argb(0x40, 0xFF, 0xFF, 0xFF))
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
            bounce(it)
            action()
        }
        parent.addView(button, LayoutParams(dp(sizeDp), dp(sizeDp)))
    }

    /** A quick squeeze and springy release, for buttons. */
    private fun bounce(view: View) {
        val base = if (view === bigPrev || view === bigNext) BIG_ICON_SCALE else 1f
        view.animate().cancel()
        view.animate()
            .scaleX(base * 0.82f).scaleY(base * 0.82f)
            .setDuration(90).setInterpolator(EMPHASIZED_ACCEL)
            .withEndAction {
                view.animate()
                    .scaleX(base).scaleY(base)
                    .setDuration(380).setInterpolator(OvershootInterpolator(3f))
                    .start()
            }
            .start()
    }

    // endregion

    // region Actions

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

    /**
     * Opens the app's player once the device is unlocked. The large player closes first so
     * fingerprint unlock is back on for the unlock this asks for.
     */
    private fun openPlayer() {
        val intent = controller?.sessionActivity ?: return
        setExpanded(false, animate = false)
        startIntent(intent)
    }

    private fun startIntent(intent: PendingIntent) {
        activityStarter.startPendingIntentDismissingKeyguard(intent)
    }

    private fun setExpanded(value: Boolean, animate: Boolean) {
        if (expanded == value) return
        expanded = value
        expandedBar = if (value) WeakReference(this) else null
        try {
            keyguardUpdateMonitor.setPickedFingerprintPaused(value)
        } catch (t: Throwable) {
            Log.e(TAG, "could not pause fingerprint", t)
        }
        if (animate) {
            (parent as? ViewGroup)?.let { TransitionManager.beginDelayedTransition(it, morph()) }
        }
        cornerRadius = dp(if (value) 28 else 32).toFloat()
        invalidate()
        refresh()
        if (animate && value) staggerIn(expandedPanel)
    }

    /**
     * The pill morphs into the large player (and back): the bounds glide with the emphasized
     * curve while the old content fades out quickly and the new one fades in after it.
     */
    private fun morph() = TransitionSet().apply {
        ordering = TransitionSet.ORDERING_TOGETHER
        addTransition(ChangeBounds().apply {
            duration = MORPH_MS
            interpolator = EMPHASIZED
        })
        addTransition(Fade(Fade.OUT).apply {
            duration = 120
            interpolator = EMPHASIZED_ACCEL
        })
        addTransition(Fade(Fade.IN).apply {
            startDelay = 140
            duration = 260
            interpolator = EMPHASIZED_DECEL
        })
    }

    /** The large player's rows arrive one after another, rising into place. */
    private fun staggerIn(panel: ViewGroup) {
        for (i in 0 until panel.childCount) {
            val child = panel.getChildAt(i)
            child.animate().cancel()
            child.alpha = 0f
            child.translationY = dp(14).toFloat()
            child.animate()
                .alpha(1f).translationY(0f)
                .setStartDelay(150L + i * 45L)
                .setDuration(340)
                .setInterpolator(EMPHASIZED_DECEL)
                .start()
        }
    }

    // endregion

    // region Paging

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> pressScale(if (expanded) 0.985f else 0.965f, true)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> pressScale(1f, false)
        }
        return super.dispatchTouchEvent(ev)
    }

    /** Squeezes the bar under the finger and lets it spring back on release. */
    private fun pressScale(target: Float, down: Boolean) {
        if (!shown) return
        animate()
            .scaleX(target).scaleY(target)
            .setStartDelay(0)
            .setDuration(if (down) 140 else 420)
            .setInterpolator(if (down) EMPHASIZED else OvershootInterpolator(2.2f))
            .start()
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (expanded || pages.size < 2) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                swiping = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (abs(dx) > touchSlop && abs(dx) > abs(dy) * 1.5f) {
                    swiping = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!swiping) return super.onTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                translationX = (ev.x - downX) * 0.35f
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val dx = ev.x - downX
                animate().translationX(0f).setDuration(300)
                    .setInterpolator(OvershootInterpolator(1.4f)).start()
                swiping = false
                if (ev.actionMasked == MotionEvent.ACTION_UP && abs(dx) > dp(48)) {
                    performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    switchPage(if (dx < 0) 1 else -1)
                }
            }
        }
        return true
    }

    private fun switchPage(step: Int) {
        if (pages.size < 2) return
        val index = currentPage?.let { pages.indexOf(it) }?.coerceAtLeast(0) ?: 0
        currentPage = pages[(index + step + pages.size) % pages.size]
        (parent as? ViewGroup)?.let {
            TransitionManager.beginDelayedTransition(it, ChangeBounds().apply {
                duration = 320
                interpolator = EMPHASIZED
            })
        }
        refresh()
        // The new page slides in from the side the finger went towards.
        val row = if (compactRow.visibility == VISIBLE) compactRow else activityRow
        row.animate().cancel()
        row.alpha = 0f
        row.translationX = dp(48).toFloat() * step
        row.animate()
            .alpha(1f).translationX(0f)
            .setStartDelay(0)
            .setDuration(340)
            .setInterpolator(EMPHASIZED_DECEL)
            .start()
    }

    private fun computePages(): List<Page> {
        val list = ArrayList<Page>()
        val c = controller
        val state = c?.playbackState
        val mediaShows = c != null && hasMedia(c) && state != null &&
            state.state != PlaybackState.STATE_NONE &&
            state.state != PlaybackState.STATE_STOPPED && state.state != PlaybackState.STATE_ERROR
        if (mediaShows) list.add(Page.Media)
        activities.ongoingEntries.forEach { list.add(Page.Ongoing(it.key)) }
        if (activities.flashlightOn) list.add(Page.Flashlight)
        return list
    }

    private fun ongoingEntry(key: String): NotificationEntry? =
        activities.ongoingEntries.firstOrNull { it.key == key }

    // endregion

    // region Lifecycle

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attached = true
        backgroundDrawer.onAttached()
        viewTreeObserver.addOnPreDrawListener(preDrawListener)
        tunerService.addTunable(this, NOW_BAR_KEY, NOW_BAR_STYLE_KEY)
        statusBarStateController.addCallback(stateListener)
        dozing = statusBarStateController.isDozing
        activities.addListener(activitiesListener)
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
        viewTreeObserver.removeOnPreDrawListener(preDrawListener)
        backgroundDrawer.onDetached()
        tunerService.removeTunable(this)
        statusBarStateController.removeCallback(stateListener)
        activities.removeListener(activitiesListener)
        sessionManager?.removeOnActiveSessionsChangedListener(sessionsListener)
        mainHandler.removeCallbacks(progressUpdater)
        activityChrono.stop()
        setExpanded(false, animate = false)
        watchSessions(emptyList())
    }

    override fun onTuningChanged(key: String?, newValue: String?) {
        when (key) {
            NOW_BAR_KEY -> {
                nowBarEnabled = TunerService.parseIntegerSwitch(newValue, true)
                refresh()
            }
            NOW_BAR_STYLE_KEY -> {
                backgroundDrawer.setStyle(newValue?.toIntOrNull() ?: NowBarBackground.STYLE_LIQUID)
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (effectiveAlpha < 0f) effectiveAlpha = computeEffectiveAlpha()
        backgroundDrawer.draw(canvas, width, height, cornerRadius, effectiveAlpha)
        super.onDraw(canvas)
    }

    /** This view's alpha times its parents' (0 when any of them is not visible). */
    private fun computeEffectiveAlpha(): Float {
        var a = 1f
        var v: View? = this
        while (v != null) {
            if (v.visibility != VISIBLE) return 0f
            a *= v.alpha
            v = v.parent as? View
        }
        return a
    }

    // endregion

    // region Media sessions

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

    // endregion

    // region Drawing state

    private fun refresh() {
        val newPages = computePages()
        // A page that just appeared comes to front; otherwise keep the one being looked at.
        val added = newPages.firstOrNull { it !in pages }
        currentPage =
            when {
                added != null && pages.isNotEmpty() -> added
                currentPage?.let { it in newPages } == true -> currentPage
                else -> newPages.firstOrNull()
            }
        pages = newPages

        val page = currentPage
        if (!nowBarEnabled || dozing || page == null) {
            mainHandler.removeCallbacks(progressUpdater)
            activityChrono.stop()
            if (expanded) setExpanded(false, animate = false)
            hideAnimated(instant = dozing || !nowBarEnabled)
            return
        }
        if (page != Page.Media && expanded) setExpanded(false, animate = false)

        updateDots()
        when (page) {
            Page.Media -> bindMedia()
            Page.Flashlight -> bindFlashlight()
            is Page.Ongoing -> bindOngoing(page.key)
        }
        showAnimated()
    }

    /** Pops in: rises, grows and fades in with a little overshoot. */
    private fun showAnimated() {
        if (shown && visibility == VISIBLE) return
        shown = true
        animate().cancel()
        val wasHidden = visibility != VISIBLE
        visibility = VISIBLE
        if (!isAttachedToWindow || dozing) {
            resetShowState()
            return
        }
        if (wasHidden) {
            alpha = 0f
            scaleX = 0.86f
            scaleY = 0.86f
            translationY = dp(18).toFloat()
        }
        animate()
            .alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
            .setStartDelay(0)
            .setDuration(520)
            .setInterpolator(OvershootInterpolator(1.15f))
            .start()
    }

    /** Sinks, shrinks and fades out, then leaves the layout. */
    private fun hideAnimated(instant: Boolean) {
        if (!shown && visibility != VISIBLE) return
        shown = false
        animate().cancel()
        if (instant || !isShown) {
            visibility = GONE
            resetShowState()
            return
        }
        animate()
            .alpha(0f).scaleX(0.9f).scaleY(0.9f).translationY(dp(10).toFloat())
            .setStartDelay(0)
            .setDuration(220)
            .setInterpolator(EMPHASIZED_ACCEL)
            .withEndAction {
                if (!shown) {
                    visibility = GONE
                    resetShowState()
                }
            }
            .start()
    }

    private fun resetShowState() {
        alpha = 1f
        scaleX = 1f
        scaleY = 1f
        translationY = 0f
    }

    private fun updateDots() {
        if (pages.size < 2 || expanded) {
            dots.visibility = GONE
            return
        }
        dots.visibility = VISIBLE
        while (dots.childCount < pages.size) {
            val dot = View(context)
            val lp = LayoutParams(dp(5), dp(5))
            lp.setMargins(dp(3), 0, dp(3), 0)
            dots.addView(dot, lp)
        }
        while (dots.childCount > pages.size) dots.removeViewAt(dots.childCount - 1)
        val current = currentPage?.let { pages.indexOf(it) } ?: -1
        for (i in 0 until dots.childCount) {
            dots.getChildAt(i).background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (i == current) Color.WHITE else Color.argb(0x66, 0xFF, 0xFF, 0xFF))
            }
        }
    }

    private fun showRows(media: Boolean) {
        activityRow.visibility = if (media) GONE else VISIBLE
        compactRow.visibility = if (media && !expanded) VISIBLE else GONE
        expandedPanel.visibility = if (media && expanded) VISIBLE else GONE
        if (media) activityChrono.stop()
    }

    private fun bindMedia() {
        showRows(media = true)
        val c = controller ?: return
        val metadata = c.metadata
        val state = c.playbackState

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
        val playIcon =
            if (playing) R.drawable.picked_now_bar_pause else R.drawable.picked_now_bar_play
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

        mainHandler.removeCallbacks(progressUpdater)
        if (expanded) progressUpdater.run()
    }

    private fun bindFlashlight() {
        showRows(media = false)
        mainHandler.removeCallbacks(progressUpdater)
        activityIcon.setImageResource(R.drawable.picked_now_bar_flashlight)
        activityIcon.setColorFilter(Color.WHITE)
        activityTitle.setText(R.string.picked_now_bar_flashlight)
        activityText.setText(R.string.picked_now_bar_flashlight_on)
        activityText.visibility = VISIBLE
        activityChrono.stop()
        activityChrono.visibility = GONE
        activityAction.visibility = VISIBLE
    }

    private fun bindOngoing(key: String) {
        showRows(media = false)
        mainHandler.removeCallbacks(progressUpdater)
        activityAction.visibility = GONE
        val entry = ongoingEntry(key) ?: return
        val sbn = entry.sbn
        val n = sbn.notification
        val isPrivate = n.visibility == Notification.VISIBILITY_PRIVATE
        val extras = (if (isPrivate) n.publicVersion?.extras else null) ?: n.extras

        val appLabel = appLabel(sbn.packageName)
        val titleText = extras.getCharSequence(Notification.EXTRA_TITLE)
            ?.takeIf { it.isNotBlank() } ?: appLabel
        activityTitle.text = titleText

        try {
            val icon = n.smallIcon?.loadDrawable(context)
            activityIcon.setImageDrawable(icon)
        } catch (t: Throwable) {
            activityIcon.setImageDrawable(null)
        }
        activityIcon.setColorFilter(Color.WHITE)

        if (n.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER) && n.`when` > 0) {
            activityChrono.base =
                SystemClock.elapsedRealtime() - (System.currentTimeMillis() - n.`when`)
            activityChrono.isCountDown = n.extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN)
            activityChrono.visibility = VISIBLE
            activityChrono.start()
            activityText.visibility = GONE
        } else {
            activityChrono.stop()
            activityChrono.visibility = GONE
            val text = if (isPrivate) appLabel
                else extras.getCharSequence(Notification.EXTRA_TEXT)?.takeIf { it.isNotBlank() }
            activityText.text = text
            activityText.visibility = if (text.isNullOrEmpty() || text == titleText) GONE else VISIBLE
        }
    }

    private fun appLabel(pkg: String): CharSequence =
        try {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0))
        } catch (e: PackageManager.NameNotFoundException) {
            pkg
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

    // endregion

    private fun formatTime(ms: Long): String = DateUtils.formatElapsedTime(ms / 1000)

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics)
            .toInt()

    companion object {
        private const val TAG = "NowBarView"
        private const val NOW_BAR_KEY = "system:" + Settings.System.NOW_BAR_ENABLED
        private const val NOW_BAR_STYLE_KEY = "system:" + Settings.System.NOW_BAR_STYLE
        private const val PROGRESS_INTERVAL_MS = 500L
        private const val MORPH_MS = 460L
        private const val BIG_ICON_SCALE = 1.4f

        // Material 3 emphasized motion curves.
        private val EMPHASIZED = PathInterpolator(0.2f, 0f, 0f, 1f)
        private val EMPHASIZED_DECEL = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
        private val EMPHASIZED_ACCEL = PathInterpolator(0.3f, 0f, 0.8f, 0.15f)

        /** The bar whose large player is open, for [consumeOutsideTouch]. */
        private var expandedBar: WeakReference<NowBarView>? = null
        private var swallowingGesture = false

        /**
         * Called by NotificationShadeWindowView for every touch. While the large player is open,
         * a touch that starts outside it closes it, and that whole gesture is consumed so it
         * doesn't also open the bouncer or the shade. Returns true when consumed.
         */
        @JvmStatic
        fun consumeOutsideTouch(ev: MotionEvent): Boolean {
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
                swallowingGesture = false
                val bar = expandedBar?.get() ?: return false
                if (!bar.expanded || !bar.isAttachedToWindow || !bar.isShown) return false
                val loc = IntArray(2)
                bar.getLocationOnScreen(loc)
                val inside = ev.rawX >= loc[0] && ev.rawX < loc[0] + bar.width &&
                    ev.rawY >= loc[1] && ev.rawY < loc[1] + bar.height
                if (inside) return false
                bar.setExpanded(false, animate = true)
                swallowingGesture = true
                return true
            }
            if (!swallowingGesture) return false
            if (ev.actionMasked == MotionEvent.ACTION_UP ||
                ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                swallowingGesture = false
            }
            return true
        }

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
