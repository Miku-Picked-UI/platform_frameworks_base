/*
 * Copyright (C) 2024 The Android Open Source Project
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

import android.content.Context
import com.android.systemui.penguin.LocalQsPanels
import com.android.systemui.penguin.QsEditMode
import com.android.systemui.qs.composefragment.ColorOsEditor
import com.android.systemui.qs.composefragment.DEFAULT_SLIDERS_SPAN
import com.android.systemui.qs.panels.shared.model.SizedTile
import com.android.systemui.qs.panels.shared.model.SizedTileImpl
import com.android.systemui.qs.panels.ui.model.GridCell
import com.android.systemui.qs.panels.ui.model.SpacerGridCell
import com.android.systemui.qs.panels.ui.model.TileGridCell
import com.android.systemui.qs.panels.ui.model.toGridCells
import com.android.systemui.qs.ui.composable.panelElementPreviewHeights
import com.android.systemui.qs.ui.composable.panelElementPreviews
import com.android.systemui.qs.ui.composable.qsHeaderPreview
import android.content.ContentResolver
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.qs.composefragment.POSITION_ABOVE_GRID
import com.android.systemui.qs.composefragment.POSITION_BELOW_GRID
import com.android.systemui.qs.composefragment.POSITION_HEADER
import com.android.systemui.qs.composefragment.SETTING_QS_FOLDER_POSITION
import com.android.systemui.qs.composefragment.SETTING_QS_FOLDER_SPAN
import com.android.systemui.qs.composefragment.SETTING_QS_MEDIA_SPAN
import com.android.systemui.qs.composefragment.SETTING_QS_SLIDERS_POSITION
import com.android.systemui.qs.composefragment.SETTING_QS_SLIDERS_SPAN
import com.android.systemui.qs.composefragment.SETTING_QS_MEDIA_POSITION
import com.android.systemui.qs.panels.ui.viewmodel.AvailableEditActions
import com.android.systemui.qs.composefragment.connectivityFolderEnabled
import com.android.systemui.qs.composefragment.connectivityFolderSpecs
import com.android.systemui.qs.composefragment.effectiveQsPanelStyle
import com.android.systemui.qs.composefragment.secureIntSetting
import com.android.systemui.qs.panels.ui.viewmodel.EditModeViewModel
import com.android.systemui.qs.panels.ui.viewmodel.EditTileViewModel
import androidx.compose.ui.unit.Dp
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.shared.model.TileCategory
import com.android.systemui.qs.shared.style.QsPanelStyle
import com.android.systemui.res.R

val FOLDER_SPEC = TileSpec.create("penguin_connectivity_folder")
val MEDIA_SPEC = TileSpec.create("penguin_media_player")
val SLIDERS_SPEC = TileSpec.create("penguin_sliders")

val PANEL_ELEMENT_SPECS = setOf(FOLDER_SPEC, MEDIA_SPEC, SLIDERS_SPEC)

const val PANEL_FILLER_COLUMNS = 2

private const val PANEL_FILLER_ROWS = 2

fun panelFillerTiles(pool: List<TileSpec>, largeTiles: Set<TileSpec>): List<TileSpec> {
    val taken = mutableListOf<TileSpec>()
    var row = 0
    var used = 0
    for (spec in pool) {
        val width = if (spec in largeTiles) PANEL_FILLER_COLUMNS else 1
        if (used + width > PANEL_FILLER_COLUMNS) {
            row++
            used = 0
        }
        if (row >= PANEL_FILLER_ROWS) break
        taken.add(spec)
        used += width
        if (used >= PANEL_FILLER_COLUMNS) {
            row++
            used = 0
        }
    }
    return taken
}

fun TileSpec.panelSpanSetting(): String? =
    when (this) {
        FOLDER_SPEC -> SETTING_QS_FOLDER_SPAN
        MEDIA_SPEC -> SETTING_QS_MEDIA_SPAN
        SLIDERS_SPEC -> SETTING_QS_SLIDERS_SPAN
        else -> null
    }

val LocalPanelElementPreview =
    compositionLocalOf<Map<TileSpec, @Composable () -> Unit>> { emptyMap() }

val LocalPanelElementHeight = compositionLocalOf<Map<TileSpec, Dp>> { emptyMap() }

val LocalQsHeaderPreview = compositionLocalOf<(@Composable () -> Unit)?> { null }

private fun ContentResolver.folderTilesToKeep(
    folderMemberSpecs: List<String>,
    written: List<TileSpec>,
): List<TileSpec> =
    (Settings.Secure.getString(this, Settings.Secure.QS_TILES) ?: "")
        .split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() && it in folderMemberSpecs }
        .map { TileSpec.create(it) }
        .filterNot { it in written }


private fun TileSpec.positionSetting() =
    when (this) {
        FOLDER_SPEC -> SETTING_QS_FOLDER_POSITION
        SLIDERS_SPEC -> SETTING_QS_SLIDERS_POSITION
        else -> SETTING_QS_MEDIA_POSITION
    }

private fun TileSpec.editIndexSetting() =
    when (this) {
        FOLDER_SPEC -> "qs_connectivity_folder_edit_index"
        SLIDERS_SPEC -> "qs_sliders_edit_index"
        else -> "qs_media_edit_index"
    }

private fun ContentResolver.editIndex(spec: TileSpec) =
    Settings.Secure.getInt(
        this,
        spec.editIndexSetting(),
        when (spec) {
            MEDIA_SPEC -> 0
            FOLDER_SPEC -> 1
            else -> 2
        },
    )

private fun ContentResolver.park(spec: TileSpec) {
    Settings.Secure.putInt(this, spec.positionSetting(), POSITION_HEADER)
}

private fun List<EditTileViewModel>.withPanelElements(
    resolver: ContentResolver,
    folderEnabled: Boolean,
): List<EditTileViewModel> {
    val current = filter { it.isCurrent }.toMutableList()
    val rest = filterNot { it.isCurrent }
    PANEL_ELEMENT_SPECS.filter { folderEnabled || it != FOLDER_SPEC }
        .sortedBy { resolver.editIndex(it) }
        .forEach { spec ->
            val index = resolver.editIndex(spec).coerceIn(0, current.size)
            current.add(index, spec.toEditTileViewModel())
        }
    return current + rest
}

private fun List<TileSpec>.stripPanelElements(resolver: ContentResolver): List<TileSpec> {
    val firstTile = indexOfFirst { !it.isPanelElement() }
    forEachIndexed { index, spec ->
        if (spec.isPanelElement()) {
            Settings.Secure.putInt(resolver, spec.editIndexSetting(), index)
            val position =
                if (firstTile == -1 || index < firstTile) POSITION_ABOVE_GRID
                else POSITION_BELOW_GRID
            Settings.Secure.putInt(resolver, spec.positionSetting(), position)
        }
    }
    return filterNot { it.isPanelElement() }
}

private fun TileSpec.toEditTileViewModel(): EditTileViewModel {
    val label =
        when (this) {
            FOLDER_SPEC -> "Connectivity folder"
            SLIDERS_SPEC -> "Brightness and volume"
            else -> "Media player"
        }
    val iconRes =
        when (this) {
            FOLDER_SPEC -> R.drawable.ic_apps_expressive
            SLIDERS_SPEC -> R.drawable.ic_brightness_full
            else -> R.drawable.ic_music_note
        }
    return EditTileViewModel(
        tileSpec = this,
        icon = Icon.Resource(iconRes, null),
        label = AnnotatedString(label),
        inlinedLabel = null,
        appName = null,
        appIcon = null,
        isCurrent = true,
        isDualTarget = false,
        availableEditActions = setOf(AvailableEditActions.MOVE, AvailableEditActions.REMOVE),
        category = TileCategory.CONNECTIVITY,
    )
}

fun List<SizedTile<EditTileViewModel>>.panelRows(columns: Int, startingRow: Int): List<GridCell> {
    val out = mutableListOf<GridCell>()
    val hoisted = BooleanArray(size)
    val plain = mutableListOf<SizedTile<EditTileViewModel>>()
    var row = startingRow

    fun flushPlain() {
        if (plain.isEmpty()) return
        val cells = plain.toGridCells(columns, row)
        out.addAll(cells)
        row = (cells.maxOfOrNull { it.row } ?: (row - 1)) + 1
        plain.clear()
    }

    fun spacers(count: Int, atRow: Int) = List(count) { SpacerGridCell(atRow) }

    var index = 0
    while (index < size) {
        if (hoisted[index]) {
            index++
            continue
        }
        val sized = this[index]
        if (sized.tile.tileSpec !in PANEL_ELEMENT_SPECS) {
            plain.add(sized)
            index++
            continue
        }
        flushPlain()
        if (sized.width >= columns) {
            out.add(TileGridCell(sized, row, 0))
            row++
            index++
            continue
        }

        val partner =
            getOrNull(index + 1)?.takeIf {
                it.tile.tileSpec in PANEL_ELEMENT_SPECS && it.width < columns
            }
        if (partner != null) {
            out.add(TileGridCell(sized, row, 0, rows = 2))
            out.add(TileGridCell(partner, row, sized.width, rows = 2))
            out.addAll(spacers(columns, row + 1))
            row += 2
            index += 2
            continue
        }

        val gap = columns - sized.width
        val fillerRows = List(2) { mutableListOf<SizedTile<EditTileViewModel>>() }
        var scan = index + 1
        var fillerRow = 0
        var used = 0
        while (scan < size && fillerRow < 2) {
            val candidate = this[scan]
            if (candidate.tile.tileSpec in PANEL_ELEMENT_SPECS) break
            val width = candidate.width.coerceAtMost(gap)
            if (used + width > gap) {
                fillerRow++
                used = 0
                continue
            }
            fillerRows[fillerRow].add(SizedTileImpl(candidate.tile, width))
            hoisted[scan] = true
            used += width
            scan++
            if (used >= gap) {
                fillerRow++
                used = 0
            }
        }

        fun fillerCells(
            fillers: List<SizedTile<EditTileViewModel>>,
            atRow: Int,
            startColumn: Int,
            before: Boolean,
        ): List<GridCell> {
            var column = startColumn
            val cells = mutableListOf<GridCell>()
            fillers.forEach {
                cells.add(TileGridCell(it, atRow, column, hoistedBefore = before))
                column += it.width
            }
            repeat(gap - fillers.sumOf { it.width }) { cells.add(SpacerGridCell(atRow)) }
            return cells
        }

        val alignEnd = sized.tile.tileSpec == SLIDERS_SPEC
        if (alignEnd) {
            out.addAll(fillerCells(fillerRows[0], row, 0, before = true))
            out.add(TileGridCell(sized, row, gap, rows = 2))
            out.addAll(fillerCells(fillerRows[1], row + 1, 0, before = false))
            out.addAll(spacers(sized.width, row + 1))
        } else {
            out.add(TileGridCell(sized, row, 0, rows = 2))
            out.addAll(fillerCells(fillerRows[0], row, sized.width, before = false))
            out.addAll(spacers(sized.width, row + 1))
            out.addAll(fillerCells(fillerRows[1], row + 1, sized.width, before = false))
        }
        row += 2
        index++
    }
    flushPlain()
    return out
}

class StyledQsEditMode : QsEditMode {
    private var folderMemberSpecs: List<String> = emptyList()
    private var panelElementsEditable = false

    @Composable
    override fun Takeover(
        viewModel: EditModeViewModel,
        tiles: List<EditTileViewModel>,
        gridLayout: GridLayout,
        modifier: Modifier,
    ): Boolean {
        val resolver = LocalContext.current.contentResolver
        val panelStyle = effectiveQsPanelStyle()
        val folderMemberSpecs = folderMemberSpecs(panelStyle)
        if (panelStyle == QsPanelStyle.Harmony) {
            val present = remember(tiles) { tiles.filter { it.isCurrent }.map { it.tileSpec.spec } }
            val fixedSpecs =
                remember(present) {
                    buildSet {
                        listOf("wifi", "internet").firstOrNull { it in present }?.let(::add)
                        if ("bt" in present) add("bt")
                        if ("cast" in present) add("cast")
                    }
                }
            HarmonyEditMode(
                tiles = tiles,
                fixedSpecs = fixedSpecs,
                onAddTile = viewModel::addTile,
                onRemoveTile = viewModel::removeTile,
                onSetTiles = viewModel::setTiles,
                onStopEditing = viewModel::stopEditing,
                modifier = modifier,
            )
            return true
        }
        if (panelStyle == QsPanelStyle.ColorOs) {
            ColorOsEditor(viewModel, modifier)
            return true
        }
        if (panelStyle == QsPanelStyle.Penguin) {
            gridLayout.TileSizing { largeTiles, resize ->
                PenguinEditMode(
                    tiles = tiles,
                    folderMemberSpecs = folderMemberSpecs,
                    folderEnabled = connectivityFolderEnabled(),
                    largeTiles = largeTiles,
                    onResizeTile = resize,
                    onAddTile = viewModel::addTile,
                    onRemoveTile = viewModel::removeTile,
                    onCommitTiles = { specs ->
                        viewModel.setTiles(
                            specs + resolver.folderTilesToKeep(folderMemberSpecs, specs)
                        )
                    },
                    onStopEditing = viewModel::stopEditing,
                    modifier = modifier,
                )
            }
            return true
        }
        return false
    }

    @Composable
    override fun tiles(tiles: List<EditTileViewModel>): List<EditTileViewModel> {
        val resolver = LocalContext.current.contentResolver
        val panelStyle = effectiveQsPanelStyle()
        panelElementsEditable = panelStyle == QsPanelStyle.Penguin
        val folderMemberSpecs = folderMemberSpecs(panelStyle)
        this.folderMemberSpecs = folderMemberSpecs
        val folderEnabled = connectivityFolderEnabled()
        val gridTiles =
            remember(tiles, folderMemberSpecs) {
                tiles.filterNot { it.isCurrent && it.tileSpec.spec in folderMemberSpecs }
            }
        return if (panelElementsEditable) {
            gridTiles.withPanelElements(resolver, folderEnabled)
        } else {
            gridTiles
        }
    }

    override fun remove(context: Context, spec: TileSpec, removeTile: (TileSpec) -> Unit) {
        if (spec.isPanelElement()) context.contentResolver.park(spec) else removeTile(spec)
    }

    override fun commit(context: Context, specs: List<TileSpec>, setTiles: (List<TileSpec>) -> Unit) {
        val resolver = context.contentResolver
        val merged = specs + resolver.folderTilesToKeep(folderMemberSpecs, specs)
        setTiles(if (panelElementsEditable) merged.stripPanelElements(resolver) else merged)
    }

    override val elementSpecs: Set<TileSpec> = PANEL_ELEMENT_SPECS

    @Composable
    override fun fullWidthSpecs(): Set<TileSpec> = buildSet {
        if (secureIntSetting(SETTING_QS_FOLDER_SPAN, 1) >= 2) add(FOLDER_SPEC)
        if (secureIntSetting(SETTING_QS_MEDIA_SPAN, 1) >= 2) add(MEDIA_SPEC)
        if (secureIntSetting(SETTING_QS_SLIDERS_SPAN, DEFAULT_SLIDERS_SPAN) >= 2) add(SLIDERS_SPEC)
    }

    override fun resize(context: Context, spec: TileSpec, toIcon: Boolean): Boolean {
        val spanSetting = spec.panelSpanSetting() ?: return false
        Settings.Secure.putInt(context.contentResolver, spanSetting, if (toIcon) 1 else 2)
        return true
    }

    @Composable
    override fun preview(spec: TileSpec): (@Composable () -> Unit)? {
        val container = LocalQsPanels.current?.container ?: return null
        return panelElementPreviews(container)[spec]
    }

    @Composable
    override fun previewHeight(spec: TileSpec): Dp? = panelElementPreviewHeights()[spec]

    @Composable
    override fun HeaderPreview() {
        val container = LocalQsPanels.current?.container ?: return
        qsHeaderPreview(container)?.invoke()
    }

    override fun rows(
        tiles: List<SizedTile<EditTileViewModel>>,
        columns: Int,
        startingRow: Int,
    ): List<GridCell> = tiles.panelRows(columns, startingRow)

    @Composable
    private fun folderMemberSpecs(panelStyle: QsPanelStyle): List<String> =
        // COS shows Wi-Fi and Bluetooth as pills and the other connectivity tiles in its grid,
        // never in the connectivity folder, so its editor must list them all.
        if (!panelStyle.stockLayout && panelStyle != QsPanelStyle.ColorOs &&
            connectivityFolderEnabled()) {
            connectivityFolderSpecs()
        } else {
            emptyList()
        }
}
