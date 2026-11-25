package com.android.systemui.navigationbar.gestural

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.UserHandle
import android.provider.Settings
import android.util.MathUtils.min
import android.view.View
import androidx.dynamicanimation.animation.FloatPropertyCompat
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.android.internal.graphics.drawable.BackgroundBlurDrawable
import com.android.internal.util.LatencyTracker
import com.android.systemui.navigationbar.gestural.BackPanelController.DelayedOnAnimationEndListener
import com.android.systemui.qs.shared.style.LiquidGlass
import com.android.systemui.qs.shared.style.LiquidGlassTune
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

private const val TAG = "BackPanel"
private const val DEBUG = false

// Liquid Glass back arrow: a light blur, like the Now Bar's clear glass, and the PIN keys' tint.
private const val GLASS_BLUR_RADIUS = 16
private const val GLASS_TINT = 0x1FFFFFFF

// Water back arrow (tuned in BackWaterSim, .picked-tools/backwater): the bubble's outline wobbles
// in the shape modes of a liquid drop, driven by how it moves. It bounces when it pops out and
// jiggles when the finger moves up and down. Settings.Secure picked_back_water (default on).
private const val WATER_SETTING = "picked_back_water"
private const val WATER_WOBBLE_HZ = 2.55f
private const val WATER_VISCOSITY = 0.174f
private const val WATER_POP_BOUNCE = 0.380f
private const val WATER_POP_SPLASH = 1.23f
private const val WATER_JIGGLE = 1.14f
private const val WATER_FOLLOW_STIFFNESS = 701f
private const val WATER_FOLLOW_DAMPING = 0.488f
private const val WATER_DRAG_STRETCH = 0.52f
private const val WATER_STEP_S = 0.001
private const val WATER_OUTLINE_POINTS = 72

/**
 * Shape modes of a liquid drop: r(θ) = r0(θ) · (1 + Σ a_m cos mθ + b_m sin mθ), m = 2..5.
 * Like a real drop (Rayleigh), higher modes are faster (ω ∝ √(m(m-1)(m+2))) and calm down
 * sooner (damping ∝ (m-1)(2m+1)). Amplitudes are fractions of the radius.
 */
private class WaterSurface {
    val a = DoubleArray(M1 + 1)
    val b = DoubleArray(M1 + 1)
    private val va = DoubleArray(M1 + 1)
    private val vb = DoubleArray(M1 + 1)
    private val fa = DoubleArray(M1 + 1)
    private val fb = DoubleArray(M1 + 1)

    fun reset() {
        for (m in 0..M1) {
            a[m] = 0.0; b[m] = 0.0; va[m] = 0.0; vb[m] = 0.0; fa[m] = 0.0; fb[m] = 0.0
        }
    }

    fun clearForces() {
        fa.fill(0.0)
        fb.fill(0.0)
    }

    /** Adds a forcing shaped like amp · cos(m(θ - φ)). */
    fun force(m: Int, amp: Double, phi: Double) {
        fa[m] += amp * cos(m * phi)
        fb[m] += amp * sin(m * phi)
    }

    fun kick(m: Int, vel: Double, phi: Double) {
        va[m] += vel * cos(m * phi)
        vb[m] += vel * sin(m * phi)
    }

    fun step(dt: Double) {
        val w2 = 2 * Math.PI * WATER_WOBBLE_HZ
        for (m in M0..M1) {
            val w = w2 * sqrt(m * (m - 1) * (m + 2) / 8.0)
            val z = (WATER_VISCOSITY * (m - 1) * (2 * m + 1) / 5.0).coerceAtMost(1.0)
            va[m] += (-w * w * a[m] - 2 * z * w * va[m] + fa[m]) * dt
            vb[m] += (-w * w * b[m] - 2 * z * w * vb[m] + fb[m]) * dt
            a[m] = (a[m] + va[m] * dt).coerceIn(-0.4, 0.4)
            b[m] = (b[m] + vb[m] * dt).coerceIn(-0.4, 0.4)
        }
    }

    fun rel(th: Double): Double {
        var s = 0.0
        for (m in M0..M1) s += a[m] * cos(m * th) + b[m] * sin(m * th)
        return s
    }

    /** True while the surface still visibly moves. */
    fun isMoving(): Boolean {
        for (m in M0..M1) {
            if (abs(a[m]) > 5e-4 || abs(b[m]) > 5e-4 || abs(va[m]) > 5e-3 || abs(vb[m]) > 5e-3) {
                return true
            }
        }
        return false
    }

    companion object {
        const val M0 = 2
        const val M1 = 5
    }
}

class BackPanel(context: Context, private val latencyTracker: LatencyTracker) : View(context) {

    var arrowsPointLeft = false
        set(value) {
            if (field != value) {
                invalidate()
                field = value
            }
        }

    // Arrow color and shape
    private val arrowPath = Path()
    private val arrowPaint = Paint()

    // Arrow background color and shape
    private var arrowBackgroundRect = RectF()
    private var arrowBackgroundPaint = Paint()

    // True if the panel is currently on the left of the screen
    var isLeftPanel = false

    /** Used to track back arrow latency from [android.view.MotionEvent.ACTION_DOWN] to [onDraw] */
    private var trackingBackArrowLatency = false

    /** The length of the arrow measured horizontally. Used for animating [arrowPath] */
    private var arrowLength =
        AnimatedFloat(
            name = "arrowLength",
            minimumVisibleChange = SpringAnimation.MIN_VISIBLE_CHANGE_PIXELS,
        )

    /**
     * The height of the arrow measured vertically from its center to its top (i.e. half the total
     * height). Used for animating [arrowPath]
     */
    var arrowHeight =
        AnimatedFloat(
            name = "arrowHeight",
            minimumVisibleChange = SpringAnimation.MIN_VISIBLE_CHANGE_ROTATION_DEGREES,
        )

    val backgroundWidth =
        AnimatedFloat(
            name = "backgroundWidth",
            minimumVisibleChange = SpringAnimation.MIN_VISIBLE_CHANGE_PIXELS,
            minimumValue = 0f,
        )

    val backgroundHeight =
        AnimatedFloat(
            name = "backgroundHeight",
            minimumVisibleChange = SpringAnimation.MIN_VISIBLE_CHANGE_PIXELS,
            minimumValue = 0f,
        )

    /**
     * Corners of the background closer to the edge of the screen (where the arrow appeared from).
     * Used for animating [arrowBackgroundRect]
     */
    val backgroundEdgeCornerRadius = AnimatedFloat("backgroundEdgeCornerRadius")

    /**
     * Corners of the background further from the edge of the screens (toward the direction the
     * arrow is being dragged). Used for animating [arrowBackgroundRect]
     */
    val backgroundFarCornerRadius = AnimatedFloat("backgroundFarCornerRadius")

    var scale =
        AnimatedFloat(
            name = "scale",
            minimumVisibleChange = SpringAnimation.MIN_VISIBLE_CHANGE_SCALE,
            minimumValue = 0f,
        )

    val scalePivotX =
        AnimatedFloat(
            name = "scalePivotX",
            minimumVisibleChange = SpringAnimation.MIN_VISIBLE_CHANGE_PIXELS,
            minimumValue = backgroundWidth.pos / 2,
        )

    /**
     * Left/right position of the background relative to the canvas. Also corresponds with the
     * background's margin relative to the screen edge. The arrow will be centered within the
     * background.
     */
    var horizontalTranslation = AnimatedFloat(name = "horizontalTranslation")

    var arrowAlpha =
        AnimatedFloat(
            name = "arrowAlpha",
            minimumVisibleChange = SpringAnimation.MIN_VISIBLE_CHANGE_ALPHA,
            minimumValue = 0f,
            maximumValue = 1f,
        )

    val backgroundAlpha =
        AnimatedFloat(
            name = "backgroundAlpha",
            minimumVisibleChange = SpringAnimation.MIN_VISIBLE_CHANGE_ALPHA,
            minimumValue = 0f,
            maximumValue = 1f,
        )

    private val allAnimatedFloat =
        setOf(
            arrowLength,
            arrowHeight,
            backgroundWidth,
            backgroundEdgeCornerRadius,
            backgroundFarCornerRadius,
            scalePivotX,
            scale,
            horizontalTranslation,
            arrowAlpha,
            backgroundAlpha,
        )

    /**
     * Canvas vertical translation. How far up/down the arrow and background appear relative to the
     * canvas.
     */
    var verticalTranslation = AnimatedFloat("verticalTranslation")

    /** Use for drawing debug info. Can only be set if [DEBUG]=true */
    var drawDebugInfo: ((canvas: Canvas) -> Unit)? = null
        set(value) {
            if (DEBUG) field = value
        }

    internal fun updateArrowPaint(arrowThickness: Float) {
        arrowPaint.strokeWidth = arrowThickness

        val isDeviceInNightTheme =
            resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES

        arrowPaint.color =
            context.getColor(
                if (isDeviceInNightTheme) {
                    com.android.internal.R.color.materialColorOnSecondaryContainer
                } else {
                    com.android.internal.R.color.materialColorOnSecondaryFixed
                }
            )

        arrowBackgroundPaint.color =
            context.getColor(
                if (isDeviceInNightTheme) {
                    com.android.internal.R.color.materialColorSecondaryContainer
                } else {
                    com.android.internal.R.color.materialColorSecondaryFixedDim
                }
            )
    }

    inner class AnimatedFloat(
        name: String,
        private val minimumVisibleChange: Float? = null,
        private val minimumValue: Float? = null,
        private val maximumValue: Float? = null,
    ) {

        // The resting position when not stretched by a touch drag
        private var restingPosition = 0f

        // The current position as updated by the SpringAnimation
        var pos = 0f
            private set(v) {
                if (field != v) {
                    field = v
                    invalidate()
                }
            }

        private val animation: SpringAnimation
        var spring: SpringForce
            get() = animation.spring
            set(value) {
                animation.cancel()
                animation.spring = value
            }

        val isRunning: Boolean
            get() = animation.isRunning

        fun addEndListener(listener: DelayedOnAnimationEndListener) {
            animation.addEndListener(listener)
        }

        init {
            val floatProp =
                object : FloatPropertyCompat<AnimatedFloat>(name) {
                    override fun setValue(animatedFloat: AnimatedFloat, value: Float) {
                        animatedFloat.pos = value
                    }

                    override fun getValue(animatedFloat: AnimatedFloat): Float = animatedFloat.pos
                }
            animation =
                SpringAnimation(this, floatProp).apply {
                    spring = SpringForce()
                    this@AnimatedFloat.minimumValue?.let { setMinValue(it) }
                    this@AnimatedFloat.maximumValue?.let { setMaxValue(it) }
                    this@AnimatedFloat.minimumVisibleChange?.let { minimumVisibleChange = it }
                }
        }

        fun snapTo(newPosition: Float) {
            animation.cancel()
            restingPosition = newPosition
            animation.spring.finalPosition = newPosition
            pos = newPosition
        }

        fun snapToRestingPosition() {
            snapTo(restingPosition)
        }

        fun stretchTo(
            stretchAmount: Float,
            startingVelocity: Float? = null,
            springForce: SpringForce? = null,
        ) {
            animation.apply {
                startingVelocity?.let {
                    cancel()
                    setStartVelocity(it)
                }
                springForce?.let { spring = springForce }
                animateToFinalPosition(restingPosition + stretchAmount)
            }
        }

        /**
         * Animates to a new position ([finalPosition]) that is the given fraction ([amount])
         * between the existing [restingPosition] and the new [finalPosition].
         *
         * The [restingPosition] will remain unchanged. Only the animation is updated.
         */
        fun stretchBy(finalPosition: Float?, amount: Float) {
            val stretchedAmount = amount * ((finalPosition ?: 0f) - restingPosition)
            animation.animateToFinalPosition(restingPosition + stretchedAmount)
        }

        fun updateRestingPosition(pos: Float?, animated: Boolean = true) {
            if (pos == null) return

            restingPosition = pos
            if (animated) {
                animation.animateToFinalPosition(restingPosition)
            } else {
                snapTo(restingPosition)
            }
        }

        fun cancel() = animation.cancel()
    }

    init {
        visibility = GONE
        arrowPaint.apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.SQUARE
        }
        arrowBackgroundPaint.apply {
            style = Paint.Style.FILL
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
        }
    }

    private fun calculateArrowPath(dx: Float, dy: Float): Path {
        arrowPath.reset()
        arrowPath.moveTo(dx, -dy)
        arrowPath.lineTo(0f, 0f)
        arrowPath.lineTo(dx, dy)
        arrowPath.moveTo(dx, -dy)
        return arrowPath
    }

    fun addAnimationEndListener(
        animatedFloat: AnimatedFloat,
        endListener: DelayedOnAnimationEndListener,
    ): Boolean {
        return if (animatedFloat.isRunning) {
            animatedFloat.addEndListener(endListener)
            true
        } else {
            endListener.run()
            false
        }
    }

    fun cancelAnimations() {
        allAnimatedFloat.forEach { it.cancel() }
    }

    fun setStretch(
        horizontalTranslationStretchAmount: Float,
        arrowStretchAmount: Float,
        arrowAlphaStretchAmount: Float,
        backgroundAlphaStretchAmount: Float,
        backgroundWidthStretchAmount: Float,
        backgroundHeightStretchAmount: Float,
        edgeCornerStretchAmount: Float,
        farCornerStretchAmount: Float,
        fullyStretchedDimens: EdgePanelParams.BackIndicatorDimens,
    ) {
        horizontalTranslation.stretchBy(
            finalPosition = fullyStretchedDimens.horizontalTranslation,
            amount = horizontalTranslationStretchAmount,
        )
        arrowLength.stretchBy(
            finalPosition = fullyStretchedDimens.arrowDimens.length,
            amount = arrowStretchAmount,
        )
        arrowHeight.stretchBy(
            finalPosition = fullyStretchedDimens.arrowDimens.height,
            amount = arrowStretchAmount,
        )
        arrowAlpha.stretchBy(
            finalPosition = fullyStretchedDimens.arrowDimens.alpha,
            amount = arrowAlphaStretchAmount,
        )
        backgroundAlpha.stretchBy(
            finalPosition = fullyStretchedDimens.backgroundDimens.alpha,
            amount = backgroundAlphaStretchAmount,
        )
        backgroundWidth.stretchBy(
            finalPosition = fullyStretchedDimens.backgroundDimens.width,
            amount = backgroundWidthStretchAmount,
        )
        backgroundHeight.stretchBy(
            finalPosition = fullyStretchedDimens.backgroundDimens.height,
            amount = backgroundHeightStretchAmount,
        )
        backgroundEdgeCornerRadius.stretchBy(
            finalPosition = fullyStretchedDimens.backgroundDimens.edgeCornerRadius,
            amount = edgeCornerStretchAmount,
        )
        backgroundFarCornerRadius.stretchBy(
            finalPosition = fullyStretchedDimens.backgroundDimens.farCornerRadius,
            amount = farCornerStretchAmount,
        )
    }

    fun popOffEdge(startingVelocity: Float) {
        if (waterEnabled) {
            if (startingVelocity > 0) {
                // Pops out: the bubble overshoots its size and bounces, and the push off the
                // edge squashes it sideways first, so it rebounds tall, then wide, like a drop.
                scale.stretchTo(
                    stretchAmount = 0f,
                    startingVelocity = 2.5f * WATER_POP_SPLASH,
                    springForce =
                        SpringForce().setStiffness(325f).setDampingRatio(WATER_POP_BOUNCE),
                )
                water.kick(2, -4.0 * WATER_POP_SPLASH, 0.0)
                water.kick(3, 1.2 * WATER_POP_SPLASH, Math.PI)
            } else {
                // Let go of "back": it sags a little
                scale.stretchTo(stretchAmount = 0f, startingVelocity = -1f * WATER_POP_SPLASH)
                water.kick(2, 2.0 * WATER_POP_SPLASH, 0.0)
            }
            horizontalTranslation.stretchTo(stretchAmount = 0f, startingVelocity * 200f)
            invalidate()
            return
        }
        scale.stretchTo(stretchAmount = 0f, startingVelocity = startingVelocity * -.8f)
        horizontalTranslation.stretchTo(stretchAmount = 0f, startingVelocity * 200f)
    }

    fun popScale(startingVelocity: Float) {
        scalePivotX.snapTo(backgroundWidth.pos / 2)
        scale.stretchTo(stretchAmount = 0f, startingVelocity = startingVelocity)
        if (waterEnabled) {
            water.kick(2, 3.0 * WATER_POP_SPLASH, 0.0)
            invalidate()
        }
    }

    // ---------------------------------------------------------------------------------------
    // Water
    // ---------------------------------------------------------------------------------------

    /** Read once per gesture, so the setting applies from the next swipe. */
    var waterEnabled = false
        private set

    private val water = WaterSurface()

    // The water's own idea of where the finger is (dp), on a soft spring: it lags, overshoots
    // and drives the jiggle when the finger moves up and down. The visible movement is still
    // AOSP's rubber band.
    private var sloshY = 0.0
    private var sloshV = 0.0
    private var sloshTarget = 0.0

    // The bubble centre's horizontal motion (dp), from frame to frame
    private var lastCenterX = Double.NaN
    private var lastCenterVx = 0.0
    private var centerAx = 0.0
    private var centerVx = 0.0
    private var lastWaterNs = 0L

    private val waterPath = Path()
    private val waterXs = FloatArray(WATER_OUTLINE_POINTS)
    private val waterYs = FloatArray(WATER_OUTLINE_POINTS)
    private val waterShinePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val waterShadePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** Called when a back swipe starts. */
    fun onGestureEntry() {
        waterEnabled =
            Settings.Secure.getIntForUser(
                context.contentResolver,
                WATER_SETTING,
                1,
                UserHandle.USER_CURRENT,
            ) != 0
        water.reset()
        sloshY = 0.0
        sloshV = 0.0
        sloshTarget = 0.0
        lastCenterX = Double.NaN
        lastCenterVx = 0.0
        centerAx = 0.0
        centerVx = 0.0
        lastWaterNs = 0L
    }

    /** The finger's vertical distance from where the swipe started, in px. */
    fun setWaterFingerOffset(yOffsetPx: Float) {
        if (!waterEnabled) return
        sloshTarget = yOffsetPx / resources.displayMetrics.density.toDouble()
        invalidate()
    }

    private fun stepWater() {
        val now = System.nanoTime()
        val density = resources.displayMetrics.density.toDouble()
        val centerX = (horizontalTranslation.pos + backgroundWidth.pos / 2) / density
        if (lastWaterNs == 0L || lastCenterX.isNaN()) {
            lastWaterNs = now
            lastCenterX = centerX
            return
        }
        val dt = ((now - lastWaterNs) / 1e9).coerceAtMost(1.0 / 30)
        lastWaterNs = now
        if (dt <= 0) return

        // Horizontal motion comes from AOSP's springs; differentiate and smooth it
        val vx = (centerX - lastCenterX) / dt
        lastCenterX = centerX
        val ax = (vx - lastCenterVx) / dt
        lastCenterVx = vx
        val k = (dt * 40).coerceAtMost(1.0)
        centerVx += (vx - centerVx) * k
        centerAx += (ax - centerAx) * k

        val r = max(12.0, (backgroundWidth.pos + backgroundHeight.pos) / 4 / density)
        val sk = WATER_FOLLOW_STIFFNESS.toDouble()
        val sz = 2 * WATER_FOLLOW_DAMPING * sqrt(sk)
        var t = 0.0
        while (t < dt) {
            val h = minOf(WATER_STEP_S, dt - t)
            val ay = -sk * (sloshY - sloshTarget) - sz * sloshV
            sloshV += ay * h
            sloshY += sloshV * h

            water.clearForces()
            val am = hypot(centerAx, ay).coerceAtMost(1e5)
            if (am > 1) {
                // Pseudo-gravity against the acceleration: squash along it, bulge behind
                val phi = atan2(ay, centerAx)
                val g = 0.11 * WATER_JIGGLE * am / r
                water.force(2, -g, phi)
                water.force(3, -0.5 * g, phi)
            }
            val vm = hypot(centerVx, sloshV).coerceAtMost(4000.0)
            if (vm > 1) {
                // Moving: a slight teardrop along the motion
                water.force(2, 2.0 * WATER_DRAG_STRETCH * vm / r, atan2(sloshV, centerVx))
            }
            water.step(h)
            t += h
        }
    }

    private fun waterStillMoving() =
        water.isMoving() || abs(sloshV) > 1 || abs(sloshY - sloshTarget) > 0.5 ||
            abs(centerVx) > 1

    /** AOSP's rounded rect, deformed by the water, through a smooth curve. */
    private fun waterOutline(w: Float, h: Float, edgeCorner: Float, farCorner: Float): Path {
        val n = WATER_OUTLINE_POINTS
        val re = minOf(edgeCorner, w / 2, h / 2)
        val rf = minOf(farCorner, w / 2, h / 2)
        val cx = w / 2
        val maxR = hypot(w, h)
        for (i in 0 until n) {
            val th = 2 * Math.PI * i / n
            val c = cos(th).toFloat()
            val s = sin(th).toFloat()
            var lo = 0f
            var hi = maxR
            repeat(16) {
                val mid = (lo + hi) / 2
                if (insideRoundRect(cx + c * mid, s * mid, w, h, re, rf)) lo = mid else hi = mid
            }
            val rr = lo * (1 + water.rel(th)).toFloat()
            waterXs[i] = cx + c * rr
            waterYs[i] = s * rr
        }
        waterPath.reset()
        waterPath.moveTo(waterXs[0], waterYs[0])
        for (i in 0 until n) {
            val i0 = (i - 1 + n) % n
            val i2 = (i + 1) % n
            val i3 = (i + 2) % n
            waterPath.cubicTo(
                waterXs[i] + (waterXs[i2] - waterXs[i0]) / 6,
                waterYs[i] + (waterYs[i2] - waterYs[i0]) / 6,
                waterXs[i2] - (waterXs[i3] - waterXs[i]) / 6,
                waterYs[i2] - (waterYs[i3] - waterYs[i]) / 6,
                waterXs[i2],
                waterYs[i2],
            )
        }
        waterPath.close()
        return waterPath
    }

    private fun insideRoundRect(x: Float, y: Float, w: Float, h: Float, re: Float, rf: Float):
        Boolean {
        if (x < 0 || x > w || abs(y) > h / 2) return false
        val edgeSide = x < w / 2
        val r = if (edgeSide) re else rf
        val ccx = if (edgeSide) r else w - r
        val ccy = h / 2 - r
        val dx = if (edgeSide) max(0f, ccx - x) else max(0f, x - ccx)
        val dy = max(0f, abs(y) - ccy)
        return dx * dx + dy * dy <= r * r
    }

    /** A glint that rides on the surface and a darker belly, so the bubble reads as liquid. */
    private fun drawWaterShine(canvas: Canvas, path: Path, w: Float, h: Float) {
        val alpha = backgroundAlpha.pos.coerceIn(0f, 1f)
        if (alpha <= 0f || w < 1f || h < 1f) return
        val gx = w * 0.36f + w * 0.5f * (water.a[2] * 0.5 + water.b[3] * 0.4).toFloat()
        val gy = -h * 0.24f + h * 0.5f * (water.b[2] * 0.6 - water.a[3] * 0.3).toFloat()
        waterShinePaint.shader =
            RadialGradient(
                gx,
                gy,
                max(w, h) * 0.42f,
                intArrayOf(0x73FFFFFF, 0x22FFFFFF, 0x00FFFFFF),
                floatArrayOf(0f, 0.35f, 1f),
                Shader.TileMode.CLAMP,
            )
        waterShinePaint.alpha = (255 * alpha).toInt()
        canvas.drawPath(path, waterShinePaint)
        waterShadePaint.shader =
            LinearGradient(0f, h * 0.1f, 0f, h * 0.6f, 0x00000000, 0x26000000, Shader.TileMode.CLAMP)
        waterShadePaint.alpha = (255 * alpha).toInt()
        canvas.drawPath(path, waterShadePaint)
    }

    private val waterBlurRect = RectF()
    private val waterRimPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.WHITE
        }

    /** How much of the resting size the water's outline keeps at its deepest dip (0.6..1). */
    private fun waterBlurScale(): Float {
        var minRel = 0.0
        for (i in 0 until WATER_OUTLINE_POINTS) {
            minRel = minOf(minRel, water.rel(2 * Math.PI * i / WATER_OUTLINE_POINTS))
        }
        return (1 + minRel).toFloat().coerceIn(0.6f, 1f)
    }

    /** Liquid Glass's lit rim, traced along the water's outline instead of the rounded rect. */
    private fun drawWaterRim(canvas: Canvas, path: Path) {
        // The ColorOS style is frosted glass without the iOS-like lit rim.
        if (LiquidGlass.cosStyle(context).value) return
        val alpha = backgroundAlpha.pos.coerceIn(0f, 1f)
        if (alpha <= 0f) return
        waterRimPaint.strokeWidth = LiquidGlass.rimWidthDp * resources.displayMetrics.density
        val rim = LiquidGlassTune.f("rim_mid", 0.32f) * LiquidGlassTune.f("view_rim", 0.9f)
        waterRimPaint.alpha = (255 * rim * alpha).toInt().coerceIn(0, 255)
        canvas.drawPath(path, waterRimPaint)
    }

    fun popArrowAlpha(startingVelocity: Float, springForce: SpringForce? = null) {
        arrowAlpha.stretchTo(
            stretchAmount = 0f,
            startingVelocity = startingVelocity,
            springForce = springForce,
        )
    }

    fun resetStretch() {
        backgroundAlpha.snapTo(1f)
        verticalTranslation.snapTo(0f)
        scale.snapTo(1f)

        horizontalTranslation.snapToRestingPosition()
        arrowLength.snapToRestingPosition()
        arrowHeight.snapToRestingPosition()
        arrowAlpha.snapToRestingPosition()
        backgroundWidth.snapToRestingPosition()
        backgroundHeight.snapToRestingPosition()
        backgroundEdgeCornerRadius.snapToRestingPosition()
        backgroundFarCornerRadius.snapToRestingPosition()
    }

    /** Updates resting arrow and background size not accounting for stretch */
    internal fun setRestingDimens(
        restingParams: EdgePanelParams.BackIndicatorDimens,
        animate: Boolean = true,
    ) {
        horizontalTranslation.updateRestingPosition(restingParams.horizontalTranslation)
        scale.updateRestingPosition(restingParams.scale)
        backgroundAlpha.updateRestingPosition(restingParams.backgroundDimens.alpha)

        arrowAlpha.updateRestingPosition(restingParams.arrowDimens.alpha, animate)
        arrowLength.updateRestingPosition(restingParams.arrowDimens.length, animate)
        arrowHeight.updateRestingPosition(restingParams.arrowDimens.height, animate)
        scalePivotX.updateRestingPosition(restingParams.scalePivotX, animate)
        backgroundWidth.updateRestingPosition(restingParams.backgroundDimens.width, animate)
        backgroundHeight.updateRestingPosition(restingParams.backgroundDimens.height, animate)
        backgroundEdgeCornerRadius.updateRestingPosition(
            restingParams.backgroundDimens.edgeCornerRadius,
            animate,
        )
        backgroundFarCornerRadius.updateRestingPosition(
            restingParams.backgroundDimens.farCornerRadius,
            animate,
        )
    }

    fun animateVertically(yPos: Float) = verticalTranslation.stretchTo(yPos)

    fun setSpring(
        horizontalTranslation: SpringForce? = null,
        verticalTranslation: SpringForce? = null,
        scale: SpringForce? = null,
        arrowLength: SpringForce? = null,
        arrowHeight: SpringForce? = null,
        arrowAlpha: SpringForce? = null,
        backgroundAlpha: SpringForce? = null,
        backgroundFarCornerRadius: SpringForce? = null,
        backgroundEdgeCornerRadius: SpringForce? = null,
        backgroundWidth: SpringForce? = null,
        backgroundHeight: SpringForce? = null,
    ) {
        arrowLength?.let { this.arrowLength.spring = it }
        arrowHeight?.let { this.arrowHeight.spring = it }
        arrowAlpha?.let { this.arrowAlpha.spring = it }
        backgroundAlpha?.let { this.backgroundAlpha.spring = it }
        backgroundFarCornerRadius?.let { this.backgroundFarCornerRadius.spring = it }
        backgroundEdgeCornerRadius?.let { this.backgroundEdgeCornerRadius.spring = it }
        scale?.let { this.scale.spring = it }
        backgroundWidth?.let { this.backgroundWidth.spring = it }
        backgroundHeight?.let { this.backgroundHeight.spring = it }
        horizontalTranslation?.let { this.horizontalTranslation.spring = it }
        verticalTranslation?.let {
            this.verticalTranslation.spring =
                if (waterEnabled) {
                    SpringForce()
                        .setStiffness(WATER_FOLLOW_STIFFNESS)
                        .setDampingRatio(WATER_FOLLOW_DAMPING)
                } else {
                    it
                }
        }
    }

    override fun hasOverlappingRendering() = false

    // [PenguinOS] Liquid Glass back arrow: while system-wide Liquid Glass is on, the arrow's
    // bubble is a glass slab (a blur region SurfaceFlinger draws as glass) with a light tint and
    // the lit rim, like the glass PIN keys, instead of a solid colour.
    private var glassBlur: BackgroundBlurDrawable? = null
    private val glassRadii = FloatArray(8)
    private val glassTintPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun drawGlassBehind(canvas: Canvas, rect: RectF, edgeCorner: Float, farCorner: Float) {
        val blur =
            glassBlur ?: viewRootImpl?.createBackgroundBlurDrawable()?.also { glassBlur = it }
                ?: return
        blur.setVisible(true, false)
        blur.setBlurRadius(GLASS_BLUR_RADIUS)
        // The canvas is scaled, and mirrored for the right edge; the blur region takes its
        // bounds from that, but its corners are given in screen orientation.
        val s = scale.pos
        val edge = edgeCorner * s
        val far = farCorner * s
        if (isLeftPanel) {
            blur.setCornerRadius(edge, far, edge, far)
        } else {
            blur.setCornerRadius(far, edge, far, edge)
        }
        blur.alpha = (255 * backgroundAlpha.pos).toInt().coerceIn(0, 255)
        blur.setBounds(
            rect.left.toInt(),
            rect.top.toInt(),
            rect.right.toInt(),
            rect.bottom.toInt(),
        )
        blur.draw(canvas)
    }

    private fun drawGlassRim(canvas: Canvas, rect: RectF, edgeCorner: Float, farCorner: Float) {
        // The ColorOS style is frosted glass without the iOS-like lit rim.
        if (LiquidGlass.cosStyle(context).value) return
        glassRadii[0] = edgeCorner; glassRadii[1] = edgeCorner // top left (edge side)
        glassRadii[2] = farCorner; glassRadii[3] = farCorner // top right
        glassRadii[4] = farCorner; glassRadii[5] = farCorner // bottom right
        glassRadii[6] = edgeCorner; glassRadii[7] = edgeCorner // bottom left
        val alpha = backgroundAlpha.pos
        if (alpha < 1f) {
            canvas.saveLayerAlpha(rect, (255 * alpha).toInt().coerceIn(0, 255))
        }
        LiquidGlass.drawRim(canvas, rect, glassRadii, resources.displayMetrics.density)
        if (alpha < 1f) canvas.restore()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        glassBlur?.setVisible(false, false)
        glassBlur = null
    }

    override fun onDraw(canvas: Canvas) {
        if (waterEnabled) stepWater()
        val edgeCorner = backgroundEdgeCornerRadius.pos
        val farCorner = backgroundFarCornerRadius.pos
        val halfHeight = backgroundHeight.pos / 2
        val canvasWidth = width
        val backgroundWidth = backgroundWidth.pos
        val scalePivotX = scalePivotX.pos

        canvas.save()

        if (!isLeftPanel) canvas.scale(-1f, 1f, canvasWidth / 2.0f, 0f)

        canvas.translate(horizontalTranslation.pos, height * 0.5f + verticalTranslation.pos)

        canvas.scale(scale.pos, scale.pos, scalePivotX, 0f)

        arrowBackgroundRect.apply {
            left = 0f
            top = -halfHeight
            right = backgroundWidth
            bottom = halfHeight
        }
        val watery = waterEnabled && backgroundWidth >= 1f && halfHeight >= 0.5f
        val arrowBackground =
            if (watery) {
                waterOutline(backgroundWidth, halfHeight * 2, edgeCorner, farCorner)
            } else {
                arrowBackgroundRect.toPathWithRoundCorners(
                    topLeft = edgeCorner,
                    bottomLeft = edgeCorner,
                    topRight = farCorner,
                    bottomRight = farCorner,
                )
            }
        val glass = LiquidGlass.isEnabled(context)
        if (glass && watery) {
            // The blur region can only be a rounded rect, so keep it inside the water: shrink it
            // by however far the surface currently dips in. Tint, rim and glint follow the water.
            val k = waterBlurScale()
            val cx = backgroundWidth / 2
            waterBlurRect.set(cx - cx * k, -halfHeight * k, cx + cx * k, halfHeight * k)
            drawGlassBehind(canvas, waterBlurRect, edgeCorner * k, farCorner * k)
            canvas.drawPath(
                arrowBackground,
                glassTintPaint.apply {
                    color = GLASS_TINT
                    alpha = (Color.alpha(GLASS_TINT) * backgroundAlpha.pos).toInt()
                },
            )
            drawWaterShine(canvas, arrowBackground, backgroundWidth, halfHeight * 2)
            drawWaterRim(canvas, arrowBackground)
        } else if (glass) {
            drawGlassBehind(canvas, arrowBackgroundRect, edgeCorner, farCorner)
            canvas.drawPath(
                arrowBackground,
                glassTintPaint.apply {
                    color = GLASS_TINT
                    alpha = (Color.alpha(GLASS_TINT) * backgroundAlpha.pos).toInt()
                },
            )
            drawGlassRim(canvas, arrowBackgroundRect, edgeCorner, farCorner)
        } else {
            glassBlur?.setVisible(false, false)
            canvas.drawPath(
                arrowBackground,
                arrowBackgroundPaint.apply { alpha = (255 * backgroundAlpha.pos).toInt() },
            )
            if (watery) drawWaterShine(canvas, arrowBackground, backgroundWidth, halfHeight * 2)
        }

        val dx = arrowLength.pos
        val dy = arrowHeight.pos

        // How far the arrow bounding box should be from the edge of the screen. Measured from
        // either the tip or the back of the arrow, whichever is closer
        val arrowOffset = (backgroundWidth - dx) / 2
        canvas.translate(
            /* dx= */ arrowOffset,
            /* dy= */ 0f, /* pass 0 for the y position since the canvas was already translated */
        )

        val arrowPointsAwayFromEdge = !arrowsPointLeft.xor(isLeftPanel)
        if (arrowPointsAwayFromEdge) {
            canvas.apply {
                scale(-1f, 1f, 0f, 0f)
                translate(-dx, 0f)
            }
        }

        val arrowPath = calculateArrowPath(dx = dx, dy = dy)
        val arrowPaint =
            arrowPaint.apply { alpha = (255 * min(arrowAlpha.pos, backgroundAlpha.pos)).toInt() }
        canvas.drawPath(arrowPath, arrowPaint)
        canvas.restore()

        if (trackingBackArrowLatency) {
            latencyTracker.onActionEnd(LatencyTracker.ACTION_SHOW_BACK_ARROW)
            trackingBackArrowLatency = false
        }

        if (DEBUG) drawDebugInfo?.invoke(canvas)

        // Keep the water moving after AOSP's springs have settled
        if (waterEnabled && visibility == VISIBLE && waterStillMoving()) postInvalidateOnAnimation()
    }

    fun startTrackingShowBackArrowLatency() {
        latencyTracker.onActionStart(LatencyTracker.ACTION_SHOW_BACK_ARROW)
        trackingBackArrowLatency = true
    }

    private fun RectF.toPathWithRoundCorners(
        topLeft: Float = 0f,
        topRight: Float = 0f,
        bottomRight: Float = 0f,
        bottomLeft: Float = 0f,
    ): Path =
        Path().apply {
            val corners =
                floatArrayOf(
                    topLeft,
                    topLeft,
                    topRight,
                    topRight,
                    bottomRight,
                    bottomRight,
                    bottomLeft,
                    bottomLeft,
                )
            addRoundRect(this@toPathWithRoundCorners, corners, Path.Direction.CW)
        }
}
