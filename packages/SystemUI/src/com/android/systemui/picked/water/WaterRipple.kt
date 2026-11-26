/*
 * SPDX-FileCopyrightText: 2026 DrDisagree, Miku-Picked UI
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Glass Layer's water ripple (github.com/Mahmud0808/GlassLayer, GPL-3.0: UnlockRippleView,
 * RippleOverlay), as a SystemUI animation: a full-screen overlay where the screen under it ripples
 * like water from a point. The module captures the screen through its BackdropCapture; here the
 * overlay captures the display itself every frame at 40 % (window manager captureDisplay without
 * its own layer, as ColorCC does) while the ripple runs, about 1.4 s.
 */

package com.android.systemui.picked.water

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.PointF
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.Choreographer
import android.view.View
import android.view.WindowManager
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/** Plays the water ripple over the whole screen. */
object WaterRipple {

    private const val TAG = "PickedWaterRipple"
    private val main = Handler(Looper.getMainLooper())
    private var current: WaterRippleView? = null

    val isShowing: Boolean
        get() = current != null

    /** Starts a ripple from [origin] (screen pixels); [durationScale] 1 = 1.4 s. */
    @JvmStatic
    fun play(context: Context, origin: PointF, durationScale: Float = 1f) {
        cancel()
        val manager = context.getSystemService(WindowManager::class.java) ?: return
        val view = WaterRippleView(context, origin, durationScale) { finished -> remove(manager, finished) }
        try {
            manager.addView(view, layoutParams(context))
            current = view
        } catch (e: RuntimeException) {
            Log.w(TAG, "Can't show the water ripple", e)
        }
    }

    @JvmStatic
    fun cancel() {
        current?.finish()
    }

    /** The middle of the screen, for a ripple with no better origin. */
    @JvmStatic
    fun screenCenter(context: Context): PointF {
        val bounds = context.getSystemService(WindowManager::class.java)?.maximumWindowMetrics?.bounds
        return PointF(bounds?.exactCenterX() ?: 0f, bounds?.exactCenterY() ?: 0f)
    }

    private fun remove(manager: WindowManager, view: WaterRippleView) {
        if (current === view) current = null
        main.post {
            try {
                if (view.isAttachedToWindow) manager.removeViewImmediate(view)
            } catch (e: RuntimeException) {
                Log.w(TAG, "Can't remove the water ripple", e)
            }
        }
    }

    private fun layoutParams(context: Context) =
        WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_SECURE_SYSTEM_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT,
            )
            .apply {
                title = "PickedWaterRipple"
                packageName = context.opPackageName
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                fitInsetsTypes = 0
            }
}

@SuppressLint("ViewConstructor")
internal class WaterRippleView(
    context: Context,
    private val origin: PointF,
    private val durationScale: Float,
    private val onFinished: (WaterRippleView) -> Unit,
) : View(context), Choreographer.FrameCallback {

    private val shader = RuntimeShader(WaterRippleShader.SOURCE)
    private val paint = Paint().apply { shader = this@WaterRippleView.shader }
    private val empty =
        BitmapShader(
            Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888),
            Shader.TileMode.CLAMP,
            Shader.TileMode.CLAMP,
        )
    private val surface = WaterSurface()
    private val fieldMatrix = Matrix()
    private val backdropMatrix = Matrix()
    private val screen = IntArray(2)
    private var fieldReady = false
    private var startNanos = 0L
    private var lastNanos = 0L
    private var elapsedSeconds = 0f
    private var finished = false

    // Captures run on their own thread; the newest one is drawn.
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private val inFlight = AtomicBoolean(false)
    @Volatile private var backdrop: Bitmap? = null
    private var boundBackdrop: Bitmap? = null

    init {
        setWillNotDraw(false)
        shader.setInputShader(WaterRippleShader.BACKDROP, empty)
        shader.setInputShader(WaterRippleShader.FIELD, empty)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        captureThread = HandlerThread("PickedWaterCapture").also {
            it.start()
            captureHandler = Handler(it.looper)
        }
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onDetachedFromWindow() {
        Choreographer.getInstance().removeFrameCallback(this)
        captureThread?.quitSafely()
        captureThread = null
        captureHandler = null
        backdrop = null
        boundBackdrop = null
        surface.release()
        super.onDetachedFromWindow()
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (finished) return
        try {
            if (!fieldReady && width > 0 && height > 0) prepareField()
            if (startNanos == 0L) {
                startNanos = frameTimeNanos
                lastNanos = frameTimeNanos
            }
            val duration = max(DURATION_MS * durationScale, 1f)
            elapsedSeconds = (frameTimeNanos - startNanos) / NANOS
            if (elapsedSeconds * MILLIS >= duration) {
                finish()
                return
            }
            if (fieldReady) {
                surface.advance((frameTimeNanos - lastNanos) / NANOS, REFERENCE_MS / duration)
                surface.upload()
            }
            lastNanos = frameTimeNanos
            requestCapture()
            invalidate()
            Choreographer.getInstance().postFrameCallback(this)
        } catch (e: RuntimeException) {
            Log.w("PickedWaterRipple", "Water ripple failed", e)
            finish()
        }
    }

    fun finish() {
        if (finished) return
        finished = true
        Choreographer.getInstance().removeFrameCallback(this)
        onFinished(this)
    }

    private fun prepareField() {
        getLocationOnScreen(screen)
        surface.reset(width, height)
        surface.impulse(origin.x - screen[0], origin.y - screen[1])
        surface.upload()
        val bitmap = surface.bitmap ?: return
        surface.fieldMatrix(fieldMatrix)
        val field =
            BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                filterMode = BitmapShader.FILTER_MODE_LINEAR
                setLocalMatrix(fieldMatrix)
            }
        shader.setInputShader(WaterRippleShader.FIELD, field)
        fieldReady = true
    }

    /** The screen under this overlay, at [CAPTURE_SCALE], without the overlay itself. */
    private fun requestCapture() {
        val handler = captureHandler ?: return
        val control = viewRootImpl?.surfaceControl ?: return
        if (!control.isValid || !inFlight.compareAndSet(false, true)) return
        val displayId = display?.displayId ?: 0
        handler.post {
            try {
                val bitmap = WaterCapture.capture(displayId, control, CAPTURE_SCALE)
                if (bitmap != null) {
                    backdrop = bitmap
                    postInvalidateOnAnimation()
                }
            } catch (e: Exception) {
                Log.w("PickedWaterRipple", "Screen capture failed", e)
            } finally {
                inFlight.set(false)
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (!fieldReady || width <= 0 || height <= 0) return
        val frame = backdrop ?: return
        if (frame !== boundBackdrop) {
            val bitmapShader =
                BitmapShader(frame, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                    filterMode = BitmapShader.FILTER_MODE_LINEAR
                }
            // The capture is the whole display at CAPTURE_SCALE; map it onto this window.
            val real = Point()
            display?.getRealSize(real)
            val scaleX = if (frame.width > 0) real.x.toFloat() / frame.width else 1f / CAPTURE_SCALE
            val scaleY = if (frame.height > 0) real.y.toFloat() / frame.height else 1f / CAPTURE_SCALE
            backdropMatrix.setScale(scaleX, scaleY)
            backdropMatrix.postTranslate(-screen[0].toFloat(), -screen[1].toFloat())
            bitmapShader.setLocalMatrix(backdropMatrix)
            shader.setInputShader(WaterRippleShader.BACKDROP, bitmapShader)
            boundBackdrop = frame
        }
        val duration = max(DURATION_MS * durationScale, 1f) / MILLIS
        shader.setFloatUniform(WaterRippleShader.PROGRESS, (elapsedSeconds / duration).coerceIn(0f, 1f))
        shader.setFloatUniform(
            WaterRippleShader.REFRACTION,
            REFRACTION_DP * resources.displayMetrics.density,
        )
        shader.setFloatUniform(WaterRippleShader.HAS_BACKDROP, 1f)
        shader.setFloatUniform(WaterRippleShader.OVERLAY, 1f)
        shader.setFloatUniform(WaterRippleShader.RESOLUTION, width.toFloat(), height.toFloat())
        canvas.drawPaint(paint)
    }

    private companion object {
        // Glass Layer's defaults: 1400 ms, strength and speed 100 %.
        const val DURATION_MS = 1400f
        const val NANOS = 1_000_000_000f
        const val MILLIS = 1000f
        const val REFERENCE_MS = 800f
        const val REFRACTION_DP = 80f
        const val CAPTURE_SCALE = 0.4f
    }
}
