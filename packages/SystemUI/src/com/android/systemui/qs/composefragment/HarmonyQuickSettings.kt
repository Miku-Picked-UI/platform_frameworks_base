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

import android.service.quicksettings.Tile
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Arrangement.spacedBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon as MaterialIcon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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

internal val HarmonyGap = 12.dp
private val HarmonyCorner = 24.dp
private val HarmonyAccent = Color(0xFF0A59F7)
private val HarmonyToggleSize = 52.dp
private const val HarmonyToggleColumns = 5

private val WifiSpecs = listOf("wifi", "internet")
private const val BluetoothSpec = "bt"
private const val CastSpec = "cast"

fun harmonyCardSpecs(tiles: List<TileViewModel>): Set<String> {
    val present = tiles.map { it.spec.spec }.toSet()
    return buildSet {
        WifiSpecs.firstOrNull { it in present }?.let(::add)
        if (BluetoothSpec in present) add(BluetoothSpec)
        if (CastSpec in present) add(CastSpec)
    }
}

@Composable
private fun Modifier.harmonyCard(): Modifier =
    clip(RoundedCornerShape(HarmonyCorner)).background(glassSurface()).glassRim(RoundedCornerShape(HarmonyCorner))

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
fun HarmonyTitle(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.harmony_control_centre),
        color = MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.headlineSmall,
        modifier = modifier.padding(horizontal = 4.dp, vertical = 4.dp),
    )
}

@Composable
fun HarmonyTopRow(
    tiles: List<TileViewModel>,
    media: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val bySpec = remember(tiles) { tiles.associateBy { it.spec.spec } }
    val wifi = WifiSpecs.firstNotNullOfOrNull { bySpec[it] }
    val bluetooth = bySpec[BluetoothSpec]
    ListenTo(listOfNotNull(wifi, bluetooth))
    val height = qsModuleHeight(2)
    Row(
        modifier = modifier.fillMaxWidth().height(height),
        horizontalArrangement = spacedBy(HarmonyGap),
    ) {
        Box(Modifier.weight(1f).fillMaxSize()) { media?.invoke() ?: HarmonyIdleMedia() }
        Column(Modifier.weight(1f).fillMaxSize(), verticalArrangement = spacedBy(HarmonyGap)) {
            wifi?.let { HarmonyNetworkCard(it, Modifier.weight(1f)) }
            bluetooth?.let { HarmonyNetworkCard(it, Modifier.weight(1f), dropDown = true) }
        }
    }
}

@Composable
private fun HarmonyIdleMedia() {
    Column(
        modifier = Modifier.fillMaxSize().harmonyCard().padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Box(
            modifier =
                Modifier.size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
            contentAlignment = Alignment.Center,
        ) {
            MaterialIcon(
                imageVector = Icons.Filled.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = stringResource(R.string.harmony_not_playing),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleSmall,
        )
    }
}

@Composable
private fun HarmonyNetworkCard(
    tile: TileViewModel,
    modifier: Modifier = Modifier,
    dropDown: Boolean = false,
) {
    val (uiState, icon) = rememberTileState(tile)
    val details = LocalQsTileDetails.current
    val active = uiState.visualState == Tile.STATE_ACTIVE
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .harmonyCard()
                .combinedClickable(
                    onClick = { tile.primaryAction(uiState, details) },
                    onLongClick = { tile.detailsOrSettings(uiState, details) },
                )
                .padding(start = 12.dp, end = 8.dp),
        horizontalArrangement = spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HarmonyCircle(icon = icon, active = active, size = 40.dp)
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
        if (dropDown) {
            Box(
                modifier =
                    Modifier.size(32.dp).clip(CircleShape).clickable { tile.detailsOrMain(details) },
                contentAlignment = Alignment.Center,
            ) {
                MaterialIcon(
                    imageVector = Icons.Filled.ArrowDropDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun HarmonyCircle(icon: Icon, active: Boolean, size: Dp) {
    val background by
        animateColorAsState(
            if (active) HarmonyAccent
            else if (liquidGlassOn) LiquidGlassControl
            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f),
            label = "HarmonyCircle",
        )
    Box(
        modifier = Modifier.size(size).glassPress().clip(CircleShape).background(background).glassRim(CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        SmallTileContent(
            iconProvider = { icon },
            color = if (active) Color.White else MaterialTheme.colorScheme.onSurface,
            size = { size * 0.46f },
        )
    }
}

@Composable
fun HarmonyTogglesCard(
    tiles: List<TileViewModel>,
    brightness: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    ListenTo(tiles)
    var expanded by remember { mutableStateOf(false) }
    LifecycleStartEffect(Unit) { onStopOrDispose { expanded = false } }
    val canExpand = tiles.size > HarmonyToggleColumns
    val shown = if (expanded) tiles else tiles.take(HarmonyToggleColumns)

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .harmonyCard()
                .animateContentSize()
                .padding(start = 14.dp, end = 14.dp, top = 16.dp),
        verticalArrangement = spacedBy(14.dp),
    ) {
        shown.chunked(HarmonyToggleColumns).forEach { row ->
            Row(modifier = Modifier.fillMaxWidth()) {
                row.forEach { HarmonyToggle(it, labelled = expanded, Modifier.weight(1f)) }
                repeat(HarmonyToggleColumns - row.size) { Box(Modifier.weight(1f)) }
            }
        }
        brightness()
        Box(
            modifier =
                Modifier.fillMaxWidth()
                    .height(24.dp)
                    .then(
                        if (canExpand) {
                            Modifier.clickable { expanded = !expanded }
                                .pointerInput(Unit) {
                                    detectVerticalDragGestures { change, dragAmount ->
                                        if (dragAmount > 8f) expanded = true
                                        else if (dragAmount < -8f) expanded = false
                                        change.consume()
                                    }
                                }
                        } else {
                            Modifier
                        }
                    ),
            contentAlignment = Alignment.Center,
        ) {
            if (canExpand) {
                Box(
                    Modifier.width(28.dp)
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f))
                )
            }
        }
    }
}

@Composable
private fun HarmonyToggle(tile: TileViewModel, labelled: Boolean, modifier: Modifier = Modifier) {
    val (uiState, icon) = rememberTileState(tile)
    val details = LocalQsTileDetails.current
    Column(
        modifier =
            modifier.combinedClickable(
                onClick = { tile.primaryAction(uiState, details) },
                onLongClick = { tile.detailsOrSettings(uiState, details) },
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = spacedBy(6.dp),
    ) {
        HarmonyCircle(icon, uiState.visualState == Tile.STATE_ACTIVE, HarmonyToggleSize)
        if (labelled) {
            Text(
                text = uiState.label,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
fun HarmonyCastCard(tiles: List<TileViewModel>, modifier: Modifier = Modifier) {
    val cast = remember(tiles) { tiles.firstOrNull { it.spec.spec == CastSpec } } ?: return
    ListenTo(listOf(cast))
    val (uiState, icon) = rememberTileState(cast)
    val details = LocalQsTileDetails.current
    val active = uiState.visualState == Tile.STATE_ACTIVE
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .harmonyCard()
                .clickable { cast.detailsOrMain(details) }
                .padding(16.dp),
        verticalArrangement = spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.harmony_devices),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.fillMaxWidth(),
        )
        Box(
            modifier =
                Modifier.size(64.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)),
            contentAlignment = Alignment.Center,
        ) {
            HarmonyCircle(icon = icon, active = active, size = 44.dp)
        }
        Text(
            text =
                if (active && uiState.secondaryLabel.isNotBlank()) uiState.secondaryLabel
                else stringResource(R.string.harmony_cast_hint),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
fun HarmonyConnectedDevices(tiles: List<TileViewModel>, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val defaults =
        remember(context) {
            mapOf(
                BluetoothSpec to context.getString(R.string.quick_settings_bluetooth_label),
                "hotspot" to context.getString(R.string.quick_settings_hotspot_label),
            )
        }
    val candidates = remember(tiles) { tiles.filter { it.spec.spec in defaults } }
    if (candidates.isEmpty()) return
    ListenTo(candidates)
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = spacedBy(HarmonyGap)) {
        candidates.forEach { tile ->
            val (uiState, icon) = rememberTileState(tile)
            val connected =
                uiState.visualState == Tile.STATE_ACTIVE &&
                    uiState.label.isNotBlank() &&
                    uiState.label != defaults[tile.spec.spec]
            if (connected) HarmonyDeviceCard(tile, uiState.label, uiState.secondaryLabel, icon)
        }
    }
}

@Composable
private fun HarmonyDeviceCard(
    tile: TileViewModel,
    name: String,
    status: String,
    icon: Icon,
) {
    val details = LocalQsTileDetails.current
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .harmonyCard()
                .clickable { tile.detailsOrMain(details) }
                .padding(16.dp),
        horizontalArrangement = spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier.size(56.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)),
            contentAlignment = Alignment.Center,
        ) {
            SmallTileContent(iconProvider = { icon }, color = HarmonyAccent, size = { 28.dp })
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = name,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text =
                    listOf(stringResource(R.string.harmony_connected), status)
                        .filter { it.isNotBlank() }
                        .joinToString(" | "),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
