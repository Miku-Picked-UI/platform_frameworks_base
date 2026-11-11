/*
 * Copyright (C) 2026 The PenguinOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.composefragment

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import com.airbnb.lottie.LottieComposition
import com.airbnb.lottie.LottieCompositionFactory
import com.airbnb.lottie.LottieProperty
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.rememberLottieDynamicProperties
import com.airbnb.lottie.compose.rememberLottieDynamicProperty
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal object CosTileLottie {
    private const val DIR = "cos/qs"

    private val compositions = ConcurrentHashMap<String, Result<LottieComposition>>()

    private fun prefix(spec: String): String? =
        when (spec) {
            "wifi", "internet" -> "qs_wifi_lottie"
            "bt" -> "qs_bluetooth_lottie"
            "cell" -> "qs_cellular_lottie"
            "airplane" -> "qs_airplane_lottie"
            "dnd" -> "qs_dnd_lottie"
            "location" -> "qs_location_lottie"
            "rotation" -> "qs_rotation_lottie"
            "flashlight" -> "qs_flashlight_lottie"
            "hotspot" -> "qs_hotspot_lottie"
            "nfc" -> "qs_nfc_lottie"
            "night" -> "qs_eyeprotect_lottie"
            else -> null
        }

    suspend fun asset(context: Context, path: String): LottieComposition? =
        compositions
            .getOrPut(path) {
                withContext(Dispatchers.IO) {
                    runCatching {
                        val json = com.android.systemui.qs.shared.style.PenguinAssets
                            .text(context, "cos/$path")!!
                        LottieCompositionFactory.fromJsonStringSync(json, path).value!!
                    }
                }
            }
            .getOrNull()

    fun keepsOwnIcon(spec: String) = spec == "wifi" || spec == "internet" || spec == "cell"

    suspend fun composition(context: Context, spec: String, on: Boolean): LottieComposition? {
        val prefix = prefix(spec) ?: return null
        val name = "${prefix}_${if (on) "active" else "inactive"}_sep_dual_light.json"
        return compositions
            .getOrPut(name) {
                withContext(Dispatchers.IO) {
                    runCatching {
                        val json = com.android.systemui.qs.shared.style.PenguinAssets
                            .text(context, "$DIR/$name")!!
                        LottieCompositionFactory.fromJsonStringSync(json, name).value!!
                    }
                }
            }
            .getOrNull()
    }
}

@Composable
internal fun cosTileLottie(
    spec: String?,
    active: Boolean,
    offColor: Color,
    onColor: Color,
    size: Dp,
    current: Color? = null,
): Boolean {
    if (spec == null) return false
    val context = LocalContext.current
    var shown by remember(spec) { mutableStateOf<LottieComposition?>(null) }
    var from by remember(spec) { mutableStateOf(active) }
    var playing by remember(spec) { mutableStateOf(false) }
    val progress = remember(spec) { Animatable(1f) }
    LaunchedEffect(spec, active) {
        val forward = CosTileLottie.composition(context, spec, true) ?: run {
            shown = null
            return@LaunchedEffect
        }
        val changed = from != active
        from = active
        val turn = if (active) forward else CosTileLottie.composition(context, spec, false)
        if (changed && turn != null) {
            shown = turn
            playing = true
            progress.snapTo(0f)
            progress.animateTo(1f, tween(turn.duration.toInt(), easing = LinearEasing))
            playing = false
        }
        shown = forward
        progress.snapTo(if (active) 1f else 0f)
    }
    val composition = shown ?: return false
    if (!playing && CosTileLottie.keepsOwnIcon(spec)) return false
    val start =
        if (playing) current ?: (if (active) offColor else onColor) else offColor
    val end = if (playing) (if (active) onColor else offColor) else onColor
    val properties =
        rememberLottieDynamicProperties(
            rememberLottieDynamicProperty(LottieProperty.COLOR, start.toArgb(), "**", "startFill", "**"),
            rememberLottieDynamicProperty(LottieProperty.COLOR, end.toArgb(), "**", "endFill", "**"),
            rememberLottieDynamicProperty(
                LottieProperty.STROKE_COLOR,
                start.toArgb(),
                "**",
                "startStroke",
                "**",
            ),
            rememberLottieDynamicProperty(
                LottieProperty.STROKE_COLOR,
                end.toArgb(),
                "**",
                "endStroke",
                "**",
            ),
        )
    LottieAnimation(
        composition = composition,
        progress = { progress.value },
        dynamicProperties = properties,
        modifier = Modifier.size(size),
    )
    return true
}
