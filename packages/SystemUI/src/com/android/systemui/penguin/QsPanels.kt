/*
 * Copyright (C) 2026 The PenguinOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.penguin

import android.content.Context
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import com.android.compose.animation.scene.ContentScope
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.qs.panels.shared.model.SizedTile
import com.android.systemui.qs.panels.ui.compose.GridLayout
import com.android.systemui.qs.panels.ui.compose.infinitegrid.TileColors
import com.android.systemui.qs.panels.ui.model.GridCell
import com.android.systemui.qs.panels.ui.viewmodel.EditModeViewModel
import com.android.systemui.qs.panels.ui.viewmodel.EditTileViewModel
import com.android.systemui.qs.panels.ui.viewmodel.TileUiState
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.panels.ui.viewmodel.toolbar.ToolbarViewModel
import com.android.systemui.qs.ui.viewmodel.QuickSettingsContainerViewModel
import com.android.systemui.shade.ui.viewmodel.ShadeSceneContentViewModel

interface QsPanels {
    @Composable
    fun ContentScope.Content(
        viewModel: QuickSettingsContainerViewModel,
        mediaInRow: Boolean,
        modifier: Modifier,
        mediaSquishiness: () -> Float,
    )

    @Composable
    fun ContentScope.ShadeHeader(
        viewModel: ShadeSceneContentViewModel,
        mediaInRow: Boolean,
        horizontalPadding: Dp,
        visualOffset: Density.() -> Int,
    )

    @Composable
    fun ContentScope.OverlayBody(
        viewModel: QuickSettingsContainerViewModel,
        buildNumber: @Composable ColumnScope.() -> Unit,
    )

    @Composable fun ContentScope.OverlayHost(content: @Composable () -> Unit)

    @Composable
    fun ContentScope.OverlayTop(
        viewModel: QuickSettingsContainerViewModel,
        toolbarViewModel: ToolbarViewModel,
    ): Boolean

    @Composable fun showsOverlayStatusBar(viewModel: QuickSettingsContainerViewModel): Boolean

    @Composable fun wideOverlay(viewModel: QuickSettingsContainerViewModel): Boolean

    /** Whether the Quick Settings shade should draw AOSP's own toolbar and body. */
    @Composable
    fun stockOverlay(viewModel: QuickSettingsContainerViewModel): Boolean = false

    @Composable fun FooterAction(viewModel: QuickSettingsContainerViewModel) {}

    @Composable
    fun ContentScope.OverlayEditor(viewModel: QuickSettingsContainerViewModel, modifier: Modifier): Boolean

    @Composable fun ContentScope.Host(content: @Composable () -> Unit)

    val editMode: QsEditMode

    @Composable fun tileStyle(): QsTileStyle?

    fun onScrimClicked(): Boolean = false

    @Composable fun cosPanel(viewModel: QuickSettingsContainerViewModel): Boolean = false
}

interface QsEditMode {
    @Composable
    fun Takeover(
        viewModel: EditModeViewModel,
        tiles: List<EditTileViewModel>,
        gridLayout: GridLayout,
        modifier: Modifier,
    ): Boolean

    @Composable fun tiles(tiles: List<EditTileViewModel>): List<EditTileViewModel>

    fun remove(context: Context, spec: TileSpec, removeTile: (TileSpec) -> Unit)

    fun commit(context: Context, specs: List<TileSpec>, setTiles: (List<TileSpec>) -> Unit)

    val elementSpecs: Set<TileSpec>

    @Composable fun fullWidthSpecs(): Set<TileSpec>

    fun resize(context: Context, spec: TileSpec, toIcon: Boolean): Boolean

    @Composable fun preview(spec: TileSpec): (@Composable () -> Unit)?

    @Composable fun previewHeight(spec: TileSpec): Dp?

    @Composable fun HeaderPreview()

    fun rows(
        tiles: List<SizedTile<EditTileViewModel>>,
        columns: Int,
        startingRow: Int,
    ): List<GridCell>
}

interface QsTileStyle {
    @Composable fun colors(uiState: TileUiState, iconOnly: Boolean, stock: TileColors): TileColors

    @Composable fun cornerRadius(uiState: TileUiState, stock: Dp): Dp

    val iconHalo: Boolean

    @Composable fun startPadding(): Dp

    val roundIconTiles: Boolean

    fun icon(spec: String, icon: Icon): Icon

    fun roundIconSize(tileHeight: Dp): Dp

    @Composable fun Container(shape: Shape, content: @Composable () -> Unit)

    @Composable fun Modifier.containerBehaviour(): Modifier

    @Composable fun colorSpec(): AnimationSpec<Color>

    val editToggleInset: Dp
}

data class QsPanelsHost(val panels: QsPanels, val container: QuickSettingsContainerViewModel)

val LocalQsPanels = staticCompositionLocalOf<QsPanelsHost?> { null }

@Composable
fun ContentScope.QsPanelsHostScope(
    container: QuickSettingsContainerViewModel,
    content: @Composable () -> Unit,
) {
    val panels = container.penguin
    if (panels == null) {
        content()
        return
    }
    CompositionLocalProvider(LocalQsPanels provides QsPanelsHost(panels, container)) {
        with(panels) { Host(content) }
    }
}
