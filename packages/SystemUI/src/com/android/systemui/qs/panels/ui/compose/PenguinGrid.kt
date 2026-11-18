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

import com.android.systemui.qs.pipeline.shared.TileSpec
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

object PenguinGrid {
    const val COLUMNS = 4

    const val MAX_ROWS = 6

    const val SETTING = "qs_penguin_grid"

    val BRIGHTNESS_SPEC = TileSpec.create("penguin_brightness")
    val VOLUME_SPEC = TileSpec.create("penguin_volume")

    val ONEUI_TOGGLES_SPEC = TileSpec.create("penguin_oneui_toggles")

    val ONEUI_SOUND_SPEC = TileSpec.create("penguin_oneui_sound")

    val MODULE_SPECS = listOf(FOLDER_SPEC, MEDIA_SPEC, BRIGHTNESS_SPEC, VOLUME_SPEC)

    private val ONEUI_MODULE_SPECS =
        listOf(ONEUI_TOGGLES_SPEC, BRIGHTNESS_SPEC, VOLUME_SPEC, ONEUI_SOUND_SPEC, MEDIA_SPEC)

    enum class Flavor(val setting: String, val maxRows: Int) {
        ControlCentre(SETTING, MAX_ROWS),

        OneUi("qs_oneui_grid", Int.MAX_VALUE / 2);

        val moduleSpecs: List<TileSpec>
            get() = if (this == OneUi) ONEUI_MODULE_SPECS else MODULE_SPECS

        val placesEveryTile: Boolean
            get() = this == ControlCentre
    }

    fun isModule(spec: TileSpec) =
        spec in MODULE_SPECS || spec == ONEUI_TOGGLES_SPEC || spec == ONEUI_SOUND_SPEC

    data class Item(val spec: TileSpec, val x: Int, val y: Int, val w: Int, val h: Int) {
        val right: Int
            get() = x + w

        val bottom: Int
            get() = y + h

        fun overlaps(other: Item) =
            x < other.right && other.x < right && y < other.bottom && other.y < bottom

        fun contains(cellX: Int, cellY: Int) = cellX in x until right && cellY in y until bottom
    }

    fun sizes(spec: TileSpec, flavor: Flavor = Flavor.ControlCentre): List<Pair<Int, Int>> =
        if (flavor == Flavor.OneUi) oneUiSizes(spec) else controlCentreSizes(spec)

    private fun oneUiSizes(spec: TileSpec): List<Pair<Int, Int>> =
        when (spec) {
            ONEUI_TOGGLES_SPEC -> listOf(4 to 2, 4 to 3)
            BRIGHTNESS_SPEC,
            VOLUME_SPEC -> listOf(3 to 1, 4 to 1, 2 to 1, 1 to 2, 1 to 3)
            ONEUI_SOUND_SPEC -> listOf(1 to 1)
            MEDIA_SPEC -> listOf(4 to 2, 2 to 2, 4 to 4)
            else -> listOf(1 to 1, 2 to 1)
        }

    private fun controlCentreSizes(spec: TileSpec): List<Pair<Int, Int>> =
        when (spec) {
            MEDIA_SPEC -> listOf(2 to 2, 4 to 2, 4 to 4, 4 to 6)
            FOLDER_SPEC -> listOf(2 to 2, 4 to 4, 4 to 6)
            BRIGHTNESS_SPEC,
            VOLUME_SPEC -> listOf(1 to 2, 2 to 1, 4 to 1)
            else -> listOf(1 to 1, 2 to 1)
        }

    fun defaultSize(
        spec: TileSpec,
        largeTile: Boolean,
        flavor: Flavor = Flavor.ControlCentre,
    ): Pair<Int, Int> =
        if (isModule(spec)) sizes(spec, flavor).first()
        else if (largeTile) 2 to 1
        else 1 to 1

    fun rows(page: List<Item>) = page.maxOfOrNull { it.bottom } ?: 0

    const val WIDE_ROWS = 4

    fun foldWide(page: List<Item>): List<Item> {
        val split =
            (WIDE_ROWS downTo 1).firstOrNull { row -> page.none { it.y < row && it.bottom > row } }
                ?: return page.map {
                    if (it.y < WIDE_ROWS && it.bottom > WIDE_ROWS) it.copy(h = WIDE_ROWS - it.y)
                    else it
                }
        if (rows(page) <= split) return page
        return page.map { if (it.y < split) it else it.copy(x = it.x + COLUMNS, y = it.y - split) }
    }

    private fun stack(pages: List<List<Item>>): List<List<Item>> {
        if (pages.size < 2) return pages
        var top = 0
        val stacked = mutableListOf<Item>()
        for (page in pages) {
            stacked += page.map { it.copy(y = it.y + top) }
            top += rows(page)
        }
        return listOf(stacked)
    }

    /**
     * first.
     */
    fun resolve(
        pages: List<List<Item>>,
        modules: Set<TileSpec>,
        tiles: List<TileSpec>,
        largeTiles: Set<TileSpec>,
        preferredPage: Map<TileSpec, Int> = emptyMap(),
        flavor: Flavor = Flavor.ControlCentre,
    ): List<List<Item>> {
        val present = tiles.toSet()
        val seen = mutableSetOf<TileSpec>()
        val out =
            (if (flavor == Flavor.OneUi) stack(pages) else pages)
                .map { page ->
                    val placed = mutableListOf<Item>()
                    for (item in page) {
                        val module = isModule(item.spec)
                        if (module && (item.spec !in modules || placed.any { it.spec == item.spec })) {
                            continue
                        }
                        if (!module && (item.spec !in present || item.spec in seen)) continue
                        val (w, h) =
                            if (module) {
                                if (item.w to item.h in sizes(item.spec, flavor)) item.w to item.h
                                else sizes(item.spec, flavor).first()
                            } else {
                                defaultSize(item.spec, item.spec in largeTiles, flavor)
                            }
                        val fitted =
                            item.copy(
                                w = w,
                                h = h,
                                x = item.x.coerceIn(0, COLUMNS - w),
                                y = item.y.coerceAtLeast(0),
                            )
                        placed +=
                            if (placed.none { it.overlaps(fitted) }) fitted
                            else firstFree(placed, item.spec, w, h)
                        if (!module) seen += item.spec
                    }
                    placed
                }
                .toMutableList()
        if (out.isEmpty()) out.add(mutableListOf())
        for (spec in tiles) {
            if (!flavor.placesEveryTile) break
            if (spec in seen) continue
            val index = preferredPage[spec]?.takeIf { it in out.indices } ?: 0
            val (w, h) = defaultSize(spec, spec in largeTiles)
            out[index].add(firstFree(out[index], spec, w, h))
        }
        return paginate(
            out.filterIndexed { index, page -> index == 0 || page.isNotEmpty() }.map(::compact),
            flavor.maxRows,
        )
    }

    fun compact(page: List<Item>): List<Item> {
        val out = mutableListOf<Item>()
        for (item in page.sortedWith(compareBy({ it.y }, { it.x }))) {
            var risen = item
            while (risen.y > 0) {
                val up = risen.copy(y = risen.y - 1)
                if (out.any { it.overlaps(up) }) break
                risen = up
            }
            out.add(risen)
        }
        return out
    }

    fun paginate(pages: List<List<Item>>, maxRows: Int = MAX_ROWS): List<List<Item>> {
        val out = mutableListOf<List<Item>>()
        var carried = emptyList<Item>()
        for (page in pages) {
            carried = spill(page, out, maxRows)
            while (carried.isNotEmpty()) {
                val next = mutableListOf<Item>()
                for (item in carried) next.add(firstFree(next, item.spec, item.w, item.h))
                carried = spill(next, out, maxRows)
            }
        }
        return out
    }

    private fun spill(page: List<Item>, out: MutableList<List<Item>>, maxRows: Int): List<Item> {
        val (fits, over) = page.partition { it.bottom <= maxRows }
        out.add(fits)
        return over.sortedWith(compareBy({ it.y }, { it.x }))
    }

    fun defaults(
        modules: Set<TileSpec>,
        tiles: List<TileSpec>,
        largeTiles: Set<TileSpec>,
        flavor: Flavor = Flavor.ControlCentre,
    ): List<List<Item>> {
        if (flavor == Flavor.OneUi) return oneUiDefaults(modules, tiles, largeTiles)
        val first =
            listOf(
                    Item(FOLDER_SPEC, 0, 0, 2, 2),
                    Item(MEDIA_SPEC, 2, 0, 2, 2),
                    Item(BRIGHTNESS_SPEC, 2, 2, 1, 2),
                    Item(VOLUME_SPEC, 3, 2, 1, 2),
                )
                .filter { it.spec in modules }
        val pages =
            listOfNotNull(
                first,
                listOf(Item(MEDIA_SPEC, 0, 0, 4, 4)).takeIf { MEDIA_SPEC in modules },
                listOf(Item(FOLDER_SPEC, 0, 0, 4, 4)).takeIf { FOLDER_SPEC in modules },
            )
        return resolve(pages, modules, tiles, largeTiles)
    }

    private const val ONEUI_DEFAULT_PILLS = 4

    private fun oneUiDefaults(
        modules: Set<TileSpec>,
        tiles: List<TileSpec>,
        largeTiles: Set<TileSpec>,
    ): List<List<Item>> {
        val present = tiles.toSet()
        fun tile(vararg specs: String) =
            specs.map { TileSpec.create(it) }.firstOrNull { it in present }
        val wifi = tile("wifi", "internet")
        val bluetooth = tile("bt")
        val dark = tile("dark")
        val page =
            listOfNotNull(
                    wifi?.let { Item(it, 0, 0, 2, 1) },
                    bluetooth?.let { Item(it, 2, 0, 2, 1) },
                    Item(ONEUI_TOGGLES_SPEC, 0, 1, 4, 2),
                    Item(BRIGHTNESS_SPEC, 0, 3, 3, 1),
                    dark?.let { Item(it, 3, 3, 1, 1) },
                    Item(VOLUME_SPEC, 0, 4, 3, 1),
                    Item(ONEUI_SOUND_SPEC, 3, 4, 1, 1),
                    Item(MEDIA_SPEC, 0, 5, 4, 2),
                )
                .filter { it.spec in modules || !isModule(it.spec) }
                .toMutableList()
        val placed = page.map { it.spec }.toSet()
        for (spec in tiles.filter { it in largeTiles && it !in placed }.take(ONEUI_DEFAULT_PILLS)) {
            page += firstFree(page, spec, 2, 1)
        }
        return resolve(listOf(page), modules, tiles, largeTiles, flavor = Flavor.OneUi)
    }

    fun firstFree(
        placed: List<Item>,
        spec: TileSpec,
        w: Int,
        h: Int,
        fromY: Int = 0,
        fromX: Int = 0,
    ): Item {
        var y = fromY
        while (true) {
            val startX = if (y == fromY) fromX.coerceAtMost(COLUMNS - w) else 0
            for (x in startX..COLUMNS - w) {
                val candidate = Item(spec, x, y, w, h)
                if (placed.none { it.overlaps(candidate) }) return candidate
            }
            y++
        }
    }

    fun place(page: List<Item>, item: Item, x: Int, y: Int, w: Int = item.w, h: Int = item.h):
        List<Item> {
        val moved = Item(item.spec, x.coerceIn(0, COLUMNS - w), y.coerceAtLeast(0), w, h)
        val result = mutableListOf(moved)
        for (other in page.filter { it.spec != item.spec }.sortedWith(compareBy({ it.y }, { it.x }))) {
            result +=
                if (result.none { it.overlaps(other) }) other
                else firstFree(result, other.spec, other.w, other.h, other.y, other.x)
        }
        return result
    }

    fun parse(json: String?): List<List<Item>>? {
        if (json.isNullOrBlank()) return null
        return try {
            val trimmed = json.trim()
            val pages =
                if (trimmed.startsWith("[")) JSONArray().put(JSONArray(trimmed))
                else JSONObject(trimmed).getJSONArray("pages")
            (0 until pages.length()).map { index ->
                val page = pages.getJSONArray(index)
                (0 until page.length()).map { itemIndex ->
                    val item = page.getJSONObject(itemIndex)
                    Item(
                        spec = TileSpec.create(item.getString("s")),
                        x = item.getInt("x"),
                        y = item.getInt("y"),
                        w = item.getInt("w"),
                        h = item.getInt("h"),
                    )
                }
            }
        } catch (e: JSONException) {
            null
        }
    }

    fun serialize(pages: List<List<Item>>): String =
        JSONObject()
            .put(
                "pages",
                JSONArray(
                    pages.map { page ->
                        JSONArray(
                            page.map { item ->
                                JSONObject()
                                    .put("s", item.spec.spec)
                                    .put("x", item.x)
                                    .put("y", item.y)
                                    .put("w", item.w)
                                    .put("h", item.h)
                            }
                        )
                    }
                ),
            )
            .toString()
}
