/*
 * Copyright (C) 2026 The PenguinOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.shade.ui.composable

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlinx.coroutines.flow.first

class CosSwitchMotion {
    var offset by mutableFloatStateOf(0f)
    var active by mutableStateOf(false)
    var dragging by mutableStateOf(false)
    var fingerY by mutableFloatStateOf(0f)

    internal class Row {
        var centerY = 0f
        var rowsFromFinger = 0
        var velocity = 0f
        var lag by mutableFloatStateOf(0f)
    }

    internal val rows = LinkedHashSet<Row>()

    internal fun add(row: Row) {
        row.lag = offset
        rows += row
    }

    suspend fun drive() {
        while (true) {
            snapshotFlow { active }.first { it }
            measureRows()
            for (row in rows) {
                row.lag = offset
                row.velocity = 0f
            }
            var last = withFrameNanos { it }
            while (active) {
                withFrameNanos { now ->
                    var dt = ((now - last) / 1e9f).coerceIn(0f, 0.05f)
                    last = now
                    val target = offset
                    val drag = dragging
                    while (dt > 0f) {
                        val step = min(dt, 1f / 240f)
                        for (row in rows) step(row, target, drag, step)
                        dt -= step
                    }
                }
            }
            for (row in rows) row.lag = offset
        }
    }

    private fun measureRows() {
        val bands = rows.map { (it.centerY / ROW_BUCKET_PX).toInt() }.distinct().sorted()
        val finger =
            bands.minByOrNull { abs(it * ROW_BUCKET_PX + ROW_BUCKET_PX / 2 - fingerY) } ?: 0
        val fingerIndex = bands.indexOf(finger)
        for (row in rows) {
            row.rowsFromFinger =
                abs(bands.indexOf((row.centerY / ROW_BUCKET_PX).toInt()) - fingerIndex)
        }
    }

    private fun step(row: Row, target: Float, drag: Boolean, dt: Float) {
        val response =
            if (drag) min(DRAG_RESPONSE + DRAG_STEP * row.rowsFromFinger, MAX_RESPONSE)
            else min(RELEASE_RESPONSE + RELEASE_STEP * row.rowsFromFinger, MAX_RESPONSE)
        val bounce = if (drag) DRAG_BOUNCE else RELEASE_BOUNCE
        val omega = (2 * PI / response).toFloat()
        val damping = 2f * (1f - bounce) * omega
        val accel = -omega * omega * (row.lag - target) - damping * row.velocity
        row.velocity += accel * dt
        row.lag += row.velocity * dt
    }

    private companion object {
        const val ROW_BUCKET_PX = 48f
        const val DRAG_RESPONSE = 0.2f
        const val DRAG_STEP = 0.02f
        const val DRAG_BOUNCE = 0.25f
        const val RELEASE_RESPONSE = 0.28f
        const val RELEASE_STEP = 0.01f
        const val RELEASE_BOUNCE = 0.1f
        const val MAX_RESPONSE = 0.4f
    }
}

val LocalCosSwitchMotion = staticCompositionLocalOf<CosSwitchMotion?> { null }

@Composable
fun Modifier.cosSwitchRow(): Modifier {
    val motion = LocalCosSwitchMotion.current ?: return this
    val row = remember { CosSwitchMotion.Row() }
    DisposableEffect(motion) {
        motion.add(row)
        onDispose { motion.rows.remove(row) }
    }
    return onGloballyPositioned { row.centerY = it.boundsInWindow().center.y }
        .graphicsLayer { translationX = row.lag - motion.offset }
}
