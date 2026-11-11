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

package com.android.systemui.qs.panels.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement.spacedBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon as MaterialIcon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.android.compose.theme.LocalAndroidColorScheme
import com.android.systemui.qs.panels.ui.compose.infinitegrid.SmallTileContent
import com.android.systemui.qs.panels.ui.viewmodel.EditTileViewModel
import com.android.systemui.qs.pipeline.domain.interactor.CurrentTilesInteractor.Companion.POSITION_AT_END
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.res.R
import kotlin.math.roundToInt

private const val Columns = 5
private val CellHeight = 96.dp
private val CircleSize = 52.dp
private val CardCorner = 24.dp
private val HarmonyAccent = Color(0xFF0A59F7)

@Composable
fun HarmonyEditMode(
    tiles: List<EditTileViewModel>,
    fixedSpecs: Set<String>,
    onAddTile: (TileSpec, Int) -> Unit,
    onRemoveTile: (TileSpec) -> Unit,
    onSetTiles: (List<TileSpec>) -> Unit,
    onStopEditing: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val current = remember(tiles) { tiles.filter { it.isCurrent } }
    val available = remember(tiles) { tiles.filterNot { it.isCurrent } }

    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(48.dp).clip(CircleShape).clickable { onStopEditing() },
                contentAlignment = Alignment.Center,
            ) {
                MaterialIcon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            Column(Modifier.padding(start = 8.dp)) {
                Text(
                    text = stringResource(R.string.harmony_edit_title),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    text = stringResource(R.string.harmony_edit_subtitle),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        EditCard {
            ReorderGrid(
                tiles = current.filterNot { it.tileSpec.spec in fixedSpecs },
                onRemoveTile = onRemoveTile,
                onCommit = { order ->
                    val moved = order.iterator()
                    onSetTiles(
                        current.map { tile ->
                            if (tile.tileSpec.spec in fixedSpecs || !moved.hasNext()) tile.tileSpec
                            else moved.next()
                        }
                    )
                },
            )
        }

        if (available.isNotEmpty()) {
            Text(
                text = stringResource(R.string.harmony_edit_more),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(start = 4.dp),
            )
            EditCard {
                available.chunked(Columns).forEach { row ->
                    Row(Modifier.fillMaxWidth()) {
                        row.forEach { tile ->
                            key(tile.tileSpec) {
                                EditCell(
                                    tile = tile,
                                    badgeAdds = true,
                                    onBadge = { onAddTile(tile.tileSpec, POSITION_AT_END) },
                                    modifier = Modifier.weight(1f).height(CellHeight),
                                )
                            }
                        }
                        repeat(Columns - row.size) { Box(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun EditCard(content: @Composable () -> Unit) {
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(CardCorner))
                .background(LocalAndroidColorScheme.current.surfaceEffect1.copy(alpha = 0.6f))
                .padding(vertical = 12.dp, horizontal = 6.dp)
    ) {
        content()
    }
}

@Composable
private fun ReorderGrid(
    tiles: List<EditTileViewModel>,
    onRemoveTile: (TileSpec) -> Unit,
    onCommit: (List<TileSpec>) -> Unit,
) {
    var order by remember { mutableStateOf(tiles) }
    var dragged by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    LaunchedEffect(tiles) { if (dragged < 0) order = tiles }
    val commit by rememberUpdatedState(onCommit)

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val density = LocalDensity.current
        val cellWidthDp = maxWidth / Columns
        val cellWidth = with(density) { cellWidthDp.toPx() }
        val cellHeight = with(density) { CellHeight.toPx() }
        val rows = (order.size + Columns - 1) / Columns
        fun origin(index: Int) =
            Offset((index % Columns) * cellWidth, (index / Columns) * cellHeight)

        Box(Modifier.fillMaxWidth().height(CellHeight * rows.coerceAtLeast(1))) {
            order.forEachIndexed { index, tile ->
                key(tile.tileSpec) {
                    val isDragged = index == dragged
                    val position = origin(index) + if (isDragged) dragOffset else Offset.Zero
                    EditCell(
                        tile = tile,
                        badgeAdds = false,
                        onBadge =
                            if (tile.isRemovable) {
                                { onRemoveTile(tile.tileSpec) }
                            } else {
                                null
                            },
                        lifted = isDragged,
                        modifier =
                            Modifier.offset {
                                    IntOffset(position.x.roundToInt(), position.y.roundToInt())
                                }
                                .size(cellWidthDp, CellHeight)
                                .zIndex(if (isDragged) 1f else 0f)
                                .pointerInput(tile.tileSpec) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = {
                                            dragged = order.indexOf(tile)
                                            dragOffset = Offset.Zero
                                        },
                                        onDrag = { change, amount ->
                                            change.consume()
                                            val from = dragged
                                            if (from < 0) return@detectDragGesturesAfterLongPress
                                            dragOffset += amount
                                            val centre =
                                                origin(from) +
                                                    dragOffset +
                                                    Offset(cellWidth / 2, cellHeight / 2)
                                            val column =
                                                (centre.x / cellWidth).toInt().coerceIn(0, Columns - 1)
                                            val row = (centre.y / cellHeight).toInt().coerceAtLeast(0)
                                            val to =
                                                (row * Columns + column).coerceIn(0, order.size - 1)
                                            if (to != from) {
                                                order =
                                                    order.toMutableList().apply {
                                                        add(to, removeAt(from))
                                                    }
                                                dragOffset -= origin(to) - origin(from)
                                                dragged = to
                                            }
                                        },
                                        onDragEnd = {
                                            dragged = -1
                                            dragOffset = Offset.Zero
                                            commit(order.map { it.tileSpec })
                                        },
                                        onDragCancel = {
                                            dragged = -1
                                            dragOffset = Offset.Zero
                                            commit(order.map { it.tileSpec })
                                        },
                                    )
                                },
                    )
                }
            }
        }
    }
}

@Composable
private fun EditCell(
    tile: EditTileViewModel,
    badgeAdds: Boolean,
    onBadge: (() -> Unit)?,
    modifier: Modifier = Modifier,
    lifted: Boolean = false,
) {
    Column(
        modifier = modifier.padding(horizontal = 2.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = spacedBy(6.dp),
    ) {
        Box(Modifier.scale(if (lifted) 1.12f else 1f)) {
            Box(
                modifier =
                    Modifier.size(CircleSize)
                        .clip(CircleShape)
                        .background(
                            if (badgeAdds) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                            else HarmonyAccent
                        ),
                contentAlignment = Alignment.Center,
            ) {
                SmallTileContent(
                    iconProvider = { tile.icon },
                    color = if (badgeAdds) MaterialTheme.colorScheme.onSurface else Color.White,
                    size = { 24.dp },
                )
            }
            if (onBadge != null) {
                Box(
                    modifier =
                        Modifier.align(Alignment.TopEnd)
                            .offset(x = 6.dp, y = (-6).dp)
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                            .clickable { onBadge() },
                    contentAlignment = Alignment.Center,
                ) {
                    MaterialIcon(
                        imageVector = if (badgeAdds) Icons.Filled.Add else Icons.Filled.Remove,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
        Text(
            text = tile.label.text,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}
