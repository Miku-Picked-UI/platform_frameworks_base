package com.android.systemui.navigationbar.gestural

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
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

// Water back arrow (tuned in BackWaterSim, .picked-tools/backwater): the panel moves like a liquid
// drop, in the drop's shape modes, driven by how it moves. It bounces when it pops out and jiggles
// when the finger moves up and down. It is drawn as a Liquid Glass slab: a rounded rect (the only
// shape SurfaceFlinger's glass refracts) whose four sides follow the water's surface, so it
// squashes and stretches instead of being a free-form bubble. The arrow inside is warped by the
// same surface, so it stretches, shrinks and leans with the drop. Settings.Secure
// picked_back_water (default on); the tunables are the BackWaterSim sliders, see [WaterParams].
private const val WATER_SETTING = "picked_back_water"
private const val WATER_STEP_S = 0.001
// Each arm of the arrow is drawn as this many segments, so the water can bend it
private const val WATER_ARROW_SEGMENTS = 8

/**
 * The water's tunables. Each is a Settings.Secure int (the value times [scale]) set from
 * MikuSettings > Picked features > Water back arrow, with the values tuned in BackWaterSim as
 * defaults. Read once per swipe.
 */
private enum class WaterTunable(val key: String, val default: Int, val scale: Float) {
    WOBBLE_HZ("picked_back_water_wobble", 255, 100f),
    VISCOSITY("picked_back_water_viscosity", 174, 1000f),
    POP_BOUNCE("picked_back_water_pop_bounce", 38, 100f),
    POP_SPLASH("picked_back_water_pop_splash", 123, 100f),
    JIGGLE("picked_back_water_jiggle", 114, 100f),
    FOLLOW_STIFFNESS("picked_back_water_follow_stiffness", 701, 1f),
    FOLLOW_DAMPING("picked_back_water_follow_damping", 488, 1000f),
    DRAG_STRETCH("picked_back_water_drag_stretch", 52, 100f),
    ARROW_FOLLOW("picked_back_water_arrow", 100, 100f),
}

private class WaterParams {
    var wobbleHz = 2.55f
    var viscosity = 0.174f
    var popBounce = 0.38f
    var popSplash = 1.23f
    var jiggle = 1.14f
    var followStiffness = 701f
    var followDamping = 0.488f
    var dragStretch = 0.52f
    var arrowFollow = 1f

    fun load(context: Context) {
        val cr = context.contentResolver
        fun read(t: WaterTunable) =
            Settings.Secure.getIntForUser(cr, t.key, t.default, UserHandle.USER_CURRENT) / t.scale
        // Clamped to the slider ranges, so a bad value from adb can't blow the springs up
        wobbleHz = read(WaterTunable.WOBBLE_HZ).coerceIn(0.5f, 15f)
        viscosity = read(WaterTunable.VISCOSITY).coerceIn(0.01f, 1f)
        popBounce = read(WaterTunable.POP_BOUNCE).coerceIn(0.05f, 1f)
        popSplash = read(WaterTunable.POP_SPLASH).coerceIn(0f, 5f)
        jiggle = read(WaterTunable.JIGGLE).coerceIn(0f, 5f)
        followStiffness = read(WaterTunable.FOLLOW_STIFFNESS).coerceIn(50f, 30000f)
        followDamping = read(WaterTunable.FOLLOW_DAMPING).coerceIn(0.1f, 1f)
        dragStretch = read(WaterTunable.DRAG_STRETCH).coerceIn(0f, 3f)
        arrowFollow = read(WaterTunable.ARROW_FOLLOW).coerceIn(0f, 3f)
    }
}

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

    fun step(dt: Double, p: WaterParams) {
        val w2 = 2 * Math.PI * p.wobbleHz
        for (m in M0..M1) {
            val w = w2 * sqrt(m * (m - 1) * (m + 2) / 8.0)
            val z = (p.viscosity * (m - 1) * (2 * m + 1) / 5.0).coerceAtMost(1.0)
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
                    startingVelocity = 2.5f * params.popSplash,
                    springForce =
                        SpringForce().setStiffness(325f).setDampingRatio(params.popBounce),
                )
                water.kick(2, -4.0 * params.popSplash, 0.0)
                water.kick(3, 1.2 * params.popSplash, Math.PI)
            } else {
                // Let go of "back": it sags a little
                scale.stretchTo(stretchAmount = 0f, startingVelocity = -1f * params.popSplash)
                water.kick(2, 2.0 * params.popSplash, 0.0)
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
            water.kick(2, 3.0 * params.popSplash, 0.0)
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
    private val params = WaterParams()

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

    private val waterRect = RectF()

    /** Called when a back swipe starts. */
    fun onGestureEntry() {
        waterEnabled =
            Settings.Secure.getIntForUser(
                context.contentResolver,
                WATER_SETTING,
                1,
                UserHandle.USER_CURRENT,
            ) != 0
        if (waterEnabled) params.load(context)
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
        val sk = params.followStiffness.toDouble()
        val sz = 2 * params.followDamping * sqrt(sk)
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
                val g = 0.11 * params.jiggle * am / r
                water.force(2, -g, phi)
                water.force(3, -0.5 * g, phi)
            }
            val vm = hypot(centerVx, sloshV).coerceAtMost(4000.0)
            if (vm > 1) {
                // Moving: a slight teardrop along the motion
                water.force(2, 2.0 * params.dragStretch * vm / r, atan2(sloshV, centerVx))
            }
            water.step(h, params)
            t += h
        }
    }

    private fun waterStillMoving() =
        water.isMoving() || abs(sloshV) > 1 || abs(sloshY - sloshTarget) > 0.5 ||
            abs(centerVx) > 1

    /**
     * AOSP's rounded rect with each side moved by the water's surface on that side (right, down,
     * left, up), so the glass slab squashes, stretches and leans with the drop's shape modes.
     */
    private fun layoutWaterRect(w: Float, h: Float, out: RectF) {
        val cx = w / 2
        val hw = w / 2
        val hh = h / 2
        out.set(
            cx - hw * (1 + water.rel(Math.PI)).toFloat(),
            -hh * (1 + water.rel(1.5 * Math.PI)).toFloat(),
            cx + hw * (1 + water.rel(0.0)).toFloat(),
            hh * (1 + water.rel(0.5 * Math.PI)).toFloat(),
        )
    }

    private val waterArrowPath = Path()

    /**
     * The arrow in the background's frame (x away from the edge, the drop's centre at (w/2, 0)),
     * warped by the water like the slab: every point moves out from the centre by the surface's
     * displacement in its direction, r → r·(1 + k·rel(θ)). So the arrow squashes, stretches,
     * leans and bends with the drop's shape modes. [ARROW_FOLLOW][WaterTunable.ARROW_FOLLOW] = k.
     */
    private fun layoutWaterArrow(w: Float, dx: Float, dy: Float, pointsAway: Boolean): Path {
        val path = waterArrowPath
        path.reset()
        val offset = (w - dx) / 2
        val tipX = if (pointsAway) offset + dx else offset
        val backX = if (pointsAway) offset else offset + dx
        val cx = w / 2
        val k = params.arrowFollow.toDouble()
        for (arm in 0..1) {
            val y0 = if (arm == 0) -dy else 0f
            val x0 = if (arm == 0) backX else tipX
            val x1 = if (arm == 0) tipX else backX
            val y1 = if (arm == 0) 0f else dy
            // The second arm starts at the tip, where the first one ended
            for (i in (if (arm == 0) 0 else 1)..WATER_ARROW_SEGMENTS) {
                val t = i.toFloat() / WATER_ARROW_SEGMENTS
                val px = x0 + (x1 - x0) * t - cx
                val py = y0 + (y1 - y0) * t
                val f = (1 + k * water.rel(atan2(py.toDouble(), px.toDouble()))).toFloat()
                if (i == 0) path.moveTo(cx + px * f, py * f) else path.lineTo(cx + px * f, py * f)
            }
        }
        return path
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
                        .setStiffness(params.followStiffness)
                        .setDampingRatio(params.followDamping)
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
        if (watery) {
            // Water: always a Liquid Glass slab (frosted blur where the system glass is off),
            // squashing and stretching with the water instead of a filled bubble.
            layoutWaterRect(backgroundWidth, halfHeight * 2, waterRect)
            val maxCorner = minOf(waterRect.width(), waterRect.height()) / 2
            val edge = edgeCorner.coerceAtMost(maxCorner)
            val far = farCorner.coerceAtMost(maxCorner)
            drawGlassBehind(canvas, waterRect, edge, far)
            canvas.drawPath(
                waterRect.toPathWithRoundCorners(
                    topLeft = edge,
                    bottomLeft = edge,
                    topRight = far,
                    bottomRight = far,
                ),
                glassTintPaint.apply {
                    color = GLASS_TINT
                    alpha = (Color.alpha(GLASS_TINT) * backgroundAlpha.pos).toInt()
                },
            )
            drawGlassRim(canvas, waterRect, edge, far)
        } else {
            val arrowBackground =
                arrowBackgroundRect.toPathWithRoundCorners(
                    topLeft = edgeCorner,
                    bottomLeft = edgeCorner,
                    topRight = farCorner,
                    bottomRight = farCorner,
                )
            if (LiquidGlass.isEnabled(context)) {
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
            }
        }

        val dx = arrowLength.pos
        val dy = arrowHeight.pos

        if (watery) {
            // The arrow rides in the water and deforms with it
            val arrowPaint =
                arrowPaint.apply {
                    alpha = (255 * min(arrowAlpha.pos, backgroundAlpha.pos)).toInt()
                }
            canvas.drawPath(
                layoutWaterArrow(backgroundWidth, dx, dy, !arrowsPointLeft.xor(isLeftPanel)),
                arrowPaint,
            )
            canvas.restore()
            finishDraw(canvas)
            return
        }

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
        finishDraw(canvas)
    }

    private fun finishDraw(canvas: Canvas) {
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
