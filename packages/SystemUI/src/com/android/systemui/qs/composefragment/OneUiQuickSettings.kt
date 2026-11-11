/*
 * Copyright (C) 2026 The PenguinOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.qs.composefragment

import com.android.systemui.qs.ui.composable.visualState
import android.content.BroadcastReceiver
import android.content.Context
import android.database.ContentObserver
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.os.Vibrator
import android.provider.Settings
import android.service.quicksettings.Tile
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement.spacedBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material3.Icon as MaterialIcon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.qs.panels.ui.compose.TileListener
import com.android.systemui.qs.panels.ui.compose.infinitegrid.SmallTileContent
import com.android.systemui.qs.panels.ui.viewmodel.TileViewModel
import com.android.systemui.qs.shared.style.LiquidGlassControl
import com.android.systemui.qs.shared.style.glassPress
import com.android.systemui.qs.shared.style.glassRim
import com.android.systemui.qs.shared.style.liquidGlassOn
import com.android.systemui.res.R
import kotlin.math.roundToInt

val OneUiGap = 12.dp
private val OneUiPillHeight = 80.dp
private val OneUiPillCorner = 40.dp
private val OneUiCardCorner = 36.dp
private val OneUiToggleSize = 58.dp
private val OneUiSliderHeight = 72.dp
private val OneUiDiscInPill = 52.dp
private const val OneUiToggleColumns = 4
private const val OneUiToggleRows = 2

private val WifiSpecs = listOf("wifi", "internet")
private const val BluetoothSpec = "bt"
private const val DarkModeSpec = "dark"

@Composable
@ReadOnlyComposable
private fun oneUiOff(): Color =
    if (liquidGlassOn) LiquidGlassControl else Color.Black.copy(alpha = 0.18f)

@Composable
private fun Modifier.oneUiCard(corner: Dp = OneUiCardCorner): Modifier =
    clip(RoundedCornerShape(corner)).background(glassSurface()).glassRim(RoundedCornerShape(corner))

@Composable
private fun ListenTo(tiles: List<TileViewModel>) {
    var listening by remember { mutableStateOf(false) }
    LifecycleStartEffect(Unit) {
        listening = true
        onStopOrDispose { listening = false }
    }
    TileListener(tiles) { listening }
}

@Composable
fun OneUiQuickSettingsPanel(
    tiles: List<TileViewModel>,
    largeTileSpecs: Set<String>,
    brightness: @Composable (Modifier) -> Unit,
    media: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val bySpec = remember(tiles) { tiles.associateBy { it.spec.spec } }
    val wifi = WifiSpecs.firstNotNullOfOrNull { bySpec[it] }
    val bluetooth = bySpec[BluetoothSpec]
    val darkMode = bySpec[DarkModeSpec]
    val placed = remember(wifi, bluetooth, darkMode) { setOfNotNull(wifi, bluetooth, darkMode) }
    val rest = remember(tiles, placed) { tiles.filterNot { it in placed } }
    val toggles = remember(rest, largeTileSpecs) { rest.filterNot { it.spec.spec in largeTileSpecs } }
    val pills = remember(rest, largeTileSpecs) { rest.filter { it.spec.spec in largeTileSpecs } }
    ListenTo(tiles)

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = spacedBy(OneUiGap)) {
        if (wifi != null || bluetooth != null) {
            Row(horizontalArrangement = spacedBy(OneUiGap)) {
                listOfNotNull(wifi, bluetooth).forEach {
                    OneUiPill(it, Modifier.weight(1f).height(OneUiPillHeight))
                }
                if (wifi == null || bluetooth == null) Box(Modifier.weight(1f))
            }
        }
        if (toggles.isNotEmpty()) OneUiTogglesCard(toggles)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = spacedBy(OneUiGap)) {
            Box(Modifier.weight(1f).height(OneUiSliderHeight)) {
                brightness(Modifier.fillMaxSize())
            }
            darkMode?.let { OneUiRoundControl(it, OneUiSliderHeight) }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = spacedBy(OneUiGap)) {
            OneUiMediaVolumeSlider(Modifier.weight(1f).height(OneUiSliderHeight))
            OneUiSoundModeButton(OneUiSliderHeight)
        }
        media?.invoke() ?: OneUiIdleMedia()
        pills.chunked(2).forEach { row ->
            Row(horizontalArrangement = spacedBy(OneUiGap)) {
                row.forEach { OneUiPill(it, Modifier.weight(1f).height(OneUiPillHeight)) }
                if (row.size == 1) Box(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun OneUiPill(tile: TileViewModel, modifier: Modifier = Modifier) {
    val (uiState, icon) = rememberTileState(tile)
    val details = LocalQsTileDetails.current
    val active = uiState.visualState == Tile.STATE_ACTIVE
    Row(
        modifier =
            modifier
                .glassPress()
                .oneUiCard(OneUiPillCorner)
                .combinedClickable(
                    onClick = { tile.primaryAction(uiState, details) },
                    onLongClick = { tile.detailsOrSettings(uiState, details) },
                )
                .padding(start = 12.dp, end = 16.dp),
        horizontalArrangement = spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OneUiDisc(icon, active, OneUiDiscInPill)
        Column(Modifier.weight(1f)) {
            Text(
                text = uiState.label,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (uiState.secondaryLabel.isNotBlank()) {
                Text(
                    text = uiState.secondaryLabel,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun OneUiDisc(icon: Icon, active: Boolean, size: Dp) {
    val background by
        animateColorAsState(
            if (active) Color.White else oneUiOff(),
            label = "OneUiDisc",
        )
    Box(
        modifier =
            Modifier.size(size)
                .clip(CircleShape)
                .background(background)
                .glassRim(CircleShape, if (active) 0.7f else 1f),
        contentAlignment = Alignment.Center,
    ) {
        SmallTileContent(
            iconProvider = { icon },
            color = if (active) Color(0xFF1C1B1F) else Color.White,
            size = { size * 0.46f },
        )
    }
}

@Composable
fun OneUiTogglesCard(
    tiles: List<TileViewModel>,
    modifier: Modifier = Modifier,
    rows: Int = OneUiToggleRows,
    toggleSize: Dp = OneUiToggleSize,
    interactive: Boolean = true,
    onEdit: (() -> Unit)? = null,
) {
    ListenTo(tiles)
    val perPage = OneUiToggleColumns * rows
    val pages = remember(tiles, perPage) { tiles.chunked(perPage).ifEmpty { listOf(emptyList()) } }
    val pagerState = rememberPagerState(pageCount = { pages.size })
    Box(modifier = modifier.fillMaxWidth()) {
    Column(
        modifier = Modifier.fillMaxWidth().oneUiCard().padding(top = 14.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = spacedBy(8.dp),
    ) {
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = interactive,
            modifier = Modifier.fillMaxWidth(),
        ) { page ->
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                verticalArrangement = spacedBy(12.dp),
            ) {
                pages[page].chunked(OneUiToggleColumns).let { chunks ->
                    chunks + List(rows - chunks.size) { emptyList() }
                }.forEach { row ->
                    Row(modifier = Modifier.fillMaxWidth().height(toggleSize)) {
                        row.forEach { tile ->
                            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                OneUiToggle(tile, toggleSize, interactive)
                            }
                        }
                        repeat(OneUiToggleColumns - row.size) { Box(Modifier.weight(1f)) }
                    }
                }
            }
        }
        Row(horizontalArrangement = spacedBy(4.dp)) {
            repeat(pages.size.coerceAtLeast(1)) { index ->
                val current = index == pagerState.currentPage
                Box(
                    Modifier.width(if (current) 20.dp else 6.dp)
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(
                            MaterialTheme.colorScheme.onSurface.copy(
                                alpha = if (current) 0.6f else 0.25f
                            )
                        )
                )
            }
        }
    }
    if (tiles.isEmpty()) {
        Text(
            text = stringResource(R.string.oneui_toggles_empty),
            color = Color.White.copy(alpha = 0.75f),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 24.dp),
        )
    }
    onEdit?.takeIf { tiles.isNotEmpty() }?.let { edit ->
        Text(
            text = stringResource(R.string.oneui_edit),
            color = Color.White,
            style = MaterialTheme.typography.titleSmall,
            modifier =
                Modifier.align(Alignment.Center)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.45f))
                    .clickable(onClick = edit)
                    .padding(horizontal = 20.dp, vertical = 10.dp),
        )
    }
    }
}

@Composable
private fun OneUiToggle(tile: TileViewModel, size: Dp, interactive: Boolean) {
    val (uiState, icon) = rememberTileState(tile)
    val details = LocalQsTileDetails.current
    Box(
        modifier =
            Modifier.size(size)
                .glassPress()
                .clip(CircleShape)
                .then(
                    if (interactive) {
                        Modifier.combinedClickable(
                            onClick = { tile.primaryAction(uiState, details) },
                            onLongClick = { tile.detailsOrSettings(uiState, details) },
                        )
                    } else {
                        Modifier
                    }
                ),
        contentAlignment = Alignment.Center,
    ) {
        OneUiDisc(icon, uiState.visualState == Tile.STATE_ACTIVE, size)
    }
}

@Composable
fun OneUiTileControl(
    tile: TileViewModel,
    wide: Boolean,
    modifier: Modifier = Modifier,
    interactive: Boolean = true,
) {
    ListenTo(listOf(tile))
    if (wide) {
        Box(modifier.fillMaxSize().then(if (interactive) Modifier else Modifier.gesturesOff())) {
            OneUiPill(tile, Modifier.fillMaxSize())
        }
    } else {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val (uiState, icon) = rememberTileState(tile)
            val details = LocalQsTileDetails.current
            Box(
                Modifier.fillMaxHeight()
                    .aspectRatio(1f)
                    .glassPress()
                    .clip(CircleShape)
                    .then(
                        if (interactive) {
                            Modifier.combinedClickable(
                                onClick = { tile.primaryAction(uiState, details) },
                                onLongClick = { tile.detailsOrSettings(uiState, details) },
                            )
                        } else {
                            Modifier
                        }
                    ),
                contentAlignment = Alignment.Center,
            ) {
                OneUiDisc(icon, uiState.visualState == Tile.STATE_ACTIVE, OneUiDiscCell)
            }
        }
    }
}

private fun Modifier.gesturesOff(): Modifier =
    pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                    .changes
                    .forEach { it.consume() }
            }
        }
    }

private val OneUiDiscCell = 72.dp

@Composable
private fun OneUiRoundControl(tile: TileViewModel, size: Dp) {
    val (uiState, icon) = rememberTileState(tile)
    val details = LocalQsTileDetails.current
    Box(
        modifier =
            Modifier.size(size)
                .glassPress()
                .clip(CircleShape)
                .combinedClickable(
                    onClick = { tile.primaryAction(uiState, details) },
                    onLongClick = { tile.detailsOrSettings(uiState, details) },
                ),
        contentAlignment = Alignment.Center,
    ) {
        OneUiDisc(icon, uiState.visualState == Tile.STATE_ACTIVE, size)
    }
}

private class AudioState(val volume: Int, val max: Int, val ringerMode: Int)

@Composable
private fun rememberAudioState(): Pair<AudioManager, AudioState> {
    val context = LocalContext.current
    val audioManager = remember(context) { context.getSystemService(AudioManager::class.java) }
    fun read() =
        AudioState(
            audioManager.getStreamVolume(AudioManager.STREAM_MUSIC),
            audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1),
            audioManager.ringerModeInternal,
        )
    var state by remember { mutableStateOf(read()) }
    DisposableEffect(context, audioManager) {
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    state = read()
                }
            }
        context.registerReceiver(
            receiver,
            IntentFilter().apply {
                addAction(AudioManager.VOLUME_CHANGED_ACTION)
                addAction(AudioManager.INTERNAL_RINGER_MODE_CHANGED_ACTION)
            },
            Context.RECEIVER_NOT_EXPORTED,
        )
        onDispose { context.unregisterReceiver(receiver) }
    }
    return audioManager to state
}

@Composable
private fun OneUiMediaVolumeSlider(modifier: Modifier = Modifier) {
    val (audioManager, audio) = rememberAudioState()
    var dragged by remember { mutableIntStateOf(-1) }
    val volume = if (dragged >= 0) dragged else audio.volume
    fun setFrom(x: Float, width: Int) {
        val target = ((x / width).coerceIn(0f, 1f) * audio.max).roundToInt()
        dragged = target
        if (target != audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)) {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        }
    }
    Box(
        modifier =
            modifier
                .clip(RoundedCornerShape(OneUiPillCorner))
                .background(oneUiOff())
                .glassRim(RoundedCornerShape(OneUiPillCorner))
                .pointerInput(audio.max) { detectTapGestures { setFrom(it.x, size.width) } }
                .pointerInput(audio.max) {
                    detectHorizontalDragGestures(
                        onDragEnd = { dragged = -1 },
                        onDragCancel = { dragged = -1 },
                    ) { change, _ ->
                        setFrom(change.position.x, size.width)
                    }
                },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier.fillMaxHeight()
                .fillMaxWidth(volume.toFloat() / audio.max)
                .clip(RoundedCornerShape(OneUiPillCorner))
                .background(Color.White)
        )
        MaterialIcon(
            imageVector =
                if (volume == 0) Icons.AutoMirrored.Rounded.VolumeOff
                else Icons.Rounded.MusicNote,
            contentDescription = null,
            tint = Color(0xFF1C1B1F),
            modifier = Modifier.padding(start = 24.dp).size(26.dp),
        )
    }
}

@Composable
fun OneUiSoundModeButton(size: Dp) {
    val (audioManager, audio) = rememberAudioState()
    val context = LocalContext.current
    val canVibrate =
        remember(context) { context.getSystemService(Vibrator::class.java)?.hasVibrator() == true }
    val (glyph, active) =
        when (audio.ringerMode) {
            AudioManager.RINGER_MODE_VIBRATE -> Icons.Rounded.Vibration to true
            AudioManager.RINGER_MODE_SILENT -> Icons.Rounded.NotificationsOff to true
            else -> Icons.AutoMirrored.Rounded.VolumeUp to false
        }
    Box(
        modifier =
            Modifier.size(size)
                .clip(CircleShape)
                .background(if (active) Color.White else oneUiOff())
                .clickable {
                    audioManager.ringerModeInternal =
                        when (audio.ringerMode) {
                            AudioManager.RINGER_MODE_NORMAL ->
                                if (canVibrate) AudioManager.RINGER_MODE_VIBRATE
                                else AudioManager.RINGER_MODE_SILENT
                            AudioManager.RINGER_MODE_VIBRATE -> AudioManager.RINGER_MODE_SILENT
                            else -> AudioManager.RINGER_MODE_NORMAL
                        }
                },
        contentAlignment = Alignment.Center,
    ) {
        MaterialIcon(
            imageVector = glyph,
            contentDescription = null,
            tint = if (active) Color(0xFF1C1B1F) else Color.White,
            modifier = Modifier.size(size * 0.42f),
        )
    }
}

@Composable
fun OneUiExtraDimButton(size: Dp) {
    val context = LocalContext.current
    val resolver = context.contentResolver
    val key = Settings.Secure.REDUCE_BRIGHT_COLORS_ACTIVATED
    fun read() = Settings.Secure.getIntForUser(resolver, key, 0, UserHandle.USER_CURRENT) == 1
    var active by remember { mutableStateOf(read()) }
    DisposableEffect(resolver) {
        val observer =
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    active = read()
                }
            }
        resolver.registerContentObserver(
            Settings.Secure.getUriFor(key),
            false,
            observer,
            UserHandle.USER_ALL,
        )
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    OneUiRoundButton(size, Icons.Rounded.DarkMode, active) {
        Settings.Secure.putIntForUser(resolver, key, if (active) 0 else 1, UserHandle.USER_CURRENT)
    }
}

@Composable
fun OneUiVolumePanelButton(size: Dp) {
    val (audioManager, _) = rememberAudioState()
    OneUiRoundButton(size, Icons.AutoMirrored.Rounded.VolumeUp, active = false) {
        audioManager.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            AudioManager.ADJUST_SAME,
            AudioManager.FLAG_SHOW_UI,
        )
    }
}

@Composable
private fun OneUiRoundButton(size: Dp, glyph: ImageVector, active: Boolean, onClick: () -> Unit) {
    Box(
        modifier =
            Modifier.size(size)
                .glassPress()
                .clip(CircleShape)
                .background(if (active) Color.White else oneUiOff())
                .glassRim(CircleShape)
                .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        MaterialIcon(
            imageVector = glyph,
            contentDescription = null,
            tint = if (active) Color(0xFF1C1B1F) else Color.White,
            modifier = Modifier.size(size * 0.42f),
        )
    }
}

@Composable
private fun OneUiIdleMedia() {
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .height(OneUiPillHeight)
                .oneUiCard(OneUiPillCorner)
                .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = spacedBy(14.dp),
    ) {
        MaterialIcon(
            imageVector = Icons.Rounded.MusicNote,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(R.string.oneui_play_music),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleSmall,
        )
    }
}
