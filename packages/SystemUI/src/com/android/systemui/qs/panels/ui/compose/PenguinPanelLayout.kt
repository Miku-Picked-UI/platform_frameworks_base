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
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement.spacedBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import com.android.systemui.qs.composefragment.DEFAULT_SLIDERS_SPAN
import com.android.systemui.qs.composefragment.SETTING_QS_FOLDER_SPAN
import com.android.systemui.qs.composefragment.SETTING_QS_MEDIA_SPAN
import com.android.systemui.qs.composefragment.SETTING_QS_SLIDERS_SPAN
import com.android.systemui.qs.pipeline.shared.TileSpec

const val PANEL_COLUMNS = 4

const val QQS_PANEL_ROWS = 2

sealed interface PanelBand {
    val rows: Int

    data class Tiles(val specs: List<TileSpec>, override val rows: Int) : PanelBand

    data class Half(
        val element: TileSpec,
        val filler: List<TileSpec>,
        val elementAtEnd: Boolean,
    ) : PanelBand {
        override val rows = 2
    }

    data class Pair(val first: TileSpec, val second: TileSpec) : PanelBand {
        override val rows = 2
    }

    data class Full(val element: TileSpec, override val rows: Int) : PanelBand
}

fun TileSpec.isPanelElement() = this in PANEL_ELEMENT_SPECS

fun ContentResolver.isFullWidth(spec: TileSpec): Boolean =
    when (spec) {
        FOLDER_SPEC -> Settings.Secure.getInt(this, SETTING_QS_FOLDER_SPAN, 1) >= 2
        MEDIA_SPEC -> Settings.Secure.getInt(this, SETTING_QS_MEDIA_SPAN, 1) >= 2
        SLIDERS_SPEC ->
            Settings.Secure.getInt(this, SETTING_QS_SLIDERS_SPAN, DEFAULT_SLIDERS_SPAN) >= 2
        else -> false
    }

fun ContentResolver.setFullWidth(spec: TileSpec, full: Boolean) {
    spec.panelSpanSetting()?.let { Settings.Secure.putInt(this, it, if (full) 2 else 1) }
}

fun fullWidthRows(spec: TileSpec): Int = if (spec == MEDIA_SPEC) 2 else 1

private fun TileSpec.indexSetting() =
    when (this) {
        FOLDER_SPEC -> "qs_connectivity_folder_edit_index"
        SLIDERS_SPEC -> "qs_sliders_edit_index"
        else -> "qs_media_edit_index"
    }

fun ContentResolver.panelIndex(spec: TileSpec): Int =
    Settings.Secure.getInt(
        this,
        spec.indexSetting(),
        when (spec) {
            MEDIA_SPEC -> 0
            FOLDER_SPEC -> 1
            else -> 2
        },
    )

fun ContentResolver.setPanelIndex(spec: TileSpec, index: Int) {
    Settings.Secure.putInt(this, spec.indexSetting(), index)
}

fun panelOrder(
    tiles: List<TileSpec>,
    elements: Collection<TileSpec>,
    allElements: Collection<TileSpec>,
    index: (TileSpec) -> Int,
): List<TileSpec> {
    val order = tiles.toMutableList()
    allElements.sortedBy(index).forEach { order.add(index(it).coerceIn(0, order.size), it) }
    return order.filter { !it.isPanelElement() || it in elements }
}

fun packPanel(
    order: List<TileSpec>,
    largeTiles: Set<TileSpec>,
    isFullWidth: (TileSpec) -> Boolean,
): List<PanelBand> {
    val half = PANEL_COLUMNS / 2
    fun width(spec: TileSpec) = if (spec in largeTiles) half else 1

    val bands = mutableListOf<PanelBand>()
    val run = mutableListOf<TileSpec>()
    var runRows = 0
    val row = mutableListOf<TileSpec>()
    var used = 0

    fun closeRow() {
        if (row.isEmpty()) return
        run += row
        runRows++
        row.clear()
        used = 0
    }
    fun closeRun() {
        closeRow()
        if (run.isEmpty()) return
        bands += PanelBand.Tiles(run.toList(), runRows)
        run.clear()
        runRows = 0
    }
    val placed = mutableSetOf<TileSpec>()

    fun filler(from: Int, start: List<TileSpec>): List<TileSpec> {
        val rows = List(2) { mutableListOf<TileSpec>() }
        fun space(r: Int) = half - rows[r].sumOf { width(it) }
        fun fit(spec: TileSpec): Boolean {
            val r = rows.indices.firstOrNull { space(it) >= width(spec) } ?: return false
            rows[r] += spec
            return true
        }
        start.forEach { fit(it) }
        var index = from
        while (index < order.size && rows.indices.any { space(it) > 0 }) {
            val spec = order[index]
            if (!spec.isPanelElement() && spec !in placed && rows.none { spec in it }) fit(spec)
            index++
        }
        val taken = rows.flatten()
        placed += taken
        return taken
    }

    var index = 0
    while (index < order.size) {
        val spec = order[index]
        if (spec in placed) {
            index++
            continue
        }
        if (!spec.isPanelElement()) {
            if (used + width(spec) > PANEL_COLUMNS) closeRow()
            row += spec
            used += width(spec)
            if (used >= PANEL_COLUMNS) closeRow()
            index++
            continue
        }
        if (isFullWidth(spec)) {
            closeRun()
            bands += PanelBand.Full(spec, fullWidthRows(spec))
            index++
            continue
        }
        if (used > half) closeRow()
        if (row.isEmpty()) {
            closeRun()
            val next = order.drop(index + 1).firstOrNull { it !in placed }
            if (next != null && next.isPanelElement() && !isFullWidth(next)) {
                bands += PanelBand.Pair(spec, next)
                placed += next
                index++
                continue
            }
            bands += PanelBand.Half(spec, filler(index + 1, emptyList()), elementAtEnd = false)
        } else {
            val start = row.toList()
            row.clear()
            used = 0
            closeRun()
            bands += PanelBand.Half(spec, filler(index + 1, start), elementAtEnd = true)
        }
        index++
    }
    closeRun()
    return bands
}

fun splitPanel(
    bands: List<PanelBand>,
    largeTiles: Set<TileSpec>,
    rows: Int = QQS_PANEL_ROWS,
): kotlin.Pair<List<PanelBand>, List<PanelBand>> {
    val head = mutableListOf<PanelBand>()
    var left = rows
    var index = 0
    while (index < bands.size && left > 0) {
        val band = bands[index]
        if (band is PanelBand.Tiles && band.rows > left) {
            val (taken, rest) = splitTiles(band.specs, largeTiles, left)
            head += PanelBand.Tiles(taken, left)
            return head to (listOf(PanelBand.Tiles(rest, band.rows - left)) + bands.drop(index + 1))
        }
        if (band.rows > left) break
        head += band
        left -= band.rows
        index++
    }
    return head to bands.drop(index)
}

private fun splitTiles(
    specs: List<TileSpec>,
    largeTiles: Set<TileSpec>,
    rows: Int,
): kotlin.Pair<List<TileSpec>, List<TileSpec>> {
    var row = 0
    var used = 0
    specs.forEachIndexed { index, spec ->
        val w = if (spec in largeTiles) PANEL_COLUMNS / 2 else 1
        if (used + w > PANEL_COLUMNS) {
            row++
            used = 0
        }
        if (row >= rows) return specs.take(index) to specs.drop(index)
        used += w
    }
    return specs to emptyList()
}

@Composable
fun PanelBands(
    bands: List<PanelBand>,
    gap: Dp,
    tiles: @Composable (specs: List<TileSpec>, columns: Int) -> Unit,
    element: @Composable (spec: TileSpec, half: Boolean) -> Unit,
) {
    bands.forEach { band ->
        when (band) {
            is PanelBand.Tiles -> tiles(band.specs, PANEL_COLUMNS)
            is PanelBand.Full -> Box(Modifier.fillMaxWidth()) { element(band.element, false) }
            is PanelBand.Pair ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = spacedBy(gap)) {
                    Box(Modifier.weight(1f)) { element(band.first, true) }
                    Box(Modifier.weight(1f)) { element(band.second, true) }
                }
            is PanelBand.Half ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = spacedBy(gap)) {
                    if (band.elementAtEnd) {
                        Box(Modifier.weight(1f)) { tiles(band.filler, PANEL_COLUMNS / 2) }
                        Box(Modifier.weight(1f)) { element(band.element, true) }
                    } else {
                        Box(Modifier.weight(1f)) { element(band.element, true) }
                        Box(Modifier.weight(1f)) { tiles(band.filler, PANEL_COLUMNS / 2) }
                    }
                }
        }
    }
}
