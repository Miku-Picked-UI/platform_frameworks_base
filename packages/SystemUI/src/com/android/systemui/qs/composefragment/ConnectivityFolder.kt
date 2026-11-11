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
import com.android.systemui.qs.ui.composable.handlesToggleClick
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.content.ContentResolver
import android.provider.Settings
import android.service.quicksettings.Tile
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Arrangement.spacedBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import com.android.compose.theme.LocalAndroidColorScheme
import com.android.systemui.common.shared.model.Icon
import com.android.compose.modifiers.thenIf
import com.android.systemui.common.ui.compose.Icon
import com.android.systemui.plugins.qs.QSTile
import com.android.systemui.qs.flags.QsDetailedView
import com.android.systemui.qs.panels.ui.compose.TileListener
import com.android.systemui.qs.panels.ui.viewmodel.DetailsViewModel
import com.android.systemui.qs.panels.ui.viewmodel.IconProvider
import com.android.systemui.qs.panels.ui.viewmodel.TileUiState
import com.android.systemui.qs.panels.ui.viewmodel.TileViewModel
import com.android.systemui.qs.panels.ui.viewmodel.toIconProvider
import com.android.systemui.qs.panels.ui.viewmodel.toUiState
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.shared.style.LiquidGlassControl
import com.android.systemui.qs.shared.style.LiquidGlassGlyphs
import com.android.systemui.qs.shared.style.LiquidGlassSurface
import com.android.systemui.qs.shared.style.QsPanelStyle
import com.android.systemui.qs.shared.style.glassPress
import com.android.systemui.qs.shared.style.glassRim
import com.android.systemui.qs.shared.style.isStockQsStyle
import com.android.systemui.qs.shared.style.liquidGlassActive
import com.android.systemui.qs.shared.style.liquidGlassCos
import com.android.systemui.qs.shared.style.liquidGlassOn
import com.android.systemui.qs.tileimpl.QSTileImpl
import com.android.systemui.res.R

object ConnectivityFolderSpecs {
    val Large = listOf("airplane", "cell", "wifi")
    val Small = listOf("cast", "bt", "hotspot", "dnd")
    val ExpandedCards = listOf("wifi", "bt", "cell", "cast")
}

const val SETTING_QS_FOLDER_USAGE = "qs_connectivity_folder_usage"

private fun parseUsage(raw: String?): Map<String, Int> =
    raw
        ?.split(',')
        ?.mapNotNull {
            val parts = it.split(':')
            if (parts.size == 2 && parts[0].isNotEmpty()) {
                parts[0] to (parts[1].toIntOrNull() ?: 0)
            } else {
                null
            }
        }
        ?.toMap() ?: emptyMap()

private fun ContentResolver.recordFolderUse(spec: String) {
    val usage = parseUsage(Settings.Secure.getString(this, SETTING_QS_FOLDER_USAGE)).toMutableMap()
    usage[spec] = (usage[spec] ?: 0) + 1
    Settings.Secure.putString(
        this,
        SETTING_QS_FOLDER_USAGE,
        usage.entries.joinToString(",") { "${it.key}:${it.value}" },
    )
}

private fun promoteByUsage(
    large: List<String>,
    small: List<String>,
    usage: Map<String, Int>,
): Pair<List<String>, List<String>> {
    if (usage.isEmpty() || large.isEmpty() || small.isEmpty()) return large to small
    val all = large + small
    val promoted =
        all.sortedByDescending { (usage[it] ?: 0) + if (it in large) 1 else 0 }
            .take(large.size)
            .toSet()
    return all.filter { it in promoted } to all.filterNot { it in promoted }
}

const val SETTING_QS_FOLDER_LARGE = "qs_connectivity_folder_large"
const val SETTING_QS_FOLDER_SMALL = "qs_connectivity_folder_small"

const val SETTING_QS_FOLDER_SPAN = "qs_connectivity_folder_span"
const val SETTING_QS_FOLDER_POSITION = "qs_connectivity_folder_position"
const val SETTING_QS_MEDIA_POSITION = "qs_media_position"

object ConnectivityFolderExpansion {
    var expanded by mutableStateOf(false)
}

@Composable
fun qsModuleHeight(rows: Int): Dp {
    val tile = dimensionResource(id = R.dimen.common_tile_default_tile_height)
    val gap = dimensionResource(id = R.dimen.qs_tile_margin_vertical)
    return tile * rows + gap * (rows - 1)
}

const val POSITION_HEADER = -1
const val POSITION_ABOVE_GRID = 0
const val POSITION_BELOW_GRID = 1
const val SETTING_QS_MEDIA_SPAN = "qs_media_span"
const val SETTING_QS_SLIDERS_POSITION = "qs_sliders_position"
const val SETTING_QS_SLIDERS_SPAN = "qs_sliders_span"

const val DEFAULT_SLIDERS_SPAN = 2
const val SETTING_QS_MEDIA_STYLE = "qs_media_style"

@Composable
fun secureIntSetting(key: String, default: Int): Int {
    val resolver = LocalContext.current.contentResolver
    var value by remember(key) { mutableStateOf(Settings.Secure.getInt(resolver, key, default)) }
    DisposableEffect(resolver, key) {
        val observer =
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    value = Settings.Secure.getInt(resolver, key, default)
                }
            }
        resolver.registerContentObserver(Settings.Secure.getUriFor(key), false, observer)
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return value
}

@Composable
fun effectiveQsPanelStyle(): QsPanelStyle =
    QsPanelStyle.effective(
        secureIntSetting(QsPanelStyle.SETTING_NAME, QsPanelStyle.Penguin.value),
        secureIntSetting(QsPanelStyle.ONE_UI_SETTING_NAME, 0) != 0,
    )

@Composable
fun connectivityFolderSpecs(): List<String> {
    val (large, small) = folderSpecs()
    return large + small
}

private fun readSpecs(
    resolver: ContentResolver,
    key: String,
    fallback: List<String>,
): List<String> {
    val raw = Settings.Secure.getString(resolver, key) ?: return fallback
    val parsed = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    return parsed.ifEmpty { fallback }
}

private fun current(resolver: ContentResolver): Pair<List<String>, List<String>> =
    promoteByUsage(
        readSpecs(resolver, SETTING_QS_FOLDER_LARGE, ConnectivityFolderSpecs.Large),
        readSpecs(resolver, SETTING_QS_FOLDER_SMALL, ConnectivityFolderSpecs.Small),
        parseUsage(Settings.Secure.getString(resolver, SETTING_QS_FOLDER_USAGE)),
    )

@Composable
private fun folderSpecs(): Pair<List<String>, List<String>> {
    val resolver = LocalContext.current.contentResolver
    var specs by remember {
        mutableStateOf(current(resolver))
    }
    DisposableEffect(resolver) {
        val observer =
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    specs = current(resolver)
                }
            }
        listOf(SETTING_QS_FOLDER_LARGE, SETTING_QS_FOLDER_SMALL, SETTING_QS_FOLDER_USAGE).forEach {
            resolver.registerContentObserver(Settings.Secure.getUriFor(it), false, observer)
        }
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return specs
}

private val LargeCircle = 56.dp
private val HeroCircle = 64.dp
private val SmallCircle = 30.dp
private val CellSize = 72.dp
private val BigCardHeight = 148.dp
private val GlassCardHeight = 168.dp
private const val GlassSurfaceAlpha = 0.45f
private val CardPadding = 10.dp
private val CellSpacing = 6.dp

private val LocalFolderInteractive = compositionLocalOf { true }

@Composable
private fun Modifier.folderClickable(
    onClick: () -> Unit,
    onLongClick: () -> Unit = onClick,
): Modifier =
    if (LocalFolderInteractive.current) {
        combinedClickable(onClick = onClick, onLongClick = onLongClick)
    } else {
        this
    }

@Composable
private fun Modifier.folderTileClickable(tile: TileViewModel, uiState: TileUiState): Modifier {
    val resolver = LocalContext.current.contentResolver
    val details = LocalQsTileDetails.current
    return folderClickable(
        onClick = {
            resolver.recordFolderUse(tile.spec.spec)
            tile.primaryAction(uiState, details)
        },
        onLongClick = { tile.detailsOrSettings(uiState, details) },
    )
}

@Composable
fun ConnectivityFolder(
    tiles: List<TileViewModel>,
    modifier: Modifier = Modifier,
    interactive: Boolean = true,
    compactHeight: Dp? = null,
    expanded: Boolean = false,
    onExpandedChange: (Boolean) -> Unit = {},
    showHeader: Boolean = true,
    carded: Boolean = true,
) {
    CompositionLocalProvider(LocalFolderInteractive provides interactive) {
        ConnectivityFolderContent(
            tiles,
            modifier,
            compactHeight,
            expanded,
            onExpandedChange,
            showHeader,
            carded,
        )
    }
}

@Composable
private fun ConnectivityFolderContent(
    tiles: List<TileViewModel>,
    modifier: Modifier,
    compactHeight: Dp?,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    showHeader: Boolean,
    carded: Boolean,
) {
    val bySpec = remember(tiles) { tiles.associateBy { it.spec.spec } }
    val (largeSpecs, smallSpecs) = folderSpecs()
    val (large, small) =
        remember(bySpec, largeSpecs, smallSpecs) {
            val large = largeSpecs.mapNotNull { bySpec[it] }.toMutableList()
            val small = smallSpecs.mapNotNull { bySpec[it] }.toMutableList()
            // A device without one of the large tiles (a tablet has no mobile data) would leave
            // its big slot empty and crowd the rest into the small cluster. Fill the slot from
            // the small tiles instead, Bluetooth first.
            while (large.size < largeSpecs.size && small.isNotEmpty()) {
                val next = small.firstOrNull { it.spec.spec == "bt" } ?: small.first()
                small.remove(next)
                large.add(next)
            }
            large.toList() to small.toList()
        }

    if (large.isEmpty() && small.isEmpty()) return

    var listening by remember { mutableStateOf(false) }
    LifecycleStartEffect(Unit) {
        listening = true
        onStopOrDispose { listening = false }
    }
    TileListener(large + small) { listening }

    val gap = dimensionResource(id = R.dimen.qs_tile_margin_vertical)

    if (expanded) {
        ExpandedSheet(
            large = large,
            small = small,
            gap = gap,
            uniformGrid = secureIntSetting(SETTING_QS_FOLDER_SPAN, 1) >= 2,
            onDone = { onExpandedChange(false) }.takeIf { showHeader },
            fitHeight = !showHeader && carded,
            modifier = modifier,
        )
        return
    }

    val cell = compactHeight?.let { (it - CardPadding * 2 - CellSpacing) / 2 } ?: CellSize

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .thenIf(compactHeight != null) { Modifier.height(compactHeight!!) }
                .clip(RoundedCornerShape(28.dp))
                .background(glassSurface())
                .glassRim(RoundedCornerShape(28.dp))
                .padding(CardPadding),
        verticalArrangement = spacedBy(CellSpacing),
    ) {
        if (compactHeight == null) {
            Row(horizontalArrangement = spacedBy(CellSpacing), modifier = Modifier.fillMaxWidth()) {
                large.forEach { tile ->
                    Cell(cell) { size -> FolderCircle(tile, size * 0.88f) }
                }
                if (small.isNotEmpty()) {
                    Cell(cell) { size ->
                        SmallCluster(small, size * 0.34f, onClick = { onExpandedChange(true) })
                    }
                }
            }
        } else {
            Row(
                horizontalArrangement = spacedBy(CellSpacing),
                modifier = Modifier.fillMaxWidth().weight(1f),
            ) {
                Cell(null) { size -> large.getOrNull(0)?.let { FolderCircle(it, size * 0.82f) } }
                Cell(null) { size -> large.getOrNull(1)?.let { FolderCircle(it, size * 0.82f) } }
            }
            Row(
                horizontalArrangement = spacedBy(CellSpacing),
                modifier = Modifier.fillMaxWidth().weight(1f),
            ) {
                Cell(null) { size -> large.getOrNull(2)?.let { FolderCircle(it, size * 0.90f) } }
                Cell(null) { size ->
                    if (small.isNotEmpty()) {
                        SmallCluster(small, size * 0.34f, onClick = { onExpandedChange(true) })
                    }
                }
            }
        }
    }
}

@Composable
private fun ExpandedSheet(
    large: List<TileViewModel>,
    small: List<TileViewModel>,
    gap: Dp,
    uniformGrid: Boolean,
    onDone: (() -> Unit)?,
    modifier: Modifier = Modifier,
    fitHeight: Boolean = false,
) {
    val all = large + small
    val cards =
        if (uniformGrid || fitHeight) all
        else if (liquidGlassOn) {
            val order = listOf("wifi", "cast", "cell", "bt")
            all.filter { it.spec.spec in order }.sortedBy { order.indexOf(it.spec.spec) }
        } else all.filter { it.spec.spec in ConnectivityFolderSpecs.ExpandedCards }
    val rows = all.filterNot { it in cards }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = spacedBy(gap)) {
        if (onDone != null) Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.quick_settings_connectivity_folder_title),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.quick_settings_done),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge,
                modifier =
                    Modifier.clip(RoundedCornerShape(12.dp))
                        .folderClickable(onClick = onDone),
            )
        }
        rows.take(1).forEach { FolderRow(it) }
        cards.chunked(2).forEach { pair ->
            Row(
                modifier =
                    Modifier.fillMaxWidth().thenIf(fitHeight) { Modifier.weight(1f) },
                horizontalArrangement = spacedBy(gap),
            ) {
                pair.forEach { tile ->
                    Box(Modifier.weight(1f)) { FolderBigCard(tile, fill = fitHeight) }
                }
                if (pair.size == 1) Box(Modifier.weight(1f))
            }
        }
        rows.drop(1).forEach { FolderRow(it) }
    }
}

@Composable
private fun FolderBigCard(tile: TileViewModel, fill: Boolean = false) {
    val (uiState, icon) = rememberTileState(tile)
    val active = uiState.visualState == Tile.STATE_ACTIVE
    val glass = liquidGlassOn
    val shape = RoundedCornerShape(if (glass) 36.dp else 26.dp)
    val disc = if (glass) 50.dp else 44.dp
    val modifier =
        Modifier.fillMaxWidth()
            .then(
                if (fill) Modifier.fillMaxHeight()
                else Modifier.height(if (glass) GlassCardHeight else BigCardHeight)
            )
            .glassPress()
            .clip(shape)
            .background(glassSurface())
            .glassRim(shape)
            .folderTileClickable(tile, uiState)
    val badge: @Composable () -> Unit = {
        Box(
            modifier = Modifier.size(disc).clip(CircleShape).background(folderBackground(active, tile.spec.spec)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon = icon, tint = folderForeground(active, tile.spec.spec), modifier = Modifier.size(disc / 2))
        }
    }
    if (fill) {
        BoxWithConstraints(modifier) {
            if (maxHeight < 120.dp) {
                Row(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                    horizontalArrangement = spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    badge()
                    FolderLabels(uiState, active)
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxSize().padding(18.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    badge()
                    FolderLabels(uiState, active)
                }
            }
        }
        return
    }
    Column(
        modifier = modifier.padding(if (glass) 18.dp else 16.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        badge()
        FolderLabels(uiState, active)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun FolderLabels(
    uiState: TileUiState,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    val glass = liquidGlassOn
    val secondary =
        uiState.secondaryLabel.ifBlank {
            if (glass) stringResource(if (active) R.string.switch_bar_on else R.string.switch_bar_off)
            else ""
        }
    Column(modifier = modifier) {
        Text(
            text = uiState.label,
            color = if (glass) Color.White else MaterialTheme.colorScheme.onSurface,
            style =
                if (glass) MaterialTheme.typography.titleSmallEmphasized
                else MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (secondary.isNotBlank()) {
            Text(
                text = secondary,
                color =
                    if (glass) Color.White.copy(alpha = 0.6f)
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                style =
                    if (glass) MaterialTheme.typography.labelMedium
                    else MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun RowScope.Cell(height: Dp?, content: @Composable (Dp) -> Unit) {
    BoxWithConstraints(
        modifier =
            Modifier.weight(1f)
                .then(if (height != null) Modifier.height(height) else Modifier.fillMaxHeight()),
        contentAlignment = Alignment.Center,
    ) {
        content(minOf(maxWidth, maxHeight))
    }
}

@Composable
private fun FolderCircle(tile: TileViewModel, diameter: Dp) {
    val (uiState, icon) = rememberTileState(tile)
    val active = uiState.visualState == Tile.STATE_ACTIVE
    Box(
        modifier =
            Modifier.size(diameter)
                .clip(CircleShape)
                .background(folderBackground(active, tile.spec.spec))
                .folderTileClickable(tile, uiState),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon = icon, tint = folderForeground(active, tile.spec.spec), modifier = Modifier.size(diameter / 2))
    }
}

@Composable
private fun SmallCluster(tiles: List<TileViewModel>, dot: Dp, onClick: () -> Unit) {
    Box(
        modifier =
            Modifier.clip(RoundedCornerShape(20.dp))
                .folderClickable(onClick = onClick)
                .padding(4.dp)
    ) {
        Column(
            verticalArrangement = spacedBy(5.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(horizontalArrangement = spacedBy(5.dp)) {
                tiles.getOrNull(0)?.let { SmallDot(it, dot) }
                tiles.getOrNull(1)?.let { SmallDot(it, dot) }
            }
            Row(horizontalArrangement = spacedBy(5.dp)) {
                tiles.getOrNull(2)?.let { SmallDot(it, dot) }
                tiles.getOrNull(3)?.let { SmallDot(it, dot) }
            }
        }
    }
}

@Composable
private fun SmallDot(tile: TileViewModel, diameter: Dp) {
    val (uiState, icon) = rememberTileState(tile)
    val active = uiState.visualState == Tile.STATE_ACTIVE
    Box(
        modifier = Modifier.size(diameter).clip(CircleShape).background(folderBackground(active, tile.spec.spec)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon = icon, tint = folderForeground(active, tile.spec.spec), modifier = Modifier.size(diameter / 2))
    }
}

@Composable
private fun FolderRow(tile: TileViewModel) {
    val (uiState, icon) = rememberTileState(tile)
    val active = uiState.visualState == Tile.STATE_ACTIVE
    val glass = liquidGlassOn
    val shape = if (glass) RoundedCornerShape(50) else RoundedCornerShape(26.dp)
    val disc = if (glass) 50.dp else 44.dp
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .glassPress()
                .clip(shape)
                .background(glassSurface())
                .glassRim(shape)
                .folderTileClickable(tile, uiState)
                .padding(horizontal = if (glass) 12.dp else 16.dp, vertical = if (glass) 12.dp else 14.dp),
        horizontalArrangement = spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier.size(disc).clip(CircleShape).background(folderBackground(active, tile.spec.spec)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon = icon, tint = folderForeground(active, tile.spec.spec), modifier = Modifier.size(disc / 2))
        }
        FolderLabels(uiState, active, Modifier.fillMaxWidth())
    }
}

@Composable
internal fun glassSurface(): Color =
    if (liquidGlassCos && !isStockQsStyle) Color.Transparent
    else if (liquidGlassOn && !isStockQsStyle) LiquidGlassSurface
    else
        LocalAndroidColorScheme.current.surfaceEffect1.copy(
            alpha = if (isStockQsStyle) 1f else GlassSurfaceAlpha
        )

@Composable
private fun folderBackground(active: Boolean, spec: String): Color =
    if (active && liquidGlassOn) liquidGlassActive(spec)
    else if (active) MaterialTheme.colorScheme.primary
    else if (liquidGlassOn) LiquidGlassControl
    else glassSurface()

@Composable
private fun folderForeground(active: Boolean, spec: String): Color =
    if (liquidGlassOn) Color.White
    else if (active) MaterialTheme.colorScheme.onPrimary
    else MaterialTheme.colorScheme.onSurfaceVariant

@Composable
internal fun rememberTileState(tile: TileViewModel): Pair<TileUiState, Icon> {
    val context = LocalContext.current
    val resources = context.resources
    val state by
        produceState(
            tile.currentState.let { it.toUiState(resources) to it.toIconProvider() },
            tile,
            resources,
        ) {
            tile.state.collect { value = it.toUiState(resources) to it.toIconProvider() }
        }
    val icon = context.folderIcon(state.second)
    return state.first to if (liquidGlassOn) LiquidGlassGlyphs.swap(tile.spec.spec, icon) else icon
}

internal fun Context.folderIcon(icon: IconProvider): Icon {
    return icon.icon?.let {
        if (it is QSTileImpl.ResourceIcon) {
            Icon.Resource(it.resId, null)
        } else {
            Icon.Loaded(it.getDrawable(this), null)
        }
    } ?: Icon.Resource(R.drawable.ic_error_outline, null)
}

const val SETTING_QS_CONNECTIVITY_FOLDER = "qs_connectivity_folder"

@Composable
fun connectivityFolderEnabled(): Boolean {
    val resolver = LocalContext.current.contentResolver
    var enabled by remember {
        mutableStateOf(
            Settings.Secure.getInt(resolver, SETTING_QS_CONNECTIVITY_FOLDER, 1) != 0
        )
    }
    DisposableEffect(resolver) {
        val observer =
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    enabled = Settings.Secure.getInt(resolver, SETTING_QS_CONNECTIVITY_FOLDER, 1) != 0
                }
            }
        resolver.registerContentObserver(
            Settings.Secure.getUriFor(SETTING_QS_CONNECTIVITY_FOLDER),
            false,
            observer,
        )
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return enabled
}

/** Lets the styled panels open a tile's details (Wi-Fi networks, Bluetooth devices...). */
val LocalQsTileDetails = compositionLocalOf<DetailsViewModel?> { null }

private fun TileViewModel.showDetails(details: DetailsViewModel?): Boolean =
    QsDetailedView.isEnabled && details?.onTileClicked(spec) == true

/** Toggles a toggle tile; any other tile opens its details, or does its own click without. */
internal fun TileViewModel.primaryAction(uiState: TileUiState, details: DetailsViewModel? = null) {
    if (uiState.handlesToggleClick) toggleClick() else if (!showDetails(details)) mainClick(null)
}

/**
 * The stock small tile long-press: a dual target tile (Wi-Fi, Bluetooth, torch level...) opens its
 * details or dialog, any other tile its settings page.
 */
internal fun TileViewModel.detailsOrSettings(uiState: TileUiState, details: DetailsViewModel?) {
    if (showDetails(details)) return
    if (uiState.handlesToggleClick) mainClick(null) else settingsClick(null)
}

/** Opens a dual target tile's details or dialog; any other tile does its primary action. */
internal fun TileViewModel.detailsOrPrimary(uiState: TileUiState, details: DetailsViewModel?) {
    if (showDetails(details)) return
    if (uiState.handlesToggleClick) mainClick(null) else primaryAction(uiState)
}

/** Opens the tile's details, or does its own click when it has none. */
internal fun TileViewModel.detailsOrMain(details: DetailsViewModel?) {
    if (!showDetails(details)) mainClick(null)
}
