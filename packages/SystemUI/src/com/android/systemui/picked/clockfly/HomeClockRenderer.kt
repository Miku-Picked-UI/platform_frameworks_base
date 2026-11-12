/*
 * Copyright (C) 2026 The PenguinOS Project
 * SPDX-FileCopyrightText: 2026 Miku-Picked UI
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.picked.clockfly

import android.app.WallpaperColors
import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.LayoutInflater
import android.view.SurfaceControlViewHost
import android.view.View
import android.view.ViewGroup
import com.android.keyguard.ClockEventController
import com.android.systemui.clocks.ClockStyle
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.keyguard.domain.interactor.KeyguardClockInteractor
import com.android.systemui.plugins.keyguard.ui.clocks.ClockController
import com.android.systemui.plugins.keyguard.ui.clocks.ThemeConfig
import com.android.systemui.res.R
import com.android.systemui.shared.clocks.ClockRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Provider
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Draws the lockscreen clock for the launcher's "Lockscreen clock" home screen widget, so the
 * clock that flies home on unlock lands on the very same face.
 *
 * From PenguinOS ("SystemUI: Let a lockscreen clock face hand itself to the launcher", by
 * redducc), without its ColorOS Fluid Clock. The launcher opens a session through
 * CustomizationProvider's [METHOD] call and gets a [Messenger]; SystemUI lays the current clock
 * (the chosen clock style, or the stock clock face) out off screen in a [SurfaceControlViewHost]
 * and sends a snapshot of it whenever it changes and on every minute.
 */
@SysUISingleton
class HomeClockRenderer
@Inject
constructor(
    @Application private val context: Context,
    private val clockRegistry: ClockRegistry,
    private val clockEvents: Provider<ClockEventController>,
    private val clockInteractor: KeyguardClockInteractor,
) {
    private val main = Handler(Looper.getMainLooper())

    fun open(request: Bundle): Bundle? {
        val token = request.getBinder(KEY_HOST_TOKEN)
        val width = request.getInt(KEY_WIDTH)
        val height = request.getInt(KEY_HEIGHT)
        if (width <= 0 || height <= 0) return null
        val displayId = request.getInt(KEY_DISPLAY_ID, Display.DEFAULT_DISPLAY)
        val client = request.getParcelable(KEY_CALLBACK, Messenger::class.java)
        var result: Bundle? = null
        val done = CountDownLatch(1)
        main.post {
            result =
                runCatching { Session(token, displayId, width, height, client).reply() }
                    .onFailure { Log.e(TAG, "Could not open a home clock", it) }
                    .getOrNull()
            done.countDown()
        }
        done.await(2, TimeUnit.SECONDS)
        return result
    }

    private inner class Session(
        token: IBinder?,
        displayId: Int,
        width: Int,
        height: Int,
        private val client: Messenger?,
    ) : Handler.Callback {
        private val display =
            context.getSystemService(DisplayManager::class.java).getDisplay(displayId)
        private val host = SurfaceControlViewHost(context, display, token)
        private val frame = FitFrame(context) { sendState() }
        private val messenger = Messenger(Handler(Looper.getMainLooper(), this))
        private var clock: ClockController? = null
        private var events: ClockEventController? = null
        private var closed = false

        private val ticker =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    clock?.largeClock?.events?.onTimeTick()
                    frame.postDelayed({ sendState() }, TICK_SETTLE_MS)
                }
            }

        private val styleObserver =
            object : ContentObserver(main) {
                override fun onChange(selfChange: Boolean) = build()
            }

        private val faceListener =
            object : ClockRegistry.ClockChangeListener {
                override fun onCurrentClockChanged() {
                    main.post { build() }
                }
            }

        init {
            build()
            host.setView(frame, width, height)
            val filter =
                IntentFilter().apply {
                    addAction(Intent.ACTION_TIME_TICK)
                    addAction(Intent.ACTION_TIME_CHANGED)
                    addAction(Intent.ACTION_TIMEZONE_CHANGED)
                }
            context.registerReceiver(ticker, filter, Context.RECEIVER_EXPORTED)
            context.contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(ClockStyle.CLOCK_STYLE_KEY),
                false,
                styleObserver,
            )
            clockRegistry.registerClockChangeListener(faceListener)
            runCatching { client?.binder?.linkToDeath({ main.post { close() } }, 0) }
        }

        fun reply(): Bundle =
            Bundle().apply {
                putParcelable(KEY_SURFACE_PACKAGE, host.surfacePackage)
                putParcelable(KEY_CALLBACK, messenger)
            }

        private fun build() {
            if (closed) return
            frame.removeAllViews()
            events?.clock = null
            events?.unregisterListeners()
            events = null
            clock = null
            val style =
                Settings.Secure.getInt(context.contentResolver, ClockStyle.CLOCK_STYLE_KEY, 0)
            if (style != 0) {
                val view =
                    LayoutInflater.from(context).inflate(R.layout.keyguard_clock_style, null)
                        as ClockStyle
                view.forHome = true
                frame.show(view, null) { view.clockView }
            } else {
                val face = clockRegistry.createCurrentClock(context)
                face.largeClock.events.onThemeChanged(
                    ThemeConfig(wallpaperIsDark(), clockRegistry.seedColor)
                )
                events =
                    clockEvents.get().also {
                        it.isPreview = true
                        it.registerListeners()
                        it.clock = face
                    }
                face.largeClock.events.onTimeTick()
                clock = face
                val lockscreen = clockInteractor.currentClock.value?.largeClock?.view
                val size = lockscreen?.takeIf { it.width > 0 }?.let { it.width to it.height }
                (face.largeClock.view.parent as? ViewGroup)?.removeView(face.largeClock.view)
                frame.show(face.largeClock.view, size) { face.largeClock.view }
            }
            sendState()
        }

        private fun sendState() {
            val client = client ?: return
            val state =
                Bundle().apply {
                    frame.contentRect()?.let { putParcelable(KEY_CLOCK_RECT, it) }
                    frame.snapshot()?.let { putParcelable(KEY_SNAPSHOT, it) }
                }
            try {
                client.send(Message.obtain(null, MSG_STATE).apply { data = state })
            } catch (e: RemoteException) {
                main.post { close() }
            }
        }

        override fun handleMessage(message: Message): Boolean {
            when (message.what) {
                MSG_RESIZE -> {
                    val width = message.arg1
                    val height = message.arg2
                    if (width > 0 && height > 0 && !closed) host.relayout(width, height)
                }
                MSG_DESTROY -> close()
            }
            return true
        }

        private fun close() {
            if (closed) return
            closed = true
            runCatching { context.unregisterReceiver(ticker) }
            context.contentResolver.unregisterContentObserver(styleObserver)
            clockRegistry.unregisterClockChangeListener(faceListener)
            events?.clock = null
            events?.unregisterListeners()
            host.release()
        }

        private fun wallpaperIsDark(): Boolean {
            val colors =
                WallpaperManager.getInstance(context)
                    .getWallpaperColors(WallpaperManager.FLAG_SYSTEM) ?: return true
            return colors.colorHints and WallpaperColors.HINT_SUPPORTS_DARK_TEXT == 0
        }
    }

    /** Fits the clock (not its whole frame) into the widget, centred. */
    private class FitFrame(context: Context, private val onLaidOut: () -> Unit) :
        ViewGroup(context) {
        private var face: View? = null
        private var faceSize: Pair<Int, Int>? = null
        private var content: () -> View? = { null }

        init {
            clipChildren = false
        }

        fun show(view: View, size: Pair<Int, Int>?, clock: () -> View?) {
            face = view
            faceSize = size
            content = clock
            addView(view)
        }

        override fun removeAllViews() {
            super.removeAllViews()
            face = null
            faceSize = null
            content = { null }
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val screen = resources.displayMetrics
            val size = faceSize
            face?.measure(
                MeasureSpec.makeMeasureSpec(size?.first ?: screen.widthPixels, MeasureSpec.EXACTLY),
                if (size != null) MeasureSpec.makeMeasureSpec(size.second, MeasureSpec.EXACTLY)
                else MeasureSpec.makeMeasureSpec(screen.heightPixels, MeasureSpec.AT_MOST),
            )
            setMeasuredDimension(
                MeasureSpec.getSize(widthMeasureSpec),
                MeasureSpec.getSize(heightMeasureSpec),
            )
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val face = face ?: return
            face.layout(0, 0, face.measuredWidth, face.measuredHeight)
            val clock = clockRect(face) ?: Rect(0, 0, face.measuredWidth, face.measuredHeight)
            if (clock.isEmpty) return
            val scale = min(width / clock.width().toFloat(), height / clock.height().toFloat())
            face.pivotX = 0f
            face.pivotY = 0f
            face.scaleX = scale
            face.scaleY = scale
            face.translationX = (width - clock.width() * scale) / 2f - clock.left * scale
            face.translationY = (height - clock.height() * scale) / 2f - clock.top * scale
            post(onLaidOut)
        }

        private fun clockRect(face: View): Rect? {
            val clock = content() ?: return null
            val rect = Rect(0, 0, clock.width, clock.height)
            var view: View? = clock
            while (view != null && view !== face) {
                rect.offset(
                    (view.left + view.translationX).roundToInt(),
                    (view.top + view.translationY).roundToInt(),
                )
                view = view.parent as? View
            }
            return rect
        }

        fun contentRect(): Rect? {
            val face = face ?: return null
            val clock = clockRect(face) ?: return null
            val s = face.scaleX
            return Rect(
                (face.translationX + clock.left * s).roundToInt(),
                (face.translationY + clock.top * s).roundToInt(),
                (face.translationX + clock.right * s).roundToInt(),
                (face.translationY + clock.bottom * s).roundToInt(),
            )
        }

        fun snapshot(): Bitmap? {
            val clock = content() ?: return null
            if (clock.width <= 0 || clock.height <= 0) return null
            return runCatching {
                    Bitmap.createBitmap(clock.width, clock.height, Bitmap.Config.ARGB_8888).also {
                        clock.draw(Canvas(it))
                    }
                }
                .getOrNull()
        }
    }

    companion object {
        private const val TAG = "HomeClockRenderer"
        const val METHOD = "home_clock"
        const val KEY_HOST_TOKEN = "host_token"
        const val KEY_WIDTH = "width"
        const val KEY_HEIGHT = "height"
        const val KEY_DISPLAY_ID = "display_id"
        const val KEY_CALLBACK = "callback"
        const val KEY_SURFACE_PACKAGE = "surface_package"
        const val KEY_CLOCK_RECT = "clock_rect"
        const val KEY_SNAPSHOT = "snapshot"
        const val MSG_STATE = 1
        const val MSG_RESIZE = 2
        const val MSG_DESTROY = 3
        private const val TICK_SETTLE_MS = 300L

        /** Settings.Secure: 1 lets the lockscreen clock fly onto the home screen on unlock. */
        const val SETTING_ENABLED = "picked_clock_fly"

        fun isEnabled(context: Context): Boolean =
            Settings.Secure.getInt(context.contentResolver, SETTING_ENABLED, 0) != 0
    }
}
