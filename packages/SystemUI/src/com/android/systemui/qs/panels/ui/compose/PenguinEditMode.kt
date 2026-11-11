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

import android.content.ContentResolver
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement.spacedBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon as MaterialIcon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.android.compose.theme.LocalAndroidColorScheme
import com.android.systemui.qs.composefragment.DEFAULT_SLIDERS_SPAN
import com.android.systemui.qs.composefragment.SETTING_QS_FOLDER_SPAN
import com.android.systemui.qs.composefragment.SETTING_QS_MEDIA_SPAN
import com.android.systemui.qs.composefragment.SETTING_QS_SLIDERS_SPAN
import com.android.systemui.qs.composefragment.secureIntSetting
import com.android.systemui.qs.panels.ui.compose.infinitegrid.SmallTileContent
import com.android.systemui.qs.panels.ui.viewmodel.EditTileViewModel
import com.android.systemui.qs.pipeline.domain.interactor.CurrentTilesInteractor.Companion.POSITION_AT_END
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.res.R
import kotlinx.coroutines.delay

@Composable
fun PenguinEditMode(
    tiles: List<EditTileViewModel>,
    folderMemberSpecs: List<String>,
    folderEnabled: Boolean,
    largeTiles: Set<TileSpec>,
    onResizeTile: (TileSpec, Boolean) -> Unit,
    onAddTile: (TileSpec, Int) -> Unit,
    onRemoveTile: (TileSpec) -> Unit,
    onCommitTiles: (List<TileSpec>) -> Unit,
    onStopEditing: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val resolver = LocalContext.current.contentResolver
    val byspec = remember(tiles) { tiles.associateBy { it.tileSpec } }
    val currentSpecs =
        remember(tiles, folderMemberSpecs) {
            tiles.filter { it.isCurrent && it.tileSpec.spec !in folderMemberSpecs }.map { it.tileSpec }
        }
    val available = remember(tiles) { tiles.filterNot { it.isCurrent } }
    val elements = remember(folderEnabled) {
        listOfNotNull(FOLDER_SPEC.takeIf { folderEnabled }, MEDIA_SPEC, SLIDERS_SPEC)
    }
    val spans =
        mapOf(
            FOLDER_SPEC to secureIntSetting(SETTING_QS_FOLDER_SPAN, 1),
            MEDIA_SPEC to secureIntSetting(SETTING_QS_MEDIA_SPAN, 1),
            SLIDERS_SPEC to secureIntSetting(SETTING_QS_SLIDERS_SPAN, DEFAULT_SLIDERS_SPAN),
        )
    val indices =
        mapOf(
            FOLDER_SPEC to secureIntSetting("qs_connectivity_folder_edit_index", 1),
            MEDIA_SPEC to secureIntSetting("qs_media_edit_index", 0),
            SLIDERS_SPEC to secureIntSetting("qs_sliders_edit_index", 2),
        )

    var dragged by remember { mutableStateOf<TileSpec?>(null) }
    var order by remember { mutableStateOf(emptyList<TileSpec>()) }
    LaunchedEffect(currentSpecs, indices, elements) {
        if (dragged == null) {
            order = panelOrder(currentSpecs, elements, elements) { indices.getValue(it) }
        }
    }
    val bands = packPanel(order, largeTiles) { (spans[it] ?: 1) >= 2 }

    val bounds = remember { mutableStateMapOf<TileSpec, Rect>() }
    var pointer by remember { mutableStateOf(Offset.Zero) }
    var grab by remember { mutableStateOf(Offset.Zero) }
    var dropTarget by remember { mutableStateOf<TileSpec?>(null) }
    val commit by rememberUpdatedState {
        order.forEachIndexed { index, spec ->
            if (spec.isPanelElement()) resolver.setPanelIndex(spec, index)
        }
        onCommitTiles(order.filterNot { it.isPanelElement() })
    }
    fun moveTo(spec: TileSpec, target: TileSpec) {
        val from = order.indexOf(spec)
        val to = order.indexOf(target)
        if (from >= 0 && to >= 0 && from != to) {
            order = order.toMutableList().apply { add(to, removeAt(from)) }
        }
    }
    LaunchedEffect(dragged, dropTarget) {
        val spec = dragged ?: return@LaunchedEffect
        val target = dropTarget ?: return@LaunchedEffect
        delay(MAKE_ROOM_DELAY_MS)
        moveTo(spec, target)
        dropTarget = null
    }

    val gap = dimensionResource(R.dimen.qs_tile_margin_horizontal)
    val rowGap = dimensionResource(R.dimen.qs_tile_margin_vertical)
    val tileHeight = dimensionResource(R.dimen.common_tile_default_tile_height)

    val dropColour = MaterialTheme.colorScheme.primary
    val draggedRect by remember {
        derivedStateOf {
            val spec = dragged ?: return@derivedStateOf null
            val size = bounds[spec]?.size ?: return@derivedStateOf null
            Rect(pointer - grab, size)
        }
    }
    fun isCovered(spec: TileSpec): Boolean {
        if (spec == dragged) return false
        if (spec == dropTarget) return true
        val rect = draggedRect ?: return false
        return bounds[spec]?.center?.let { rect.contains(it) } == true
    }

    fun Modifier.movable(spec: TileSpec): Modifier =
        onGloballyPositioned { bounds[spec] = it.boundsInRoot() }
            .then(
                if (isCovered(spec)) {
                    Modifier.border(2.dp, dropColour, RoundedCornerShape(24.dp))
                } else {
                    Modifier
                }
            )
            .zIndex(if (spec == dragged) 1f else 0f)
            .graphicsLayer {
                if (spec == dragged) {
                    val at = bounds[spec]?.topLeft ?: Offset.Zero
                    translationX = pointer.x - grab.x - at.x
                    translationY = pointer.y - grab.y - at.y
                    scaleX = 1.04f
                    scaleY = 1.04f
                }
            }

    var gridOrigin by remember { mutableStateOf(Offset.Zero) }
    val dragGesture =
        Modifier.onGloballyPositioned { gridOrigin = it.boundsInRoot().topLeft }
            .pointerInput(Unit) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { start ->
                        val at = gridOrigin + start
                        val hit = bounds.entries.firstOrNull { (key, rect) ->
                            key in order && rect.contains(at)
                        } ?: return@detectDragGesturesAfterLongPress
                        dragged = hit.key
                        grab = at - hit.value.topLeft
                        pointer = at
                        dropTarget = null
                    },
                    onDrag = { change, amount ->
                        val spec = dragged ?: return@detectDragGesturesAfterLongPress
                        change.consume()
                        pointer += amount
                        val target =
                            bounds.entries
                                .firstOrNull { (key, rect) ->
                                    key != spec && key in order && rect.contains(pointer)
                                }
                                ?.key
                        if (target != null) dropTarget = target
                    },
                    onDragEnd = {
                        val spec = dragged
                        val target = dropTarget
                        if (spec != null && target != null) moveTo(spec, target)
                        dragged = null
                        dropTarget = null
                        if (spec != null) commit()
                    },
                    onDragCancel = {
                        dragged = null
                        dropTarget = null
                    },
                )
            }

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
                    text = stringResource(R.string.penguin_edit_title),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    text = stringResource(R.string.penguin_edit_subtitle),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Column(modifier = dragGesture, verticalArrangement = spacedBy(rowGap)) {
            PanelBands(
                bands = bands,
                gap = gap,
                tiles = { specs, columns ->
                    EditTileRows(specs, columns, largeTiles, gap, rowGap) { spec, cellModifier ->
                        val tile = byspec[spec] ?: return@EditTileRows
                        EditTileCell(
                            tile = tile,
                            large = spec in largeTiles,
                            height = tileHeight,
                            onRemove = if (tile.isRemovable) {
                                { onRemoveTile(spec) }
                            } else null,
                            modifier =
                                cellModifier.movable(spec).clickable {
                                    onResizeTile(spec, spec !in largeTiles)
                                },
                        )
                    }
                },
                element = { spec, half ->
                    val height =
                        if (half) tileHeight * 2 + rowGap
                        else LocalPanelElementHeight.current[spec] ?: tileHeight
                    Box(
                        modifier =
                            Modifier.fillMaxWidth()
                                .height(height)
                                .movable(spec)
                                .clip(RoundedCornerShape(24.dp))
                                .border(
                                    1.dp,
                                    MaterialTheme.colorScheme.outlineVariant,
                                    RoundedCornerShape(24.dp),
                                )
                                .clickable { resolver.setFullWidth(spec, half) }
                    ) {
                        LocalPanelElementPreview.current[spec]?.invoke()
                    }
                },
            )
        }

        if (available.isNotEmpty()) {
            Text(
                text = stringResource(R.string.penguin_edit_more),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(start = 4.dp, top = 8.dp),
            )
            available.chunked(PANEL_COLUMNS).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = spacedBy(gap)) {
                    row.forEach { tile ->
                        EditTileCell(
                            tile = tile,
                            large = false,
                            height = tileHeight,
                            onAdd = { onAddTile(tile.tileSpec, POSITION_AT_END) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    repeat(PANEL_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

private const val MAKE_ROOM_DELAY_MS = 350L

@Composable
private fun EditTileRows(
    specs: List<TileSpec>,
    columns: Int,
    largeTiles: Set<TileSpec>,
    gap: Dp,
    rowGap: Dp,
    cell: @Composable (TileSpec, Modifier) -> Unit,
) {
    val rows = mutableListOf<MutableList<TileSpec>>()
    var used = columns
    specs.forEach { spec ->
        val w = if (spec in largeTiles) minOf(2, columns) else 1
        if (used + w > columns) {
            rows += mutableListOf<TileSpec>()
            used = 0
        }
        rows.last() += spec
        used += w
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = spacedBy(rowGap)) {
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = spacedBy(gap)) {
                var taken = 0
                row.forEach { spec ->
                    val w = if (spec in largeTiles) minOf(2, columns) else 1
                    taken += w
                    cell(spec, Modifier.weight(w.toFloat()))
                }
                if (taken < columns) Spacer(Modifier.weight((columns - taken).toFloat()))
            }
        }
    }
}

@Composable
private fun EditTileCell(
    tile: EditTileViewModel,
    large: Boolean,
    height: Dp,
    modifier: Modifier = Modifier,
    onRemove: (() -> Unit)? = null,
    onAdd: (() -> Unit)? = null,
) {
    Box(modifier.height(height)) {
        Row(
            modifier =
                Modifier.fillMaxSize()
                    .clip(RoundedCornerShape(height / 2))
                    .background(LocalAndroidColorScheme.current.surfaceEffect1.copy(alpha = 0.7f))
                    .padding(horizontal = if (large) 16.dp else 0.dp),
            horizontalArrangement =
                if (large) spacedBy(10.dp) else spacedBy(0.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SmallTileContent(
                iconProvider = { tile.icon },
                color = MaterialTheme.colorScheme.onSurface,
                size = { 24.dp },
            )
            if (large) {
                Text(
                    text = tile.label.text,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        val badge = onRemove ?: onAdd
        if (badge != null) {
            Box(
                modifier =
                    Modifier.align(Alignment.TopEnd)
                        .offset(x = 4.dp, y = (-4).dp)
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .clickable { badge() },
                contentAlignment = Alignment.Center,
            ) {
                MaterialIcon(
                    imageVector = if (onAdd != null) Icons.Filled.Add else Icons.Filled.Close,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}
