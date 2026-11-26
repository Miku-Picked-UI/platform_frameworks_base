/*
 * SPDX-FileCopyrightText: 2026 DrDisagree, Miku-Picked UI
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * From Glass Layer (github.com/Mahmud0808/GlassLayer, GPL-3.0), glass/WaterSurface.kt, unchanged
 * apart from the package: a damped 2D wave simulation whose height field (with its slope) is
 * uploaded to an RGBA_F16 bitmap for WaterRippleShader.
 */
package com.android.systemui.picked.water

import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Half
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

internal class WaterSurface {

    var bitmap: Bitmap? = null
        private set
    var cellPx = 1f
        private set

    private var visibleCols = 0
    private var visibleRows = 0
    private var cols = 0
    private var rows = 0
    private var height = FloatArray(0)
    private var velocity = FloatArray(0)
    private var scratch = FloatArray(0)
    private var band = FloatArray(0)
    private var rowsUp = FloatArray(0)
    private var fine = FloatArray(0)
    private var fineCols = 0
    private var fineRows = 0
    private var pixels: ShortBuffer? = null
    private var bytes: ByteBuffer? = null
    private var pendingTicks = 0f

    fun reset(widthPx: Int, heightPx: Int) {
        val long = max(widthPx, heightPx).toFloat()
        val short = minOf(widthPx, heightPx).toFloat()
        val longCells = LONG_CELLS
        val shortCells = odd((longCells - 1) * short / long + 1f)
        val portrait = heightPx >= widthPx
        val newCols = if (portrait) shortCells else longCells
        val newRows = if (portrait) longCells else shortCells
        cellPx = long / (longCells - 1)
        if (newCols != visibleCols || newRows != visibleRows) {
            visibleCols = newCols
            visibleRows = newRows
            cols = visibleCols + PADDING * 2
            rows = visibleRows + PADDING * 2
            height = FloatArray(cols * rows)
            velocity = FloatArray(cols * rows)
            scratch = FloatArray(cols * rows)
            band = FloatArray(cols * rows)
            fineCols = visibleCols * 2 - 1
            fineRows = visibleRows * 2 - 1
            rowsUp = FloatArray(rows * fineCols)
            fine = FloatArray(fineCols * fineRows)
            bytes = ByteBuffer.allocateDirect(fineCols * fineRows * CHANNELS * BYTES_PER_HALF).order(ByteOrder.nativeOrder())
            pixels = bytes?.asShortBuffer()
            bitmap?.recycle()
            bitmap = Bitmap.createBitmap(fineCols, fineRows, Bitmap.Config.RGBA_F16)
            buildBand()
        } else {
            height.fill(0f)
            velocity.fill(0f)
        }
        pendingTicks = 0f
    }

    fun impulse(xPx: Float, yPx: Float) {
        val cx = xPx / cellPx + PADDING
        val cy = yPx / cellPx + PADDING
        val reach = IMPULSE_RADIUS * TROUGH_END
        val minX = (cx - reach).toInt().coerceAtLeast(1)
        val maxX = (cx + reach).toInt().coerceAtMost(cols - 2)
        val minY = (cy - reach).toInt().coerceAtLeast(1)
        val maxY = (cy + reach).toInt().coerceAtMost(rows - 2)
        for (y in minY..maxY) {
            for (x in minX..maxX) {
                val dx = x - cx
                val dy = y - cy
                val d = sqrt(dx * dx + dy * dy) / IMPULSE_RADIUS
                velocity[y * cols + x] += IMPULSE_AMPLITUDE * profile(d)
            }
        }
    }

    fun advance(seconds: Float, rate: Float) {
        val dt = if (seconds > MAX_GAP_S) FALLBACK_STEP_S else seconds
        pendingTicks += dt * TICKS_PER_SECOND * rate
        var ticks = pendingTicks.toInt().coerceAtMost(MAX_TICKS_PER_FRAME)
        pendingTicks -= ticks
        if (pendingTicks > 1f) pendingTicks = 0f
        while (ticks-- > 0) tick()
    }

    fun upload() {
        val target = bitmap ?: return
        val buffer = pixels ?: return
        upsample()
        buffer.position(0)
        for (y in 0 until fineRows) {
            val up = if (y > 0) y - 1 else y
            val down = if (y < fineRows - 1) y + 1 else y
            for (x in 0 until fineCols) {
                val left = if (x > 0) x - 1 else x
                val right = if (x < fineCols - 1) x + 1 else x
                val gx = (fine[y * fineCols + right] - fine[y * fineCols + left]) / ((right - left).coerceAtLeast(1))
                val gy = (fine[down * fineCols + x] - fine[up * fineCols + x]) / ((down - up).coerceAtLeast(1))
                buffer.put(Half.toHalf(gx))
                buffer.put(Half.toHalf(gy))
                buffer.put(Half.toHalf(fine[y * fineCols + x]))
                buffer.put(ONE_HALF)
            }
        }
        buffer.position(0)
        val raw = bytes ?: return
        raw.position(0)
        target.copyPixelsFromBuffer(raw)
    }

    fun fieldMatrix(out: Matrix) {
        val half = cellPx / 2f
        out.setScale(half, half)
        out.preTranslate(-TEXEL_CENTER, -TEXEL_CENTER)
    }

    fun release() {
        bitmap?.recycle()
        bitmap = null
    }

    private fun tick() {
        val h = height
        val v = velocity
        for (y in 0 until rows) {
            h[y * cols] = h[y * cols + 1]
            h[y * cols + cols - 1] = h[y * cols + cols - 2]
        }
        for (x in 0 until cols) {
            h[x] = h[cols + x]
            h[(rows - 1) * cols + x] = h[(rows - 2) * cols + x]
        }
        for (y in 1 until rows - 1) {
            var i = y * cols + 1
            for (x in 1 until cols - 1) {
                val edges = h[i - 1] + h[i + 1] + h[i - cols] + h[i + cols]
                val corners = h[i - cols - 1] + h[i - cols + 1] + h[i + cols - 1] + h[i + cols + 1]
                val laplacian = (4f * edges + corners - 20f * h[i]) / 6f
                v[i] = (v[i] + laplacian * WAVE_GAIN) * DAMPING * band[i]
                i++
            }
        }
        for (i in h.indices) h[i] = ((h[i] + v[i]) * band[i]).coerceIn(-HEIGHT_LIMIT, HEIGHT_LIMIT)
        val s = scratch
        for (y in 1 until rows - 1) {
            var i = y * cols + 1
            for (x in 1 until cols - 1) {
                val average = (h[i - 1] + h[i + 1] + h[i - cols] + h[i + cols]) * 0.25f
                s[i] = h[i] + (average - h[i]) * DIFFUSION
                i++
            }
        }
        for (y in 1 until rows - 1) {
            System.arraycopy(s, y * cols + 1, h, y * cols + 1, cols - 2)
        }
    }

    private fun upsample() {
        val h = height
        for (y in 0 until rows) {
            for (fx in 0 until fineCols) {
                val sx = fx * 0.5f + PADDING
                val i = sx.toInt()
                val t = sx - i
                val base = y * cols
                rowsUp[y * fineCols + fx] = catmullRom(
                    h[base + (i - 1).coerceAtLeast(0)],
                    h[base + i],
                    h[base + (i + 1).coerceAtMost(cols - 1)],
                    h[base + (i + 2).coerceAtMost(cols - 1)],
                    t
                )
            }
        }
        for (fy in 0 until fineRows) {
            val sy = fy * 0.5f + PADDING
            val j = sy.toInt()
            val t = sy - j
            val r0 = (j - 1).coerceAtLeast(0) * fineCols
            val r1 = j * fineCols
            val r2 = (j + 1).coerceAtMost(rows - 1) * fineCols
            val r3 = (j + 2).coerceAtMost(rows - 1) * fineCols
            for (fx in 0 until fineCols) {
                fine[fy * fineCols + fx] = catmullRom(rowsUp[r0 + fx], rowsUp[r1 + fx], rowsUp[r2 + fx], rowsUp[r3 + fx], t) / HEIGHT_LIMIT
            }
        }
    }

    private fun buildBand() {
        for (y in 0 until rows) {
            for (x in 0 until cols) {
                val outX = maxOf(PADDING - x, x - (cols - 1 - PADDING), 0)
                val outY = maxOf(PADDING - y, y - (rows - 1 - PADDING), 0)
                val depth = maxOf(outX, outY) / PADDING.toFloat()
                band[y * cols + x] = 1f - BAND_DAMPING * depth * depth
            }
        }
    }

    private fun profile(d: Float): Float = when {
        d < 1f -> 0.5f + 0.5f * cos(PI.toFloat() * d)
        d < TROUGH_END -> {
            val s = sin(PI.toFloat() * (d - 1f) / (TROUGH_END - 1f))
            -TROUGH_DEPTH * s * s
        }
        else -> 0f
    }

    private fun catmullRom(p0: Float, p1: Float, p2: Float, p3: Float, t: Float): Float {
        val t2 = t * t
        val t3 = t2 * t
        return 0.5f * (2f * p1 + (p2 - p0) * t + (2f * p0 - 5f * p1 + 4f * p2 - p3) * t2 + (3f * p1 - p0 - 3f * p2 + p3) * t3)
    }

    private fun odd(value: Float): Int {
        val rounded = value.roundToInt().coerceAtLeast(3)
        return if (rounded % 2 == 0) rounded + 1 else rounded
    }
}

private val ONE_HALF: Short = Half.toHalf(1f)
private const val LONG_CELLS = 95
private const val PADDING = 8
private const val WAVE_GAIN = 0.5f
private const val DAMPING = 0.994f
private const val BAND_DAMPING = 0.35f
private const val DIFFUSION = 0.1f
private const val HEIGHT_LIMIT = 100f
private const val IMPULSE_RADIUS = 7f
private const val TROUGH_END = 1.8f
private const val TROUGH_DEPTH = 0.22f
private const val IMPULSE_AMPLITUDE = 40f
private const val TICKS_PER_SECOND = 240f
private const val MAX_GAP_S = 0.036f
private const val FALLBACK_STEP_S = 1f / 60f
private const val MAX_TICKS_PER_FRAME = 16
private const val CHANNELS = 4
private const val BYTES_PER_HALF = 2
private const val TEXEL_CENTER = 0.5f
