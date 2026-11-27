/*
 * SPDX-FileCopyrightText: 2026 Miku-Picked UI
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.picked.sidebar

import android.app.ActivityManager
import android.app.ActivityOptions
import android.app.WindowConfiguration
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.database.ContentObserver
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import com.android.internal.graphics.picked.GlassLayer
import com.android.internal.graphics.picked.GlassSurface
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.statusbar.policy.KeyguardStateController
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Miku-Picked UI: Glass Sidebar — a Liquid Glass edge handle that opens a floating app panel to
 * launch apps directly into freeform windows (tap for freeform, long-press for fullscreen; hold
 * and drag the handle vertically to reposition it).
 *
 * Settings.Secure:
 *  - [SETTING_ENABLED]: 0 off (default), 1 on.
 *  - [SETTING_SIDE]: 0 left edge, 1 right edge (default).
 *  - [SETTING_POS]: vertical position percent along the screen edge (15..85, default 35).
 */
@SysUISingleton
class GlassSidebarController
@Inject
constructor(
    private val context: Context,
    @Main private val mainHandler: Handler,
    @Background private val bgHandler: Handler,
    private val keyguardStateController: KeyguardStateController,
) : CoreStartable {

    private val windowManager = context.getSystemService(WindowManager::class.java)!!
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private var enabled = false
    private var onRight = true
    private var verticalPercent = DEFAULT_POS_PERCENT
    private var screenOn = true

    private var handleView: FrameLayout? = null
    private var handleParams: WindowManager.LayoutParams? = null
    private var panelOverlay: FrameLayout? = null

    private data class AppEntry(
        val component: ComponentName,
        val label: CharSequence,
        val icon: Drawable,
    )

    @Volatile private var cachedApps: List<AppEntry> = emptyList()

    private val keyguardCallback =
        object : KeyguardStateController.Callback {
            override fun onKeyguardShowingChanged() {
                mainHandler.post { updateHandleVisibility() }
            }
        }

    override fun start() {
        val observer =
            object : ContentObserver(mainHandler) {
                override fun onChange(selfChange: Boolean) = refreshSettings()
            }
        val resolver = context.contentResolver
        for (key in arrayOf(SETTING_ENABLED, SETTING_SIDE, SETTING_POS)) {
            resolver.registerContentObserver(
                Settings.Secure.getUriFor(key),
                false,
                observer,
                UserHandle.USER_ALL,
            )
        }

        keyguardStateController.addCallback(keyguardCallback)

        val screenFilter =
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
        context.registerReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    screenOn = intent.action == Intent.ACTION_SCREEN_ON
                    if (!screenOn) dismissPanel()
                    updateHandleVisibility()
                }
            },
            screenFilter,
        )

        val pkgFilter =
            IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_CHANGED)
                addDataScheme("package")
            }
        context.registerReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    if (enabled) reloadAppsAsync()
                }
            },
            pkgFilter,
        )

        refreshSettings()
    }

    private fun refreshSettings() {
        val resolver = context.contentResolver
        val newEnabled =
            Settings.Secure.getIntForUser(resolver, SETTING_ENABLED, 0, UserHandle.USER_CURRENT) !=
                0
        val newOnRight =
            Settings.Secure.getIntForUser(resolver, SETTING_SIDE, 1, UserHandle.USER_CURRENT) != 0
        val newPos =
            Settings.Secure.getIntForUser(
                    resolver,
                    SETTING_POS,
                    DEFAULT_POS_PERCENT,
                    UserHandle.USER_CURRENT,
                )
                .coerceIn(MIN_POS_PERCENT, MAX_POS_PERCENT)

        val wasEnabled = enabled
        val layoutChanged = newOnRight != onRight || newPos != verticalPercent
        enabled = newEnabled
        onRight = newOnRight
        verticalPercent = newPos

        if (enabled && (!wasEnabled || cachedApps.isEmpty())) {
            reloadAppsAsync()
        }
        if (!enabled) {
            dismissPanel()
            removeHandle()
            return
        }
        if (layoutChanged && handleView != null) {
            removeHandle()
        }
        updateHandleVisibility()
    }

    private fun updateHandleVisibility() {
        val shouldShow = enabled && screenOn && !keyguardStateController.isShowing
        if (!shouldShow) {
            dismissPanel()
            removeHandle()
        } else if (handleView == null) {
            attachHandle()
        }
    }

    private fun isDarkMode(): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    private fun dp(value: Float): Int = (value * context.resources.displayMetrics.density).roundToInt()

    private fun screenBounds(): Rect = windowManager.currentWindowMetrics.bounds

    private fun attachHandle() {
        if (handleView != null) return
        val bounds = screenBounds()
        val handleWidth = dp(18f)
        val handleHeight = dp(84f)
        val pillWidth = dp(4.5f)
        val pillHeight = dp(64f)

        val container = FrameLayout(context)
        val pill =
            View(context).apply {
                background =
                    GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dp(4f).toFloat()
                        setColor(if (isDarkMode()) 0xB8FFFFFF.toInt() else 0xA81C1C1E.toInt())
                    }
            }
        val pillLp =
            FrameLayout.LayoutParams(
                pillWidth,
                pillHeight,
                (if (onRight) Gravity.END else Gravity.START) or Gravity.CENTER_VERTICAL,
            ).apply {
                if (onRight) marginEnd = dp(3f) else marginStart = dp(3f)
            }
        container.addView(pill, pillLp)
        GlassLayer.attach(
            pill,
            GlassLayer.SIDEBAR,
            HANDLE_TINT,
            GlassSurface.LIVE or GlassSurface.RIM_ON_TOP,
        )

        val yPos = ((bounds.height() * verticalPercent) / 100f - handleHeight / 2f).roundToInt()
        val lp =
            WindowManager.LayoutParams(
                    handleWidth,
                    handleHeight,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT,
                )
                .apply {
                    gravity = (if (onRight) Gravity.END else Gravity.START) or Gravity.TOP
                    x = 0
                    y = yPos.coerceIn(dp(48f), (bounds.height() - handleHeight - dp(48f)).coerceAtLeast(dp(48f)))
                    title = "GlassSidebarHandle"
                }

        attachHandleTouch(container, lp)

        try {
            windowManager.addView(container, lp)
            handleView = container
            handleParams = lp
        } catch (e: RuntimeException) {
            Log.w(TAG, "Failed to add Glass Sidebar handle", e)
        }
    }

    private fun attachHandleTouch(container: FrameLayout, lp: WindowManager.LayoutParams) {
        var downRawX = 0f
        var downRawY = 0f
        var startY = 0
        var draggingVertically = false
        var opened = false
        val longPressRunnable = Runnable {
            draggingVertically = true
            container.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }

        container.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startY = lp.y
                    draggingVertically = false
                    opened = false
                    mainHandler.postDelayed(longPressRunnable, LONG_PRESS_MOVE_MS)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (draggingVertically) {
                        val h = screenBounds().height()
                        val minY = dp(48f)
                        val maxY = (h - lp.height - dp(48f)).coerceAtLeast(minY)
                        lp.y = (startY + dy.roundToInt()).coerceIn(minY, maxY)
                        try {
                            windowManager.updateViewLayout(v, lp)
                        } catch (_: RuntimeException) {}
                        true
                    } else {
                        val inward = if (onRight) -dx else dx
                        if (abs(dx) > touchSlop || abs(dy) > touchSlop) {
                            mainHandler.removeCallbacks(longPressRunnable)
                        }
                        if (!opened && inward > dp(24f)) {
                            opened = true
                            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            openPanel()
                        }
                        true
                    }
                }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    mainHandler.removeCallbacks(longPressRunnable)
                    if (draggingVertically) {
                        val h = screenBounds().height().coerceAtLeast(1)
                        val centerY = lp.y + lp.height / 2f
                        val pct = ((centerY / h) * 100f).roundToInt().coerceIn(MIN_POS_PERCENT, MAX_POS_PERCENT)
                        verticalPercent = pct
                        Settings.Secure.putIntForUser(
                            context.contentResolver,
                            SETTING_POS,
                            pct,
                            UserHandle.USER_CURRENT,
                        )
                    } else if (!opened && event.actionMasked == MotionEvent.ACTION_UP) {
                        val dx = abs(event.rawX - downRawX)
                        val dy = abs(event.rawY - downRawY)
                        if (dx <= touchSlop && dy <= touchSlop) {
                            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            openPanel()
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun removeHandle() {
        val v = handleView ?: return
        handleView = null
        handleParams = null
        try {
            windowManager.removeViewImmediate(v)
        } catch (_: RuntimeException) {}
    }

    private fun openPanel() {
        if (panelOverlay != null) {
            dismissPanel()
            return
        }
        val apps = cachedApps
        if (apps.isEmpty()) {
            reloadAppsAsync { mainHandler.post { if (enabled) openPanel() } }
            return
        }

        val bounds = screenBounds()
        val dark = isDarkMode()

        val root =
            object : FrameLayout(context) {
                override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                    if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                        dismissPanel()
                        return true
                    }
                    return super.dispatchKeyEvent(event)
                }
            }
        root.setOnClickListener { dismissPanel() }

        val cardBg =
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(24f).toFloat()
                setColor(if (dark) 0xCC1E1E24.toInt() else 0xDCF5F6FA.toInt())
                setStroke(dp(1f), if (dark) 0x33FFFFFF else 0x22000000)
            }

        val card =
            FrameLayout(context).apply {
                background = cardBg
                elevation = dp(12f).toFloat()
                setPadding(dp(8f), dp(12f), dp(8f), dp(12f))
                isClickable = true
            }
        GlassLayer.attach(
            card,
            GlassLayer.SIDEBAR,
            PANEL_TINT,
            GlassSurface.LIVE or GlassSurface.RIM_ON_TOP,
        )

        val column =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
            }

        val iconSize = dp(44f)
        val iconPad = dp(4f)
        val itemGap = dp(6f)
        for (app in apps) {
            val iconView =
                ImageView(context).apply {
                    setImageDrawable(app.icon)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    contentDescription = app.label
                    setPadding(iconPad, iconPad, iconPad, iconPad)
                    setOnClickListener {
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        dismissPanel()
                        launchApp(app.component, freeform = true)
                    }
                    setOnLongClickListener {
                        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        dismissPanel()
                        launchApp(app.component, freeform = false)
                        true
                    }
                }
            val itemLp =
                LinearLayout.LayoutParams(iconSize, iconSize).apply {
                    topMargin = itemGap / 2
                    bottomMargin = itemGap / 2
                }
            column.addView(iconView, itemLp)
        }

        val scroll =
            ScrollView(context).apply {
                isVerticalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                addView(
                    column,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                    ),
                )
            }
        card.addView(
            scroll,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        val maxCardHeight = (bounds.height() * 0.62f).roundToInt()
        val cardLp =
            FrameLayout.LayoutParams(
                dp(68f),
                maxCardHeight,
                (if (onRight) Gravity.END else Gravity.START) or Gravity.CENTER_VERTICAL,
            ).apply {
                if (onRight) marginEnd = dp(10f) else marginStart = dp(10f)
            }
        root.addView(card, cardLp)

        val overlayLp =
            WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                    PixelFormat.TRANSLUCENT,
                )
                .apply {
                    title = "GlassSidebarPanel"
                }

        try {
            windowManager.addView(root, overlayLp)
            panelOverlay = root
            val slideFrom = if (onRight) dp(48f).toFloat() else -dp(48f).toFloat()
            card.translationX = slideFrom
            card.alpha = 0f
            card.animate().translationX(0f).alpha(1f).setDuration(180L).start()
        } catch (e: RuntimeException) {
            Log.w(TAG, "Failed to open Glass Sidebar panel", e)
        }
    }

    private fun dismissPanel() {
        val overlay = panelOverlay ?: return
        panelOverlay = null
        try {
            windowManager.removeViewImmediate(overlay)
        } catch (_: RuntimeException) {}
    }

    private fun launchApp(component: ComponentName, freeform: Boolean) {
        val intent =
            Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                this.component = component
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                )
            }
        try {
            if (freeform) {
                ensureFreeformEnabled()
                val bounds = screenBounds()
                val w = (bounds.width() * 0.80f).roundToInt()
                val h = (bounds.height() * 0.56f).roundToInt()
                val left = (bounds.width() - w) / 2
                val top = (bounds.height() - h) / 2
                val options =
                    ActivityOptions.makeBasic().apply {
                        setLaunchWindowingMode(WindowConfiguration.WINDOWING_MODE_FREEFORM)
                        setLaunchBounds(Rect(left, top, left + w, top + h))
                    }
                context.startActivityAsUser(intent, options.toBundle(), UserHandle.CURRENT)
            } else {
                context.startActivityAsUser(intent, UserHandle.CURRENT)
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "Failed to launch $component (freeform=$freeform)", e)
        }
    }

    private fun ensureFreeformEnabled() {
        val resolver = context.contentResolver
        if (
            Settings.Global.getInt(
                resolver,
                Settings.Global.DEVELOPMENT_ENABLE_FREEFORM_WINDOWS_SUPPORT,
                0,
            ) == 0
        ) {
            try {
                Settings.Global.putInt(
                    resolver,
                    Settings.Global.DEVELOPMENT_ENABLE_FREEFORM_WINDOWS_SUPPORT,
                    1,
                )
            } catch (_: RuntimeException) {}
        }
    }

    private fun reloadAppsAsync(onLoaded: (() -> Unit)? = null) {
        bgHandler.post {
            val pm = context.packageManager
            val query =
                Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
            val resolved =
                pm.queryIntentActivitiesAsUser(
                    query,
                    PackageManager.ResolveInfoFlags.of(0L),
                    ActivityManager.getCurrentUser(),
                )
            val list =
                resolved
                    .asSequence()
                    .filter { it.activityInfo != null && it.activityInfo.packageName != context.packageName }
                    .map { info ->
                        AppEntry(
                            component =
                                ComponentName(info.activityInfo.packageName, info.activityInfo.name),
                            label = info.loadLabel(pm),
                            icon = info.loadIcon(pm),
                        )
                    }
                    .sortedBy { it.label.toString().lowercase() }
                    .toList()
            cachedApps = list
            onLoaded?.invoke()
        }
    }

    companion object {
        private const val TAG = "GlassSidebar"
        const val SETTING_ENABLED = "picked_glass_sidebar"
        const val SETTING_SIDE = "picked_glass_sidebar_side"
        const val SETTING_POS = "picked_glass_sidebar_pos"
        private const val DEFAULT_POS_PERCENT = 35
        private const val MIN_POS_PERCENT = 15
        private const val MAX_POS_PERCENT = 85
        private const val LONG_PRESS_MOVE_MS = 380L
        private const val HANDLE_TINT = 0.55f
        private const val PANEL_TINT = 0.45f
    }
}
