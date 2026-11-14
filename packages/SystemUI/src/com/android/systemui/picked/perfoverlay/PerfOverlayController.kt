/*
 * SPDX-FileCopyrightText: 2026 Miku-Picked UI
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.picked.perfoverlay

import android.app.ActivityManager
import android.app.ActivityTaskManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Handler
import android.os.IThermalService
import android.os.ServiceManager
import android.os.Temperature
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.TextView
import android.window.TaskFpsCallback
import androidx.core.graphics.ColorUtils
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.shared.system.TaskStackChangeListener
import com.android.systemui.shared.system.TaskStackChangeListeners
import java.io.File
import java.util.Locale
import java.util.concurrent.Executor
import javax.inject.Inject
import kotlin.math.roundToInt

/**
 * Miku-Picked UI: a small performance overlay in the top-left corner, after the FPS and CPU
 * info overlays of crDroid / Evolution X / Lunaris.
 *
 * Settings.System [SETTING]: 0 off, 1 FPS only, 2 FPS + CPU clusters, GPU clock and load and
 * temperatures. Unlike the donors it doesn't read a Qualcomm display node: the FPS is
 * SurfaceFlinger's count for the app in front (WindowManager task FPS callback), the
 * temperatures come from the thermal HAL, the CPU clocks from cpufreq and the GPU clock and
 * load from the Mali driver (/sys/kernel/gpu, where present).
 */
@SysUISingleton
class PerfOverlayController
@Inject
constructor(
    private val context: Context,
    @Main private val mainHandler: Handler,
    @Background private val bgHandler: Handler,
    @Main private val mainExecutor: Executor,
) : CoreStartable {

    private val windowManager = context.getSystemService(WindowManager::class.java)!!
    private var mode = 0
    private var screenOn = true
    private var view: TextView? = null

    private var fps = -1
    private var fpsTaskId = -1
    private val fpsCallback =
        object : TaskFpsCallback() {
            override fun onFpsReported(value: Float) {
                fps = value.roundToInt()
            }
        }
    private var fpsRegistered = false

    private val taskListener =
        object : TaskStackChangeListener {
            override fun onTaskMovedToFront(taskInfo: ActivityManager.RunningTaskInfo) {
                mainHandler.post { watchTask(taskInfo.taskId) }
            }
        }

    private val thermal: IThermalService? by lazy {
        IThermalService.Stub.asInterface(ServiceManager.getService(Context.THERMAL_SERVICE))
    }
    private val cpuPolicies: List<File> by lazy {
        File("/sys/devices/system/cpu/cpufreq")
            .listFiles { f -> f.name.startsWith("policy") }
            ?.sortedBy { it.name.removePrefix("policy").toIntOrNull() ?: 0 }
            .orEmpty()
    }

    private val tick =
        object : Runnable {
            override fun run() {
                bgHandler.removeCallbacks(this)
                if (mode == 0 || !screenOn) return
                val text = buildText()
                mainHandler.post { view?.text = text }
                bgHandler.postDelayed(this, INTERVAL_MS)
            }
        }

    override fun start() {
        val observer =
            object : ContentObserver(mainHandler) {
                override fun onChange(selfChange: Boolean) = update()
            }
        context.contentResolver.registerContentObserver(
            Settings.System.getUriFor(SETTING),
            false,
            observer,
            UserHandle.USER_ALL,
        )
        val filter =
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
        context.registerReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    screenOn = intent.action == Intent.ACTION_SCREEN_ON
                    update()
                }
            },
            filter,
            null,
            mainHandler,
        )
        update()
    }

    private fun update() {
        mode =
            Settings.System.getIntForUser(
                context.contentResolver,
                SETTING,
                0,
                UserHandle.USER_CURRENT,
            )
        if (mode != 0 && screenOn) show() else hide()
    }

    private fun show() {
        if (view == null) {
            val v =
                TextView(context).apply {
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    typeface = Typeface.MONOSPACE
                    setBackgroundColor(ColorUtils.setAlphaComponent(Color.BLACK, 120))
                    val pad = (4 * resources.displayMetrics.density).roundToInt()
                    setPadding(pad * 2, pad, pad * 2, pad)
                }
            try {
                windowManager.addView(v, layoutParams())
                view = v
            } catch (e: Exception) {
                Log.e(TAG, "Can't add the overlay", e)
                return
            }
            TaskStackChangeListeners.getInstance().registerTaskStackListener(taskListener)
            val top = ActivityTaskManager.getInstance().getTasks(1).firstOrNull()
            if (top != null) watchTask(top.taskId)
        }
        bgHandler.post(tick)
    }

    private fun hide() {
        bgHandler.removeCallbacks(tick)
        val v = view ?: return
        view = null
        TaskStackChangeListeners.getInstance().unregisterTaskStackListener(taskListener)
        unwatchTask()
        try {
            windowManager.removeViewImmediate(v)
        } catch (_: Exception) {}
    }

    private fun layoutParams() =
        WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_SECURE_SYSTEM_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            )
            .apply {
                gravity = Gravity.TOP or Gravity.START
                title = "PerfOverlay"
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                y =
                    windowManager.currentWindowMetrics.windowInsets
                        .getInsetsIgnoringVisibility(WindowInsets.Type.statusBars())
                        .top
            }

    private fun watchTask(taskId: Int) {
        if (view == null || taskId == fpsTaskId) return
        unwatchTask()
        try {
            windowManager.registerTaskFpsCallback(taskId, mainExecutor, fpsCallback)
            fpsRegistered = true
            fpsTaskId = taskId
        } catch (e: Exception) {
            // The task is gone already, or SurfaceFlinger can't count it.
            Log.w(TAG, "No FPS for task $taskId: $e")
        }
    }

    private fun unwatchTask() {
        if (fpsRegistered) {
            try {
                windowManager.unregisterTaskFpsCallback(fpsCallback)
            } catch (_: Exception) {}
            fpsRegistered = false
        }
        fpsTaskId = -1
        fps = -1
    }

    // ---- What the overlay shows (background thread) ----------------------------------------

    private fun buildText(): String {
        val fpsText = if (fps >= 0) "$fps FPS" else "-- FPS"
        if (mode == 1) return fpsText

        val clusters =
            cpuPolicies.mapNotNull { p ->
                readInt(File(p, "scaling_cur_freq"))?.let {
                    String.format(Locale.US, "%.2f", it / 1_000_000f)
                }
            }
        val temps = temperatures()
        val line1 = buildString {
            append(fpsText)
            if (clusters.isNotEmpty()) {
                append(" · CPU ").append(clusters.joinToString(" ")).append(" GHz")
            }
            temps[Temperature.TYPE_CPU]?.let { append(" · ").append(it).append("°C") }
        }
        val line2 = buildString {
            val gpuClock = readInt(File("/sys/kernel/gpu/gpu_clock"))
            val gpuBusy = readLine(File("/sys/kernel/gpu/gpu_busy"))?.trim()
            if (gpuClock != null) {
                append("GPU ").append(gpuClock / 1000).append(" MHz")
                if (!gpuBusy.isNullOrEmpty()) append(" ").append(gpuBusy.replace(" ", ""))
            }
            temps[Temperature.TYPE_GPU]?.let {
                if (isNotEmpty()) append(" · ")
                append("GPU ").append(it).append("°C")
            }
            temps[Temperature.TYPE_BATTERY]?.let {
                if (isNotEmpty()) append(" · ")
                append("BAT ").append(it).append("°C")
            }
        }
        return if (line2.isEmpty()) line1 else "$line1\n$line2"
    }

    /** The hottest sensor of each type, rounded, from the thermal HAL. */
    private fun temperatures(): Map<Int, Int> {
        val out = HashMap<Int, Int>()
        try {
            thermal?.currentTemperatures?.forEach { t ->
                val value = t.value
                if (value.isNaN() || value <= -100f) return@forEach
                val rounded = value.roundToInt()
                val prev = out[t.type]
                if (prev == null || rounded > prev) out[t.type] = rounded
            }
        } catch (e: Exception) {
            Log.w(TAG, "Thermal HAL unavailable: $e")
        }
        return out
    }

    private fun readLine(file: File): String? =
        try {
            file.bufferedReader().use { it.readLine() }
        } catch (_: Exception) {
            null
        }

    private fun readInt(file: File): Int? = readLine(file)?.trim()?.toIntOrNull()

    companion object {
        private const val TAG = "PerfOverlay"
        private const val INTERVAL_MS = 1000L

        /** Settings.System: 0 off, 1 FPS only, 2 full. */
        const val SETTING = "picked_perf_overlay"
    }
}
