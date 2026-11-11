/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.systemui.qs.ui.composable

import androidx.compose.animation.core.Animatable
import com.android.systemui.penguin.CosFeature
import com.android.systemui.penguin.CosShadeSwitch
import com.android.systemui.penguin.rememberCosFeature
import com.android.systemui.qs.shared.style.LocalGlassBlurAlpha
import com.android.systemui.qs.shared.style.liquidGlassCos
import com.android.systemui.qs.shared.style.panelStyle
import androidx.compose.foundation.layout.Arrangement.spacedBy
import com.android.systemui.qs.composefragment.ColorOsGap
import com.android.systemui.qs.composefragment.ColorOsMediaFrame
import com.android.systemui.qs.composefragment.ColorOsTileGrid
import com.android.systemui.qs.composefragment.ColorOsTopRows
import com.android.systemui.qs.composefragment.colorOsCardSpecs
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.Stable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.snap
import com.android.compose.animation.scene.content.state.TransitionState
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.min
import androidx.lifecycle.compose.LifecycleStartEffect
import com.android.compose.animation.scene.ContentScope
import com.android.compose.gesture.gesturesDisabled
import com.android.compose.modifiers.thenIf
import com.android.systemui.brightness.ui.compose.BrightnessSliderContainer
import com.android.systemui.brightness.ui.compose.ContainerColors
import com.android.systemui.compose.modifiers.sysuiResTag
import com.android.systemui.media.remedia.ui.compose.Media
import com.android.systemui.media.remedia.ui.compose.MediaPresentationStyle
import com.android.systemui.qs.composefragment.BrightnessLayout
import com.android.systemui.qs.composefragment.ConnectivityFolder
import com.android.systemui.qs.composefragment.connectivityFolderEnabled
import com.android.systemui.qs.composefragment.connectivityFolderSpecs
import com.android.systemui.qs.composefragment.POSITION_ABOVE_GRID
import com.android.systemui.qs.composefragment.POSITION_BELOW_GRID
import com.android.systemui.qs.composefragment.POSITION_HEADER
import com.android.systemui.qs.composefragment.SETTING_QS_FOLDER_POSITION
import com.android.systemui.qs.composefragment.ConnectivityFolderExpansion
import com.android.systemui.qs.composefragment.harmonyCardSpecs
import com.android.systemui.qs.composefragment.HarmonyTopRow
import com.android.systemui.qs.composefragment.HarmonyTogglesCard
import com.android.systemui.qs.composefragment.HarmonyTitle
import com.android.systemui.qs.composefragment.HarmonyGap
import com.android.systemui.qs.composefragment.HarmonyConnectedDevices
import com.android.systemui.qs.composefragment.HarmonyCastCard
import com.android.systemui.qs.composefragment.SETTING_QS_FOLDER_SPAN
import com.android.systemui.qs.composefragment.SETTING_QS_MEDIA_POSITION
import com.android.systemui.qs.panels.ui.viewmodel.TileViewModel
import com.android.systemui.lifecycle.rememberViewModel
import com.android.systemui.qs.panels.ui.compose.toolbar.EditModeButton
import com.android.systemui.qs.composefragment.MyUiCardSpecs
import com.android.systemui.qs.composefragment.LocalMyUiInteractive
import com.android.systemui.qs.composefragment.MyUiGridGap
import com.android.systemui.qs.composefragment.MyUiTileAspect
import com.android.systemui.qs.composefragment.MyUiConnectivityCard
import com.android.systemui.qs.composefragment.MyUiMediaCard
import com.android.systemui.qs.composefragment.MyUiTileGrid
import com.android.systemui.qs.composefragment.OneUiQuickSettingsPanel
import com.android.systemui.qs.composefragment.PenguinMediaCard
import com.android.systemui.qs.panels.ui.compose.FOLDER_SPEC
import com.android.systemui.qs.panels.ui.compose.PANEL_FILLER_COLUMNS
import com.android.systemui.qs.panels.ui.compose.panelFillerTiles
import com.android.systemui.qs.panels.ui.compose.MEDIA_SPEC
import com.android.systemui.qs.panels.ui.compose.SLIDERS_SPEC
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.composefragment.SETTING_QS_MEDIA_STYLE
import com.android.systemui.qs.composefragment.SETTING_QS_SLIDERS_POSITION
import com.android.systemui.qs.composefragment.DEFAULT_SLIDERS_SPAN
import com.android.systemui.qs.composefragment.SETTING_QS_SLIDERS_SPAN
import com.android.systemui.qs.composefragment.SETTING_QS_MEDIA_SPAN
import com.android.systemui.qs.composefragment.qsModuleHeight
import com.android.systemui.qs.composefragment.effectiveQsPanelStyle
import com.android.systemui.qs.composefragment.secureIntSetting
import com.android.systemui.qs.composefragment.VolumeLayout
import com.android.systemui.qs.composefragment.ui.GridAnchor
import com.android.systemui.qs.panels.ui.compose.TileGrid
import com.android.systemui.qs.shared.style.LocalQsPanelStyle
import com.android.systemui.qs.shared.style.QsPanelStyle
import com.android.systemui.qs.shared.style.LiquidGlassBrightness
import com.android.systemui.qs.shared.style.LiquidGlassSurface
import com.android.systemui.qs.shared.style.glassRim
import com.android.systemui.qs.shared.style.liquidGlassOn
import com.android.systemui.qs.shared.ui.QuickSettings.Elements
import com.android.systemui.qs.ui.viewmodel.QuickSettingsContainerViewModel
import com.android.systemui.qs.panels.ui.compose.PanelBand
import com.android.systemui.qs.panels.ui.compose.PanelBands
import com.android.systemui.qs.panels.ui.compose.packPanel
import com.android.systemui.qs.panels.ui.compose.panelOrder
import com.android.systemui.qs.panels.ui.compose.splitPanel
import com.android.systemui.res.R
import com.android.systemui.scene.shared.model.Overlays
import kotlinx.coroutines.flow.filterNotNull

/**
 * How the content hosting Quick Settings is moving. One instance lives per content and its values
 * are state, so a change only recomposes what reads it, not everything under the provider.
 */
@Stable
internal class QsHostTransition(
    idle: () -> Boolean,
    leaving: () -> Boolean,
    /** How far the host has gone while leaving, from 0 to 1. */
    val leftProgress: () -> Float = { 0f },
) {
    val idle by derivedStateOf(idle)
    val leaving by derivedStateOf(leaving)
}

internal val LocalQsHostTransition = compositionLocalOf<QsHostTransition?> { null }

@Composable
internal fun ContentScope.qsHostTransition(): QsHostTransition {
    val state = layoutState
    val key = contentKey
    return remember(state, key) {
        fun leavingTransition(): TransitionState.Transition? =
            (state.transitionState as? TransitionState.Transition)?.takeIf {
                it.key != CosShadeSwitch && it.fromContent == key && it.toContent != key
            }
        QsHostTransition(
            idle = { state.transitionState !is TransitionState.Transition },
            leaving = { leavingTransition() != null },
            leftProgress = { leavingTransition()?.progress?.coerceIn(0f, 1f) ?: 0f },
        )
    }
}

@Composable
fun ContentScope.StyledQuickSettingsContent(
    viewModel: QuickSettingsContainerViewModel,
    mediaInRow: Boolean,
    modifier: Modifier = Modifier,
    mediaSquishiness: () -> Float = { 1f },
) {
    CompositionLocalProvider(LocalQsPanelStyle provides viewModel.panelStyle) {
        when (viewModel.panelStyle) {
            QsPanelStyle.Default,
            QsPanelStyle.Stock ->
                DefaultQuickSettingsContent(viewModel, mediaInRow, modifier, mediaSquishiness)
            QsPanelStyle.Penguin -> PenguinGridQuickSettings(viewModel, modifier)
            QsPanelStyle.MyUi ->
                MyUiQuickSettingsContent(viewModel, modifier, mediaSquishiness)
            QsPanelStyle.Harmony -> HarmonyQuickSettingsContent(viewModel, modifier)
            QsPanelStyle.OneUi -> OneUiQuickSettingsContent(viewModel, modifier)
            QsPanelStyle.ColorOs -> ColorOsQuickSettingsContent(viewModel, modifier)
        }
    }
}

@Composable
private fun ContentScope.MyUiQuickSettingsContent(
    viewModel: QuickSettingsContainerViewModel,
    modifier: Modifier = Modifier,
    mediaSquishiness: () -> Float = { 1f },
) {
    val gap = MyUiGridGap
    val allTiles = viewModel.tileGridViewModel.tileViewModels
    val cardSpecs = MyUiCardSpecs(allTiles)
    val gridTiles = remember(allTiles, cardSpecs) { allTiles.filterNot { it.spec in cardSpecs } }
    var interactable by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        snapshotFlow { Elements.QuickSettingsContent.currentAlpha() }
            .filterNotNull()
            .collect { interactable = it >= .5f }
    }

    QsColumns(
        gap = gap,
        modifier =
            modifier
                .element(Elements.QuickSettingsContent)
                .padding(horizontal = dimensionResource(id = R.dimen.qs_horizontal_margin))
                .sysuiResTag("quick_settings_panel"),
        first = {
            MyUiHeaderRow(tiles = allTiles, interactable = interactable)
            if (
                viewModel.showMedia && viewModel.hasMediaCards && isAlwaysComposedContentVisible()
            ) {
                Element(key = Media.Elements.MediaCarousel, modifier = Modifier) {
                    MyUiMediaCard(
                        viewModelFactory = viewModel.mediaViewModelFactory,
                        behavior = QuickSettingsContainerViewModel.mediaUiBehavior,
                    )
                }
            }
        },
        second = {
            var listening by remember { mutableStateOf(false) }
            LifecycleStartEffect(Unit) {
                listening = true
                onStopOrDispose { listening = false }
            }
            Box {
                GridAnchor()
                MyUiTileGrid(
                    tiles = gridTiles,
                    columns = 4,
                    gap = gap,
                    modifier = Modifier.element(Elements.QuickSettingsTiles),
                )
            }
            val editButtonViewModel =
                rememberViewModel(traceName = "MyUiQuickSettings-editButton") {
                    viewModel.editModeButtonViewModelFactory.create()
                }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                EditModeButton(viewModel = editButtonViewModel, isVisible = interactable)
            }
        },
    )
}

@Composable
private fun ContentScope.HarmonyQuickSettingsContent(
    viewModel: QuickSettingsContainerViewModel,
    modifier: Modifier = Modifier,
) {
    val allTiles = viewModel.tileGridViewModel.tileViewModels
    val cardSpecs = remember(allTiles) { harmonyCardSpecs(allTiles) }
    val toggles = remember(allTiles, cardSpecs) { allTiles.filterNot { it.spec.spec in cardSpecs } }
    var interactable by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        snapshotFlow { Elements.QuickSettingsContent.currentAlpha() }
            .filterNotNull()
            .collect { interactable = it >= .5f }
    }

    QsColumns(
        gap = HarmonyGap,
        modifier =
            modifier
                .element(Elements.QuickSettingsContent)
                .padding(horizontal = dimensionResource(id = R.dimen.qs_horizontal_margin))
                .sysuiResTag("quick_settings_panel"),
        first = {
            HarmonyTitle()
            HarmonyHeader(viewModel)
        },
        second = {
            Box(Modifier.element(Elements.QuickSettingsTiles)) {
                GridAnchor()
                HarmonyTogglesCard(
                    tiles = toggles,
                    brightness = {
                        Element(key = Elements.BrightnessSlider, modifier = Modifier) {
                            Box(Modifier.thenIf(!interactable) { Modifier.gesturesDisabled() }) {
                                BrightnessLayout(
                                    enable = interactable,
                                    horizontal = true,
                                    sliderHeight = LyingSliderHeight,
                                )
                            }
                        }
                    },
                )
            }
            HarmonyCastCard(allTiles)
            HarmonyConnectedDevices(allTiles)
            if (contentKey != Overlays.QuickSettingsShade) {
                val editButtonViewModel =
                    rememberViewModel(traceName = "HarmonyQuickSettings-editButton") {
                        viewModel.editModeButtonViewModelFactory.create()
                    }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    EditModeButton(viewModel = editButtonViewModel, isVisible = interactable)
                }
            }
        },
    )
}

@Composable
private fun ContentScope.OneUiQuickSettingsContent(
    viewModel: QuickSettingsContainerViewModel,
    modifier: Modifier = Modifier,
) {
    val tiles = viewModel.tileGridViewModel.tileViewModels
    val largeSpecs =
        remember(viewModel.tileGridViewModel.largeTiles) {
            viewModel.tileGridViewModel.largeTiles.map { it.spec }.toSet()
        }
    var interactable by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        snapshotFlow { Elements.QuickSettingsContent.currentAlpha() }
            .filterNotNull()
            .collect { interactable = it >= .5f }
    }
    val showMedia =
        viewModel.showMedia && viewModel.hasMediaCards && isAlwaysComposedContentVisible()

    Box(
        modifier =
            modifier
                .element(Elements.QuickSettingsContent)
                .padding(horizontal = dimensionResource(id = R.dimen.qs_horizontal_margin))
                .sysuiResTag("quick_settings_panel")
    ) {
        Box(Modifier.element(Elements.QuickSettingsTiles)) {
            GridAnchor()
            OneUiQuickSettingsPanel(
                tiles = tiles,
                largeTileSpecs = largeSpecs,
                brightness = { sliderModifier ->
                    Element(key = Elements.BrightnessSlider, modifier = sliderModifier) {
                        Box(Modifier.thenIf(!interactable) { Modifier.gesturesDisabled() }) {
                            BrightnessLayout(
                                enable = interactable,
                                horizontal = true,
                                sliderHeight = OneUiSliderRowHeight,
                            )
                        }
                    }
                },
                media =
                    if (showMedia) {
                        {
                            Element(key = Media.Elements.MediaCarousel, modifier = Modifier) {
                                PenguinMediaCard(
                                    viewModelFactory = viewModel.mediaViewModelFactory,
                                    behavior = QuickSettingsContainerViewModel.mediaUiBehavior,
                                    square = false,
                                    controlCentre = true,
                                    height = qsModuleHeight(2),
                                )
                            }
                        }
                    } else {
                        null
                    },
            )
        }
    }
}

private val OneUiSliderRowHeight = 72.dp

@Composable
private fun ContentScope.ColorOsQuickSettingsContent(
    viewModel: QuickSettingsContainerViewModel,
    modifier: Modifier = Modifier,
) {
    val allTiles = viewModel.tileGridViewModel.tileViewModels
    val cardSpecs = remember(allTiles) { colorOsCardSpecs(allTiles) }
    val gridTiles = remember(allTiles, cardSpecs) { allTiles.filterNot { it.spec.spec in cardSpecs } }
    var interactable by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        snapshotFlow { Elements.QuickSettingsContent.currentAlpha() }
            .filterNotNull()
            .collect { interactable = it >= .5f }
    }

    val own = qsHostTransition()
    val host = LocalQsHostTransition.current ?: own
    val transition =
        remember(host, own) {
            QsHostTransition(
                idle = { host.idle && own.idle },
                leaving = { host.leaving || own.leaving },
                leftProgress = { if (host.leaving) host.leftProgress() else own.leftProgress() },
            )
        }
    val cosHide = rememberCosFeature(CosFeature.Shade)
    val glassFade = remember { Animatable(1f) }
    LaunchedEffect(cosHide && transition.leaving) {
        glassFade.animateTo(
            if (cosHide && transition.leaving) 0f else 1f,
            spring(dampingRatio = 1f, stiffness = 3948f),
        )
    }
    val outerGlassAlpha = LocalGlassBlurAlpha.current
    CompositionLocalProvider(
        LocalQsHostTransition provides transition,
        LocalGlassBlurAlpha provides
            remember(outerGlassAlpha) { { outerGlassAlpha() * glassFade.value } },
    ) {
        QsColumns(
            gap = ColorOsGap,
            modifier =
                modifier
                    .element(Elements.QuickSettingsContent)
                    .padding(horizontal = dimensionResource(id = R.dimen.qs_horizontal_margin))
                    .sysuiResTag("quick_settings_panel"),
            first = { ColorOsHeader(viewModel, interactable) },
        ) {
            Box(Modifier.element(Elements.QuickSettingsTiles)) {
                GridAnchor()
                ColorOsTileGrid(gridTiles)
            }
            if (contentKey != Overlays.QuickSettingsShade) {
                val editButtonViewModel =
                    rememberViewModel(traceName = "ColorOsQuickSettings-editButton") {
                        viewModel.editModeButtonViewModelFactory.create()
                    }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    EditModeButton(viewModel = editButtonViewModel, isVisible = interactable)
                }
            }
        }
    }
}

@Composable
fun ContentScope.ColorOsHeader(
    viewModel: QuickSettingsContainerViewModel,
    interactable: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val showMedia =
        viewModel.showMedia && viewModel.hasMediaCards && isAlwaysComposedContentVisible()
    ColorOsTopRows(
        tiles = viewModel.tileGridViewModel.tileViewModels,
        media =
            if (showMedia) {
                { height ->
                    Element(key = Media.Elements.MediaCarousel, modifier = Modifier) {
                        ColorOsMediaFrame {
                            PenguinMediaCard(
                                viewModelFactory = viewModel.mediaViewModelFactory,
                                behavior = QuickSettingsContainerViewModel.mediaUiBehavior,
                                square = true,
                                height = height,
                            )
                        }
                    }
                }
            } else {
                null
            },
        sliders = { width, height ->
            Element(key = Elements.BrightnessSlider, modifier = Modifier) {
                Row(
                    modifier =
                        Modifier.fillMaxWidth().thenIf(!interactable) {
                            Modifier.gesturesDisabled()
                        },
                    horizontalArrangement = spacedBy(ColorOsGap, Alignment.CenterHorizontally),
                ) {
                    BrightnessLayout(
                        enable = interactable,
                        verticalWidth = width,
                        sliderHeight = height,
                    )
                    VolumeLayout(
                        enable = interactable,
                        verticalWidth = width,
                        sliderHeight = height,
                    )
                }
            }
        },
        modifier = modifier.element(Elements.ConnectivityFolder),
    )
}

@Composable
fun ContentScope.HarmonyHeader(
    viewModel: QuickSettingsContainerViewModel,
    modifier: Modifier = Modifier,
) {
    val showMedia =
        viewModel.showMedia && viewModel.hasMediaCards && isAlwaysComposedContentVisible()
    HarmonyTopRow(
        tiles = viewModel.tileGridViewModel.tileViewModels,
        media =
            if (showMedia) {
                {
                    Element(key = Media.Elements.MediaCarousel, modifier = Modifier) {
                        PenguinMediaCard(
                            viewModelFactory = viewModel.mediaViewModelFactory,
                            behavior = QuickSettingsContainerViewModel.mediaUiBehavior,
                            square = true,
                        )
                    }
                }
            } else {
                null
            },
        modifier = modifier.element(Elements.ConnectivityFolder),
    )
}

@Composable
fun ContentScope.MyUiHeaderRow(
    tiles: List<TileViewModel>,
    interactable: Boolean,
    modifier: Modifier = Modifier,
) {
    val gap = MyUiGridGap
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val column = (maxWidth - gap * 3) / 4
        val headerHeight = column / MyUiTileAspect * 2 + gap
        Row(
            modifier = Modifier.fillMaxWidth().height(headerHeight),
            horizontalArrangement = spacedBy(gap),
        ) {
            Box(Modifier.weight(1f)) {
                MyUiConnectivityCard(
                    tiles = tiles,
                    modifier = Modifier.element(Elements.ConnectivityFolder),
                )
            }
            Element(key = Elements.BrightnessSlider, modifier = Modifier.weight(1f)) {
                Row(
                    modifier =
                        Modifier.fillMaxWidth().thenIf(!interactable) {
                            Modifier.gesturesDisabled()
                        },
                    horizontalArrangement = spacedBy(gap),
                ) {
                    Box(Modifier.weight(1f)) {
                        VolumeLayout(
                            enable = interactable,
                            verticalCornerRadius = MyUiSliderCorner,
                            verticalWidth = null,
                            sliderHeight = headerHeight,
                        )
                    }
                    Box(Modifier.weight(1f)) {
                        BrightnessLayout(
                            enable = interactable,
                            verticalCornerRadius = MyUiSliderCorner,
                            verticalWidth = null,
                            sliderHeight = headerHeight,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun penguinPanel(
    tiles: List<TileSpec>,
    largeTiles: Set<TileSpec>,
    folderEnabled: Boolean,
    mediaShown: Boolean,
): kotlin.Pair<List<PanelBand>, List<PanelBand>> {
    val folderSpan = secureIntSetting(SETTING_QS_FOLDER_SPAN, 1)
    val mediaSpan = secureIntSetting(SETTING_QS_MEDIA_SPAN, 1)
    val slidersSpan = secureIntSetting(SETTING_QS_SLIDERS_SPAN, DEFAULT_SLIDERS_SPAN)
    val folderIndex = secureIntSetting("qs_connectivity_folder_edit_index", 1)
    val mediaIndex = secureIntSetting("qs_media_edit_index", 0)
    val slidersIndex = secureIntSetting("qs_sliders_edit_index", 2)
    return remember(
        tiles,
        largeTiles,
        folderEnabled,
        mediaShown,
        folderSpan,
        mediaSpan,
        slidersSpan,
        folderIndex,
        mediaIndex,
        slidersIndex,
    ) {
        val all = listOfNotNull(FOLDER_SPEC.takeIf { folderEnabled }, MEDIA_SPEC, SLIDERS_SPEC)
        val present = all.filter { it != MEDIA_SPEC || mediaShown }
        val index = mapOf(FOLDER_SPEC to folderIndex, MEDIA_SPEC to mediaIndex, SLIDERS_SPEC to slidersIndex)
        val span = mapOf(FOLDER_SPEC to folderSpan, MEDIA_SPEC to mediaSpan, SLIDERS_SPEC to slidersSpan)
        val order = panelOrder(tiles, present, all) { index.getValue(it) }
        splitPanel(packPanel(order, largeTiles) { span.getValue(it) >= 2 }, largeTiles)
    }
}

@Composable
internal fun PanelElementPlaceholder(height: Dp, iconRes: Int, label: String) {
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .height(height)
                .clip(RoundedCornerShape(28.dp))
                .background(
                    if (liquidGlassCos) Color.Transparent
                    else if (liquidGlassOn) LiquidGlassSurface
                    else MaterialTheme.colorScheme.surfaceVariant
                )
                .glassRim(RoundedCornerShape(28.dp)),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
fun panelElementPreviews(
    viewModel: QuickSettingsContainerViewModel
): Map<TileSpec, @Composable () -> Unit> {
    val elementHeight = dimensionResource(id = R.dimen.common_tile_default_tile_height)
    val standingHeight =
        elementHeight * 2 + dimensionResource(id = R.dimen.qs_tile_margin_vertical)
    val gap = dimensionResource(id = R.dimen.qs_tile_margin_horizontal)
    val folderSpan = secureIntSetting(SETTING_QS_FOLDER_SPAN, 1)
    val mediaSpan = secureIntSetting(SETTING_QS_MEDIA_SPAN, 1)
    val slidersStanding = secureIntSetting(SETTING_QS_SLIDERS_SPAN, DEFAULT_SLIDERS_SPAN) < 2
    return mapOf(
        FOLDER_SPEC to
            {
                PanelElementPreview(span = folderSpan) {
                    ConnectivityFolder(
                        tiles = viewModel.tileGridViewModel.tileViewModels,
                        interactive = false,
                        compactHeight = if (folderSpan < 2) qsModuleHeight(2) else null,
                    )
                }
            },
        MEDIA_SPEC to
            {
                PanelElementPreview(span = mediaSpan) {
                    if (viewModel.hasMediaCards) {
                        PenguinMediaCard(
                            viewModelFactory = viewModel.mediaViewModelFactory,
                            behavior = QuickSettingsContainerViewModel.mediaUiBehavior,
                            square = mediaSpan < 2,
                            interactive = false,
                        )
                    } else {
                        PanelElementPlaceholder(
                            height = if (mediaSpan >= 2) Media.DEFAULT_HEIGHT else standingHeight,
                            iconRes = R.drawable.ic_music_note,
                            label = "Media player",
                        )
                    }
                }
            },
        SLIDERS_SPEC to
            {
                PanelElementPreview(span = if (slidersStanding) 1 else 2) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = spacedBy(gap),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.weight(1f)) {
                            BrightnessLayout(
                                enable = false,
                                horizontal = !slidersStanding,
                                verticalCornerRadius = MyUiSliderCorner,
                                verticalWidth = null,
                                sliderHeight =
                                    if (slidersStanding) standingHeight else LyingSliderHeight,
                                interactive = false,
                            )
                        }
                        Box(Modifier.weight(1f)) {
                            VolumeLayout(
                                enable = false,
                                horizontal = !slidersStanding,
                                verticalCornerRadius = MyUiSliderCorner,
                                verticalWidth = null,
                                sliderHeight =
                                    if (slidersStanding) standingHeight else LyingSliderHeight,
                                interactive = false,
                            )
                        }
                    }
                }
            },
    )
}

@Composable
fun qsHeaderPreview(viewModel: QuickSettingsContainerViewModel): (@Composable () -> Unit)? {
    val style = effectiveQsPanelStyle()
    if (style != QsPanelStyle.MyUi) return null
    val tiles = viewModel.tileGridViewModel.tileViewModels
    return {
        val gap = MyUiGridGap
        CompositionLocalProvider(LocalMyUiInteractive provides false) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val column = (maxWidth - gap * 3) / 4
                val headerHeight = column / MyUiTileAspect * 2 + gap
                Row(
                    modifier = Modifier.fillMaxWidth().height(headerHeight),
                    horizontalArrangement = spacedBy(gap),
                ) {
                    Box(Modifier.weight(1f)) { MyUiConnectivityCard(tiles = tiles) }
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = spacedBy(gap),
                    ) {
                        Box(Modifier.weight(1f)) {
                            VolumeLayout(
                                enable = false,
                                verticalCornerRadius = MyUiSliderCorner,
                                verticalWidth = null,
                                sliderHeight = headerHeight,
                            )
                        }
                        Box(Modifier.weight(1f)) {
                            BrightnessLayout(
                                enable = false,
                                verticalCornerRadius = MyUiSliderCorner,
                                verticalWidth = null,
                                sliderHeight = headerHeight,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun panelElementPreviewHeights(): Map<TileSpec, Dp> {
    val elementHeight = dimensionResource(id = R.dimen.common_tile_default_tile_height)
    val twoRows = elementHeight * 2 + dimensionResource(id = R.dimen.qs_tile_margin_vertical)
    val folderFull = secureIntSetting(SETTING_QS_FOLDER_SPAN, 1) >= 2
    val mediaFull = secureIntSetting(SETTING_QS_MEDIA_SPAN, 1) >= 2
    val slidersLying = secureIntSetting(SETTING_QS_SLIDERS_SPAN, DEFAULT_SLIDERS_SPAN) >= 2
    return mapOf(
        FOLDER_SPEC to if (folderFull) FolderFlatHeight else twoRows,
        MEDIA_SPEC to if (mediaFull) Media.DEFAULT_HEIGHT else twoRows,
        SLIDERS_SPEC to if (slidersLying) LyingSliderHeight else twoRows,
    )
}

@Composable
private fun PanelElementPreview(span: Int, content: @Composable () -> Unit) {
    val margin = dimensionResource(id = R.dimen.qs_horizontal_margin)
    val gap = dimensionResource(id = R.dimen.qs_tile_margin_horizontal)
    val panelWidth = LocalConfiguration.current.screenWidthDp.dp - margin * 2
    val width = if (span >= 2) panelWidth else (panelWidth - gap) / 2
    Box(modifier = Modifier.fillMaxSize().clipToBounds()) {
        Box(
            modifier =
                Modifier.layout { measurable, constraints ->
                    val target = width.roundToPx()
                    val placeable =
                        measurable.measure(Constraints(minWidth = target, maxWidth = target))
                    val scale =
                        min(
                            1f,
                            min(
                                constraints.maxWidth.toFloat() / placeable.width.coerceAtLeast(1),
                                constraints.maxHeight.toFloat() / placeable.height.coerceAtLeast(1),
                            ),
                        )
                    layout(constraints.maxWidth, constraints.maxHeight) {
                        placeable.placeWithLayer(
                            x = (constraints.maxWidth - placeable.width) / 2,
                            y = (constraints.maxHeight - placeable.height) / 2,
                        ) {
                            scaleX = scale
                            scaleY = scale
                        }
                    }
                }
        ) {
            content()
        }
    }
}

private val CompactSliderHeight = 96.dp
internal val MyUiSliderCorner = 22.dp
private val LyingSliderHeight = 56.dp
private val FolderFlatHeight = 92.dp

internal class PanelElement(
    val span: Int,
    val order: Int,
    val alignEnd: Boolean = false,
    val rows: Int = 2,
    val content: @Composable () -> Unit,
)

internal fun panelRowsUsed(elements: List<PanelElement>): Int {
    var rows = 0
    var index = 0
    while (index < elements.size) {
        val element = elements[index]
        if (element.span >= 2) {
            rows += element.rows
            index++
            continue
        }
        val partner = elements.getOrNull(index + 1)?.takeIf { it.span < 2 }
        rows += maxOf(element.rows, partner?.rows ?: element.rows)
        index += if (partner != null) 2 else 1
    }
    return rows
}

internal fun panelFillerSlots(elements: List<PanelElement>): Int {
    var slots = 0
    var index = 0
    while (index < elements.size) {
        if (elements[index].span >= 2) {
            index++
            continue
        }
        val paired = elements.getOrNull(index + 1)?.span?.let { it < 2 } ?: false
        if (!paired) slots++
        index += if (paired) 2 else 1
    }
    return slots
}

@Composable
internal fun PanelElementRows(
    elements: List<PanelElement>,
    gap: Dp,
    filler: @Composable (slot: Int) -> Unit = {},
) {
    var slot = 0
    var index = 0
    while (index < elements.size) {
        val element = elements[index]
        if (element.span >= 2) {
            Box(Modifier.fillMaxWidth()) { element.content() }
            index++
            continue
        }
        val partner = elements.getOrNull(index + 1)?.takeIf { it.span < 2 }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = spacedBy(gap)) {
            val fillerSlot = slot
            if (partner == null && element.alignEnd) {
                Box(Modifier.weight(1f)) { filler(fillerSlot) }
            }
            Box(Modifier.weight(1f)) { element.content() }
            partner?.let { Box(Modifier.weight(1f)) { it.content() } }
            if (partner == null && !element.alignEnd) {
                Box(Modifier.weight(1f)) { filler(fillerSlot) }
            }
        }
        if (partner == null) slot++
        index += if (partner != null) 2 else 1
    }
}

@Composable
private fun ContentScope.FillerTiles(
    viewModel: QuickSettingsContainerViewModel,
    specs: List<TileSpec>,
    listening: Boolean,
) {
    if (specs.isEmpty()) return
    TileGrid(
        viewModel = viewModel.tileGridViewModel,
        includeSpecs = specs,
        columnsOverride = PANEL_FILLER_COLUMNS,
        listening = { listening },
    )
}

@Composable
private fun ContentScope.QsSliders(sliderHeight: Dp, gap: Dp, horizontal: Boolean = false) {
    var interactable by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        snapshotFlow { Elements.QuickSettingsContent.currentAlpha() }
            .filterNotNull()
            .collect { interactable = it >= .5f }
    }
    Element(modifier = Modifier, key = Elements.BrightnessSlider) {
        Row(
            modifier =
                Modifier.fillMaxWidth().thenIf(!interactable) { Modifier.gesturesDisabled() },
            horizontalArrangement = spacedBy(gap, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                BrightnessLayout(
                    enable = interactable,
                    horizontal = true,
                    sliderHeight = sliderHeight,
                )
            }
            Box(Modifier.weight(1f)) {
                VolumeLayout(
                    enable = interactable,
                    horizontal = true,
                    sliderHeight = sliderHeight,
                )
            }
        }
    }
}

@Composable
private fun ContentScope.QsStandingSliders(sliderHeight: Dp, gap: Dp) {
    var interactable by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        snapshotFlow { Elements.QuickSettingsContent.currentAlpha() }
            .filterNotNull()
            .collect { interactable = it >= .5f }
    }
    Element(modifier = Modifier, key = Elements.BrightnessSlider) {
        Row(
            modifier =
                Modifier.fillMaxWidth().thenIf(!interactable) { Modifier.gesturesDisabled() },
            horizontalArrangement = spacedBy(gap),
        ) {
            Box(Modifier.weight(1f)) {
                BrightnessLayout(
                    enable = interactable,
                    verticalCornerRadius = MyUiSliderCorner,
                    verticalWidth = null,
                    sliderHeight = sliderHeight,
                )
            }
            Box(Modifier.weight(1f)) {
                VolumeLayout(
                    enable = interactable,
                    verticalCornerRadius = MyUiSliderCorner,
                    verticalWidth = null,
                    sliderHeight = sliderHeight,
                )
            }
        }
    }
}

@Composable
private fun ContentScope.QsMedia(
    viewModel: QuickSettingsContainerViewModel,
    mediaSquishiness: () -> Float,
    square: Boolean = false,
) {
    if (square || secureIntSetting(SETTING_QS_MEDIA_STYLE, 0) != 0) {
        Element(key = Media.Elements.MediaCarousel, modifier = Modifier) {
            PenguinMediaCard(
                viewModelFactory = viewModel.mediaViewModelFactory,
                behavior = QuickSettingsContainerViewModel.mediaUiBehavior,
                square = square,
            )
        }
        return
    }
    Element(key = Media.Elements.MediaCarousel, modifier = Modifier) {
        Media(
            viewModelFactory = viewModel.mediaViewModelFactory,
            presentationStyle = MediaPresentationStyle.Default,
            behavior = QuickSettingsContainerViewModel.mediaUiBehavior,
            onDismissed = viewModel::onMediaSwipeToDismiss,
            mediaSquishiness = mediaSquishiness,
            location = Media.Location.QS,
        )
    }
}

@Composable
private fun ContentScope.DefaultQuickSettingsContent(
    viewModel: QuickSettingsContainerViewModel,
    mediaInRow: Boolean,
    modifier: Modifier = Modifier,
    mediaSquishiness: () -> Float = { 1f },
) {
    DefaultQuickSettingsPanelLayout(
        brightness =
            @Composable {
                if (viewModel.isBrightnessSliderVisible) {
                    var isBrightnessSliderInteractable by remember { mutableStateOf(false) }
                    LaunchedEffect(Unit) {
                        snapshotFlow { Elements.QuickSettingsContent.currentAlpha() }
                            .filterNotNull()
                            .collect { isBrightnessSliderInteractable = it >= .5f }
                    }
                    Element(modifier = Modifier, key = Elements.BrightnessSlider) {
                        BrightnessSliderContainer(
                            viewModel.brightnessSliderViewModel,
                            glassAccent = if (liquidGlassOn) LiquidGlassBrightness else null,
                            containerColors =
                                ContainerColors(
                                    Color.Transparent,
                                    ContainerColors.defaultContainerColor,
                                ),
                            modifier =
                                Modifier.padding(
                                        vertical =
                                            dimensionResource(id = R.dimen.qs_brightness_margin_top)
                                    )
                                    .thenIf(!isBrightnessSliderInteractable) {
                                        Modifier.gesturesDisabled()
                                    },
                        )
                    }
                }
            },
        tiles =
            @Composable {
                var listening by remember { mutableStateOf(false) }
                LifecycleStartEffect(Unit) {
                    listening = true

                    onStopOrDispose { listening = false }
                }

                Box {
                    GridAnchor()
                    TileGrid(
                        viewModel.tileGridViewModel,
                        listening = { listening },
                        modifier = Modifier.element(Elements.QuickSettingsTiles),
                    )
                }
            },
        media =
            @Composable {
                if (isAlwaysComposedContentVisible()) {
                    Element(key = Media.Elements.MediaCarousel, modifier = Modifier) {
                        if (viewModel.panelStyle == QsPanelStyle.Stock && liquidGlassOn) {
                            PenguinMediaCard(
                                viewModelFactory = viewModel.mediaViewModelFactory,
                                behavior = QuickSettingsContainerViewModel.mediaUiBehavior,
                                square = false,
                                controlCentre = true,
                            )
                        } else {
                            Media(
                                viewModelFactory = viewModel.mediaViewModelFactory,
                                presentationStyle = MediaPresentationStyle.Default,
                                behavior = QuickSettingsContainerViewModel.mediaUiBehavior,
                                onDismissed = viewModel::onMediaSwipeToDismiss,
                                mediaSquishiness = mediaSquishiness,
                                location = Media.Location.QS,
                            )
                        }
                    }
                } else {
                    // Add an empty box when QS content is not visible to keep the same number of
                    // elements.
                    Box(modifier = Modifier)
                }
            },
        mediaInRow = mediaInRow,
        modifier =
            modifier
                .element(Elements.QuickSettingsContent)
                .padding(horizontal = dimensionResource(id = R.dimen.qs_horizontal_margin))
                .sysuiResTag("quick_settings_panel"),
    )
}

@Composable
private fun DefaultQuickSettingsPanelLayout(
    brightness: @Composable () -> Unit,
    tiles: @Composable () -> Unit,
    media: @Composable () -> Unit,
    mediaInRow: Boolean,
    modifier: Modifier = Modifier,
) {
    if (mediaInRow) {
        Column(
            verticalArrangement = spacedBy(QuickSettingsShade.Dimensions.VerticalPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = modifier,
        ) {
            brightness()
            Row(
                horizontalArrangement = spacedBy(QuickSettingsShade.Dimensions.HorizontalPadding),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.weight(1f)) { tiles() }
                Box(modifier = Modifier.weight(1f)) { media() }
            }
        }
    } else {
        Column(
            verticalArrangement = spacedBy(QuickSettingsShade.Dimensions.VerticalPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = modifier,
        ) {
            brightness()
            tiles()
            media()
        }
    }
}
