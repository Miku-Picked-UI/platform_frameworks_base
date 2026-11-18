/*
 * Copyright (C) 2026 The PenguinOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.composefragment

import android.app.WallpaperColors
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.core.graphics.drawable.toBitmap
import com.android.systemui.penguin.CosFeature
import com.android.systemui.penguin.rememberCosFeature
import com.google.ux.material.libmonet.hct.Hct
import com.google.ux.material.libmonet.scheme.SchemeContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun Modifier.cosMediaLight(artwork: Drawable?, corner: Dp, artworkCenter: Offset): Modifier {
    if (!rememberCosFeature(CosFeature.MediaLights)) return this
    val context = LocalContext.current
    val light = remember { CosHooks.createMediaLight(context) } ?: return this
    val night = isSystemInDarkTheme()
    val density = LocalDensity.current
    var frame by remember { mutableIntStateOf(0) }

    val callback = remember {
        object : Drawable.Callback {
            override fun invalidateDrawable(who: Drawable) {
                frame++
            }

            override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) {}

            override fun unscheduleDrawable(who: Drawable, what: Runnable) {}
        }
    }
    DisposableEffect(light) {
        light.callback = callback
        onDispose { light.callback = null }
    }

    val art = rememberCosMediaArt(artwork)
    val color = art.color

    var measured by remember { mutableIntStateOf(0) }
    LaunchedEffect(color, night, measured) {
        if (color == 0) {
            CosHooks.clearMediaLight(light)
            return@LaunchedEffect
        }
        CosHooks.applyMediaLight(light, color, night)
    }

    val ready = measured != 0 && color != 0
    LaunchedEffect(art.signature, ready) {
        if (!ready) return@LaunchedEffect
        CosHooks.applyMediaLightSpread(
            light,
            artworkCenter.x - light.bounds.width() / 2f,
            artworkCenter.y - light.bounds.height() / 2f,
        )
    }

    return onSizeChanged {
            light.setBounds(0, 0, it.width, it.height)
            with(density) { CosHooks.setMediaLightCorner(light, corner.toPx()) }
            measured = it.width * 31 + it.height
        }
        .drawWithContent {
            frame
            drawIntoCanvas { light.draw(it.nativeCanvas) }
            drawContent()
        }
}

@Composable
fun rememberCosMediaColor(artwork: Drawable?): Int = rememberCosMediaArt(artwork).color

data class CosMediaArt(val color: Int, val signature: Int)

@Composable
fun rememberCosMediaArt(artwork: Drawable?): CosMediaArt {
    val night = isSystemInDarkTheme()
    val art by
        produceState(CosMediaArt(0, 0), artwork, night) {
            val next =
                if (artwork == null) {
                    delay(ART_GONE_DELAY_MS)
                    CosMediaArt(0, 0)
                } else {
                    withContext(Dispatchers.Default) { cosMediaArt(artwork, night) }
                }
            if (next != value) value = next
        }
    return art
}

private const val ART_GONE_DELAY_MS = 600L

private fun cosMediaArt(artwork: Drawable, night: Boolean): CosMediaArt {
    val bitmap: Bitmap = artwork.toBitmap(96, 96, Bitmap.Config.ARGB_8888)
    val seed = WallpaperColors.fromBitmap(bitmap).primaryColor.toArgb()
    val color = SchemeContent(Hct.fromInt(seed), night, 0.0).primaryPalette.tone(80)
    return CosMediaArt(color, signatureOf(bitmap))
}

private fun signatureOf(bitmap: Bitmap): Int {
    var hash = 17
    val step = bitmap.width / 8
    for (y in 0 until 8) {
        for (x in 0 until 8) {
            val pixel = bitmap.getPixel(x * step + step / 2, y * step + step / 2)
            hash = hash * 31 + ((pixel ushr 4) and 0x0F0F0F)
        }
    }
    return hash
}
