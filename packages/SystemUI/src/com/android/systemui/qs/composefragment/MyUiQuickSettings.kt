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
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Arrangement.spacedBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import com.android.systemui.common.ui.compose.Icon
import com.android.systemui.common.ui.compose.PagerDots
import com.android.systemui.qs.panels.ui.compose.TileListener
import com.android.systemui.qs.panels.ui.viewmodel.TileViewModel
import com.android.systemui.qs.shared.style.glassPress
import com.android.systemui.qs.shared.style.glassRim

internal val LocalMyUiInteractive = compositionLocalOf { true }

@Composable
private fun Modifier.myUiClickable(onClick: () -> Unit, onLongClick: () -> Unit): Modifier =
    if (LocalMyUiInteractive.current) {
        combinedClickable(onClick = onClick, onLongClick = onLongClick)
    } else {
        this
    }

private val CardSpecs = listOf("internet", "wifi", "cell", "bt")

internal const val MyUiTileAspect = 166f / 188f

internal const val MyUiGridRows = 3
internal val MyUiGridGap = 18.dp

private val CardCorner = 22.dp
private val CardRowIcon = 40.dp
private val TileCorner = 22.dp

@Composable
fun MyUiConnectivityCard(tiles: List<TileViewModel>, modifier: Modifier = Modifier) {
    val bySpec = remember(tiles) { tiles.associateBy { it.spec.spec } }
    val rows = remember(bySpec) { CardSpecs.mapNotNull { bySpec[it] }.take(3) }
    if (rows.isEmpty()) return

    var listening by remember { mutableStateOf(false) }
    LifecycleStartEffect(Unit) {
        listening = true
        onStopOrDispose { listening = false }
    }
    TileListener(rows) { listening }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(CardCorner))
                .background(glassSurface())
                .glassRim(RoundedCornerShape(CardCorner))
                .padding(vertical = 6.dp),
        verticalArrangement = Arrangement.SpaceEvenly,
    ) {
        rows.forEach { MyUiCardRow(it) }
    }
}

@Composable
private fun MyUiCardRow(tile: TileViewModel) {
    val (uiState, icon) = rememberTileState(tile)
    val details = LocalQsTileDetails.current
    val active = uiState.visualState == Tile.STATE_ACTIVE
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .myUiClickable(
                    onClick = { tile.primaryAction(uiState, details) },
                    onLongClick = { tile.detailsOrSettings(uiState, details) },
                )
                .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier.size(CardRowIcon)
                    .clip(CircleShape)
                    .background(
                        if (active) MaterialTheme.colorScheme.inverseSurface
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f)
                    ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon = icon,
                tint = if (active) activeContent() else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(22.dp),
            )
        }
        Column {
            Text(
                text = uiState.label,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = uiState.secondaryLabel.ifBlank { if (active) "On" else "Off" },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun activeContent(): Color =
    colorResource(
        if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) {
            android.R.color.system_primary_light
        } else {
            android.R.color.system_primary_dark
        }
    )

@Composable
fun MyUiCardSpecs(tiles: List<TileViewModel>): Set<com.android.systemui.qs.pipeline.shared.TileSpec> {
    val bySpec = remember(tiles) { tiles.associateBy { it.spec.spec } }
    return remember(bySpec) { CardSpecs.mapNotNull { bySpec[it]?.spec }.take(3).toSet() }
}

@Composable
fun MyUiTile(tile: TileViewModel, modifier: Modifier = Modifier) {
    val (uiState, icon) = rememberTileState(tile)
    val details = LocalQsTileDetails.current
    val active = uiState.visualState == Tile.STATE_ACTIVE
    val content = if (active) activeContent() else MaterialTheme.colorScheme.onSurface
    Column(
        modifier =
            modifier
                .aspectRatio(MyUiTileAspect)
                .glassPress()
                .clip(RoundedCornerShape(TileCorner))
                .background(
                    if (active) MaterialTheme.colorScheme.inverseSurface else glassSurface()
                )
                .glassRim(RoundedCornerShape(TileCorner))
                .myUiClickable(
                    onClick = { tile.primaryAction(uiState, details) },
                    onLongClick = { tile.detailsOrSettings(uiState, details) },
                )
                .padding(8.dp),
        verticalArrangement = spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon = icon, tint = content, modifier = Modifier.size(26.dp))
        Text(
            text = uiState.label,
            color = content,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
fun MyUiTileGrid(
    tiles: List<TileViewModel>,
    columns: Int,
    gap: Dp,
    modifier: Modifier = Modifier,
    rows: Int = MyUiGridRows,
) {
    if (tiles.isEmpty()) return
    var listening by remember { mutableStateOf(false) }
    LifecycleStartEffect(Unit) {
        listening = true
        onStopOrDispose { listening = false }
    }
    TileListener(tiles) { listening }

    val pages = remember(tiles, columns, rows) { tiles.chunked(columns * rows) }
    if (pages.size == 1) {
        MyUiTilePage(pages[0], columns, gap, rows, modifier)
        return
    }
    val pagerState = rememberPagerState(0) { pages.size }
    LaunchedEffect(pagerState, listening) {
        if (!listening) pagerState.scrollToPage(0)
    }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = spacedBy(gap)) {
        HorizontalPager(state = pagerState, pageSpacing = gap) { page ->
            MyUiTilePage(pages[page], columns, gap, rows)
        }
        PagerDots(
            pagerState = pagerState,
            activeColor = MaterialTheme.colorScheme.onSurface,
            nonActiveColor = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
    }
}

@Composable
private fun MyUiTilePage(
    tiles: List<TileViewModel>,
    columns: Int,
    gap: Dp,
    rows: Int,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = spacedBy(gap)) {
        val filled = tiles.chunked(columns)
        repeat(rows) { index ->
            val row = filled.getOrNull(index).orEmpty()
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = spacedBy(gap)) {
                row.forEach { tile -> MyUiTile(tile, Modifier.weight(1f)) }
                repeat(columns - row.size) {
                    Box(Modifier.weight(1f).aspectRatio(MyUiTileAspect))
                }
            }
        }
    }
}
