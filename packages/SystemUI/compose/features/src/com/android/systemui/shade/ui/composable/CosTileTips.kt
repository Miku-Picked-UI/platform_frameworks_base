/*
 * Copyright (C) 2026 The PenguinOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.shade.ui.composable

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

object CosTileTips {
    var tip by mutableStateOf<Pair<String, Long>?>(null)
        private set

    /** When the last tip played; the header is composed again on every pull down. */
    internal var shownAt = 0L

    fun show(text: String) {
        tip = text to SystemClock.uptimeMillis()
    }
}

@Composable
fun CosTileTipHeader(modifier: Modifier = Modifier, header: @Composable () -> Unit) {
    val tip = CosTileTips.tip
    val headerAlpha = remember { Animatable(1f) }
    val tipAlpha = remember { Animatable(0f) }
    var words by remember { mutableStateOf("") }
    LaunchedEffect(tip) {
        // Each pull down restarts this effect with the last tip; play every tip only once.
        if (tip == null || tip.second <= CosTileTips.shownAt) return@LaunchedEffect
        CosTileTips.shownAt = tip.second
        words = tip.first
        coroutineScope {
            launch { headerAlpha.animateTo(0f, tween(FADE_OUT_MS, easing = CouiEase)) }
            launch {
                delay(FADE_IN_DELAY_MS)
                tipAlpha.animateTo(1f, tween(FADE_IN_MS, easing = CouiEase))
            }
        }
        delay(HOLD_MS)
        coroutineScope {
            launch { tipAlpha.animateTo(0f, tween(FADE_OUT_MS, easing = CouiEase)) }
            launch {
                delay(FADE_IN_DELAY_MS)
                headerAlpha.animateTo(1f, tween(FADE_IN_MS, easing = CouiEase))
            }
        }
    }
    Box(modifier) {
        Box(Modifier.graphicsLayer { alpha = headerAlpha.value }) { header() }
        if (tipAlpha.value > 0f) {
            Text(
                words,
                color = Color.White,
                fontSize = 14.sp,
                maxLines = 1,
                textAlign = TextAlign.Center,
                modifier =
                    Modifier.align(Alignment.Center).graphicsLayer { alpha = tipAlpha.value },
            )
        }
    }
}

private val CouiEase = CubicBezierEasing(0.33f, 0f, 0.67f, 1f)
private const val FADE_OUT_MS = 200
private const val FADE_IN_MS = 300
private const val FADE_IN_DELAY_MS = 100L
private const val HOLD_MS = 1500L
