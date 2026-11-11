/*
 * Copyright (C) 2026 The PenguinOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.composefragment

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Vibrator
import android.service.quicksettings.Tile
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Arrangement.spacedBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material3.Icon as MaterialIcon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleStartEffect
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.qs.panels.ui.compose.TileListener
import com.android.systemui.qs.panels.ui.compose.infinitegrid.SmallTileContent
import com.android.systemui.qs.panels.ui.viewmodel.TileViewModel
import com.android.systemui.qs.ui.composable.LocalQsWide
import com.android.systemui.res.R
import com.android.systemui.shade.ui.composable.CosTileTips
import com.android.systemui.shade.ui.composable.cosSwitchRow
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.toArgb
import com.airbnb.lottie.LottieComposition
import com.airbnb.lottie.LottieProperty
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.rememberLottieDynamicProperties
import com.airbnb.lottie.compose.rememberLottieDynamicProperty
import androidx.compose.foundation.interaction.InteractionSource

internal val ColorOsGap = 18.dp
private val ColorOsPillHeight = 68.dp
private val ColorOsModuleHeight = 156.dp
private val ColorOsMaxModuleHeight = 220.dp
private const val ColorOsSliderAspect = 2.1f
internal val ColorOsTileSize = 70.dp
internal val ColorOsTileRowHeight = 105.dp
private val ColorOsMediaCorner = 28.dp
private const val ColorOsColumns = 4
private const val ColorOsRows = 3
private val ColorOsGridHeadroom = 4.dp

private val ColorOsBlue = Color(0xFF2F6BFF)
private val ColorOsRed = Color(0xFFE5483D)
private val ColorOsGreen = Color(0xFF21B04B)
private val ColorOsInk = Color(0xFF1C1C1E)
private val ColorOsActiveFill = Color(0xF2FFFFFF)
internal val ColorOsLabel = Color(0xE6FFFFFF)
private val ColorOsSubLabel = Color(0x99FFFFFF)
private val ColorOsPill = RoundedCornerShape(percent = 50)

private val ColorOsWifiSpecs = listOf("wifi", "internet")
private const val ColorOsBluetoothSpec = "bt"
private const val ColorOsSimSpec = "cell"

fun colorOsCardSpecs(tiles: List<TileViewModel>): Set<String> =
    colorOsCardSpecsOf(tiles.map { it.spec.spec }.toSet())

fun colorOsCardSpecsOf(present: Set<String>): Set<String> {
    return buildSet {
        ColorOsWifiSpecs.firstOrNull { it in present }?.let(::add)
        if (ColorOsBluetoothSpec in present) add(ColorOsBluetoothSpec)
        colorOsThirdCardSpec(present)?.let(::add)
    }
}

private fun colorOsThirdCardSpec(present: Set<String>): String? =
    if (ColorOsSimSpec in present) ColorOsSimSpec
    else ColorOsFallbackCardSpecs.firstOrNull { it in present }

private val ColorOsFallbackCardSpecs = listOf("dnd", "dark", "rotation", "hotspot", "cast", "airplane")

private fun colorOsActiveTint(spec: String): Color =
    when (spec) {
        "wifi", "internet", "bt", "cast", "hotspot", "nfc" -> ColorOsBlue
        "cell" -> ColorOsGreen
        "dnd", "dark", "night" -> ColorOsInk
        else -> ColorOsRed
    }

@Composable
private fun ColorOsListen(tiles: List<TileViewModel>) {
    var listening by remember { mutableStateOf(false) }
    LifecycleStartEffect(Unit) {
        listening = true
        onStopOrDispose { listening = false }
    }
    TileListener(tiles) { listening }
}

@Composable
fun ColorOsTopRows(
    tiles: List<TileViewModel>,
    media: (@Composable (height: Dp) -> Unit)?,
    sliders: @Composable (width: Dp, height: Dp) -> Unit,
    modifier: Modifier = Modifier,
) {
    val bySpec = remember(tiles) { tiles.associateBy { it.spec.spec } }
    val wifi = ColorOsWifiSpecs.firstNotNullOfOrNull { bySpec[it] }
    val bluetooth = bySpec[ColorOsBluetoothSpec]
    val sim = colorOsThirdCardSpec(bySpec.keys)?.let { bySpec[it] }
    ColorOsListen(listOfNotNull(wifi, bluetooth, sim))
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val half = (maxWidth - ColorOsGap) / 2
        val fit = (half - ColorOsGap) / 2
        val moduleHeight =
            (fit * ColorOsSliderAspect).coerceIn(ColorOsModuleHeight, ColorOsMaxModuleHeight)
        val sliderWidth = minOf(fit, moduleHeight / ColorOsSliderAspect)
        Column(verticalArrangement = spacedBy(ColorOsGap)) {
            Row(Modifier.cosModuleReveal(1).cosSwitchRow().fillMaxWidth().height(ColorOsPillHeight), spacedBy(ColorOsGap)) {
                Box(Modifier.weight(1f).fillMaxHeight()) { wifi?.let { ColorOsConnectionPill(it) } }
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    bluetooth?.let { ColorOsConnectionPill(it) }
                }
            }
            Row(Modifier.cosModuleReveal(2).cosSwitchRow().fillMaxWidth().height(moduleHeight), spacedBy(ColorOsGap)) {
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    media?.invoke(moduleHeight) ?: ColorOsIdleMedia()
                }
                Box(Modifier.weight(1f).fillMaxHeight()) { sliders(sliderWidth, moduleHeight) }
            }
            Row(Modifier.cosModuleReveal(3).cosSwitchRow().fillMaxWidth().height(ColorOsPillHeight), spacedBy(ColorOsGap)) {
                ColorOsRingerPill(Modifier.weight(1f).fillMaxHeight())
                Box(Modifier.weight(1f).fillMaxHeight()) { sim?.let { ColorOsConnectionPill(it) } }
            }
        }
    }
}

@Composable
private fun ColorOsConnectionPill(tile: TileViewModel) {
    val (uiState, icon) = rememberTileState(tile)
    val details = LocalQsTileDetails.current
    val active = uiState.visualState == Tile.STATE_ACTIVE
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier =
            Modifier.fillMaxSize()
                .cosPress(interaction)
                .cosGlass(ColorOsPill)
                .cosSpotLight(ColorOsPill, CosSpotLightMedium)
                .combinedClickable(
                    interactionSource = interaction,
                    indication = null,
                    // Like a large stock tile: the label opens the list, the circle toggles.
                    onClick = { tile.detailsOrPrimary(uiState, details) },
                    onLongClick = { tile.detailsOrSettings(uiState, details) },
                )
                .padding(start = 10.dp, end = 14.dp),
        horizontalArrangement = spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.clip(CircleShape).clickable(
                interactionSource = interaction,
                indication = null,
            ) {
                tile.primaryAction(uiState)
            }
        ) {
            ColorOsCircle(icon, active, 46.dp, colorOsActiveTint(tile.spec.spec), tile.spec.spec)
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = uiState.label,
                color = ColorOsLabel,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (uiState.secondaryLabel.isNotBlank()) {
                Text(
                    text = uiState.secondaryLabel,
                    color = ColorOsSubLabel,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
internal fun ColorOsCircle(
    icon: Icon,
    active: Boolean,
    size: Dp,
    activeTint: Color,
    spec: String? = null,
    interaction: InteractionSource? = null,
) {
    val fill by animateFloatAsState(if (active) 1f else 0f, tween(200, easing = ColorOsEase))
    val iconColor by
        animateColorAsState(if (active) activeTint else Color.White, tween(200, easing = ColorOsEase))
    val pop = remember { Animatable(1f) }
    var popped by remember { mutableStateOf(active) }
    LaunchedEffect(active) {
        if (popped == active) return@LaunchedEffect
        popped = active
        pop.snapTo(0.8f)
        pop.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 500f))
    }
    Box(
        modifier =
            Modifier.size(size)
                .then(if (interaction != null) Modifier.cosPress(interaction) else Modifier)
                .cosGlass(CircleShape, active)
                .drawBehind { if (fill > 0f) drawCircle(ColorOsActiveFill, alpha = fill) }
                .then(if (interaction != null) Modifier.cosSpotLight(CircleShape) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (!cosTileLottie(spec, active, Color.White, activeTint, size * 0.42f, iconColor)) {
            Box(Modifier.graphicsLayer { scaleX = pop.value; scaleY = pop.value }) {
                SmallTileContent(iconProvider = { icon }, color = iconColor, size = { size * 0.42f })
            }
        }
    }
}

private val ColorOsEase = CubicBezierEasing(0.33f, 0f, 0.67f, 1f)

private class ColorOsRingerStage(val mode: Int, val icon: ImageVector, val animation: String)

private val ColorOsRingerStages =
    listOf(
        ColorOsRingerStage(
            AudioManager.RINGER_MODE_NORMAL,
            Icons.Filled.Notifications,
            "qs_three_stage_normal_open_lottie.json",
        ),
        ColorOsRingerStage(
            AudioManager.RINGER_MODE_VIBRATE,
            Icons.Filled.Vibration,
            "qs_three_stage_vibrate_lottie.json",
        ),
        ColorOsRingerStage(
            AudioManager.RINGER_MODE_SILENT,
            Icons.Filled.NotificationsOff,
            "qs_three_stage_silent_open_lottie.json",
        ),
    )

private val ColorOsRingerIndicator = 46.dp
private val ColorOsRingerIcon = 22.dp

private fun colorOsRingerNudge(index: Int, selected: Int): Float {
    val last = ColorOsRingerStages.lastIndex
    return when (selected) {
        0 -> 1f
        last -> -1f
        else ->
            when (index) {
                0 -> 1f
                last -> -1f
                else -> 0f
            }
    }
}

@Composable
private fun ColorOsRingerPill(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val audio = remember(context) { context.getSystemService(AudioManager::class.java) }
    var mode by remember { mutableIntStateOf(audio.ringerModeInternal) }
    DisposableEffect(context) {
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    mode = audio.ringerModeInternal
                }
            }
        context.registerReceiver(
            receiver,
            IntentFilter(AudioManager.INTERNAL_RINGER_MODE_CHANGED_ACTION),
            Context.RECEIVER_NOT_EXPORTED,
        )
        onDispose { context.unregisterReceiver(receiver) }
    }
    val set = { value: Int ->
        audio.ringerModeInternal = value
        mode = value
    }
    val canVibrate = remember(context) {
        context.getSystemService(Vibrator::class.java)?.hasVibrator() == true
    }
    val stages = remember(canVibrate) {
        ColorOsRingerStages.filter { canVibrate || it.mode != AudioManager.RINGER_MODE_VIBRATE }
    }
    val selected = stages.indexOfFirst { it.mode == mode }.coerceAtLeast(0)
    BoxWithConstraints(
        modifier = modifier.cosGlass(ColorOsPill).padding(horizontal = 10.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        val density = LocalDensity.current
        val segmentPx = with(density) { maxWidth.toPx() } / stages.size
        val indicatorPx = with(density) { ColorOsRingerIndicator.toPx() }
        val nudge = { index: Int -> colorOsRingerNudge(index, selected) * 0.1f * segmentPx }
        val indicatorX by
            animateFloatAsState(
                selected * segmentPx + (segmentPx - indicatorPx) / 2 + nudge(selected),
                cosSpring(0.25f, 0.3f),
                label = "ringerIndicator",
            )
        Box(
            Modifier.graphicsLayer { translationX = indicatorX }
                .size(ColorOsRingerIndicator)
                .background(ColorOsActiveFill, CircleShape)
        )
        Row(Modifier.fillMaxSize()) {
            stages.forEachIndexed { index, stage ->
                val shift by
                    animateFloatAsState(nudge(index), cosSpring(0f, 0.35f), label = "ringerNudge")
                ColorOsRingerButton(
                    stage = stage,
                    selected = index == selected,
                    modifier =
                        Modifier.weight(1f).fillMaxHeight().graphicsLayer { translationX = shift },
                ) {
                    set(stage.mode)
                }
            }
        }
    }
}

@Composable
private fun ColorOsRingerButton(
    stage: ColorOsRingerStage,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val interaction = remember { MutableInteractionSource() }
    val scale by
        animateFloatAsState(if (selected) 1.2f else 1f, cosSpring(0f, 0.25f), label = "ringerScale")
    val color by
        animateColorAsState(
            if (selected) ColorOsRed else ColorOsSubLabel,
            cosSpring(0f, 0.3f),
            label = "ringerColor",
        )
    var animation by remember { mutableStateOf<LottieComposition?>(null) }
    val progress = remember { Animatable(1f) }
    var wasSelected by remember { mutableStateOf(selected) }
    LaunchedEffect(Unit) { animation = CosTileLottie.asset(context, "ringermode/${stage.animation}") }
    LaunchedEffect(selected, animation) {
        val clip = animation ?: return@LaunchedEffect
        if (selected == wasSelected) return@LaunchedEffect
        wasSelected = selected
        if (!selected) {
            progress.snapTo(1f)
            return@LaunchedEffect
        }
        progress.snapTo(0f)
        progress.animateTo(1f, tween(clip.duration.toInt(), easing = LinearEasing))
    }
    Box(
        modifier =
            modifier
                .cosPress(interaction, press = cosSpring(0.18f, 0.13f), release = cosSpring(0.62f, 0.35f))
                .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val iconModifier =
            Modifier.size(ColorOsRingerIcon).graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
        val clip = animation
        if (clip != null) {
            val properties =
                rememberLottieDynamicProperties(
                    rememberLottieDynamicProperty(LottieProperty.COLOR, color.toArgb(), "**"),
                    rememberLottieDynamicProperty(LottieProperty.STROKE_COLOR, color.toArgb(), "**"),
                )
            LottieAnimation(
                composition = clip,
                progress = { progress.value },
                dynamicProperties = properties,
                modifier = iconModifier,
            )
        } else {
            MaterialIcon(
                imageVector = stage.icon,
                contentDescription = null,
                tint = color,
                modifier = iconModifier,
            )
        }
    }
}

@Composable
private fun ColorOsIdleMedia() {
    Column(
        modifier =
            Modifier.fillMaxSize()
                .cosGlass(RoundedCornerShape(ColorOsMediaCorner))
                .padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Box(
            modifier =
                Modifier.size(56.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            MaterialIcon(
                imageVector = Icons.Filled.MusicNote,
                contentDescription = null,
                tint = ColorOsSubLabel,
            )
        }
        Text(
            text = stringResource(R.string.harmony_not_playing),
            color = ColorOsLabel,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
fun ColorOsMediaFrame(content: @Composable () -> Unit) {
    // The COS panel is dark glass in both themes, like its white tile labels, so the media
    // card's title, artist and controls are light on it rather than the light theme's dark ink.
    val scheme = androidx.compose.material3.MaterialTheme.colorScheme
    androidx.compose.material3.MaterialTheme(
        colorScheme =
            scheme.copy(onSurface = ColorOsLabel, onSurfaceVariant = ColorOsSubLabel),
        typography = androidx.compose.material3.MaterialTheme.typography,
        shapes = androidx.compose.material3.MaterialTheme.shapes,
    ) {
        Box(Modifier.fillMaxSize().clip(RoundedCornerShape(ColorOsMediaCorner))) { content() }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ColorOsTileGrid(tiles: List<TileViewModel>, modifier: Modifier = Modifier) {
    ColorOsListen(tiles)
    val rows = if (LocalQsWide.current) 2 else ColorOsRows
    val pages = remember(tiles, rows) { tiles.chunked(ColorOsColumns * rows) }
    if (pages.isEmpty()) return
    val pager = rememberPagerState { pages.size }
    Column(modifier.cosModuleReveal(4).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        // A single page has nothing to scroll; leave sideways swipes to the panel switch.
        HorizontalPager(
            state = pager,
            userScrollEnabled = pages.size > 1,
            modifier =
                Modifier.cosSwitchRow()
                    .fillMaxWidth()
                    .height(ColorOsTileRowHeight * rows + ColorOsGridHeadroom),
            verticalAlignment = Alignment.Top,
        ) { page ->
            Column(Modifier.fillMaxWidth().padding(top = ColorOsGridHeadroom)) {
                pages[page].chunked(ColorOsColumns).forEach { row ->
                    Row(Modifier.fillMaxWidth().height(ColorOsTileRowHeight)) {
                        row.forEach { ColorOsTile(it, Modifier.weight(1f)) }
                        repeat(ColorOsColumns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
        if (pages.size > 1) {
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = spacedBy(8.dp)) {
                repeat(pages.size) { index ->
                    Box(
                        Modifier.size(6.dp)
                            .clip(CircleShape)
                            .background(
                                if (index == pager.currentPage) Color.White
                                else Color.White.copy(alpha = 0.35f)
                            )
                    )
                }
            }
        }
    }
}

@Composable
private fun ColorOsTile(tile: TileViewModel, modifier: Modifier = Modifier) {
    val (uiState, icon) = rememberTileState(tile)
    val details = LocalQsTileDetails.current
    val context = LocalContext.current
    val active = uiState.visualState == Tile.STATE_ACTIVE
    val unavailable = uiState.visualState == Tile.STATE_UNAVAILABLE
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier =
            modifier
                .fillMaxHeight()
                .alpha(if (unavailable) 0.45f else 1f)
                .combinedClickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = {
                        if (!unavailable) {
                            CosTileTips.show(
                                context.getString(
                                    if (active) R.string.cos_tile_tip_off
                                    else R.string.cos_tile_tip_on,
                                    uiState.label,
                                )
                            )
                        }
                        tile.primaryAction(uiState, details)
                    },
                    onLongClick = { tile.detailsOrSettings(uiState, details) },
                ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ColorOsCircle(
            icon,
            active,
            ColorOsTileSize,
            colorOsActiveTint(tile.spec.spec),
            tile.spec.spec,
            interaction,
        )
        Text(
            text = uiState.label,
            color = ColorOsLabel,
            fontSize = 12.sp,
            lineHeight = 14.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 7.dp, start = 2.dp, end = 2.dp),
        )
    }
}
