/*
 * Copyright (C) 2026 The PenguinOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.volume.dialog.sliders.ui

import android.os.SystemClock
import android.os.UserHandle
import android.provider.Settings
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.compose.modifiers.thenIf
import com.android.systemui.common.ui.compose.Icon
import com.android.systemui.haptics.slider.SliderHapticFeedbackFilter
import com.android.systemui.haptics.slider.compose.ui.SliderHapticsViewModel
import com.android.systemui.volume.VolumePanelStyle
import com.android.systemui.volume.dialog.sliders.ui.compose.SliderIconsState
import com.android.systemui.volume.dialog.sliders.ui.compose.SliderTrack
import com.android.systemui.volume.dialog.sliders.ui.viewmodel.VolumeDialogOverscrollViewModel
import com.android.systemui.volume.dialog.sliders.ui.viewmodel.VolumeDialogSliderViewModel
import com.android.systemui.volume.haptics.ui.VolumeHapticsConfigsProvider
import com.android.systemui.volume.ui.compose.slider.AccessibilityParams
import com.android.systemui.volume.ui.compose.slider.Haptics
import com.android.systemui.volume.ui.compose.slider.Slider
import com.android.systemui.volume.ui.compose.slider.SliderIcon
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import com.android.systemui.res.R
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import com.android.systemui.qs.composefragment.cosGlass

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun VolumePanelSlider(
    viewModel: VolumeDialogSliderViewModel,
    overscrollViewModel: VolumeDialogOverscrollViewModel,
    hapticsViewModelFactory: SliderHapticsViewModel.Factory,
    isVolumeDialogVertical: Boolean,
    panelStyle: VolumePanelStyle = VolumePanelStyle.DEFAULT,
    showLabel: Boolean = false,
    modifier: Modifier = Modifier,
    dimensions: VolumeSliderDimensions =
        if (isVolumeDialogVertical) {
            VolumeSliderDimensions.Vertical
        } else {
            VolumeSliderDimensions.Horizontal
        },
) {
    val isPillStyle = panelStyle != VolumePanelStyle.DEFAULT
    val isOneUi = panelStyle == VolumePanelStyle.ONE_UI
    val isMyUi = panelStyle == VolumePanelStyle.MYUI
    val isCos = panelStyle == VolumePanelStyle.COLOR_OS
    val colors =
        when {
            isCos ->
                SliderDefaults.colors(
                    activeTrackColor = Color.Transparent,
                    inactiveTrackColor = Color.Transparent,
                    activeTickColor = Color.Transparent,
                    inactiveTickColor = Color.Transparent,
                    disabledActiveTrackColor = Color.Transparent,
                    disabledInactiveTrackColor = Color.Transparent,
                    disabledActiveTickColor = Color.Transparent,
                    disabledInactiveTickColor = Color.Transparent,
                )
            isMyUi -> {
                val active = colorResource(R.color.volume_panel_myui_track_active)
                val inactive = colorResource(R.color.volume_panel_myui_track_inactive)
                SliderDefaults.colors(
                    activeTrackColor = active,
                    inactiveTrackColor = inactive,
                    activeTickColor = active,
                    inactiveTickColor = inactive,
                    disabledActiveTrackColor = active,
                    disabledInactiveTrackColor = inactive,
                    disabledActiveTickColor = active,
                    disabledInactiveTickColor = inactive,
                )
            }
            isOneUi -> {
                val active = colorResource(R.color.volume_panel_oneui_track_active)
                val inactive = colorResource(R.color.volume_panel_oneui_track_inactive)
                SliderDefaults.colors(
                    activeTrackColor = active,
                    inactiveTrackColor = inactive,
                    activeTickColor = active,
                    inactiveTickColor = inactive,
                    disabledActiveTrackColor = active,
                    disabledInactiveTrackColor = inactive,
                    disabledActiveTickColor = active,
                    disabledInactiveTickColor = inactive,
                )
            }
            isPillStyle -> {
                val active = colorResource(R.color.volume_panel_expandable_track_active)
                val inactive = colorResource(R.color.volume_panel_expandable_track_inactive)
                SliderDefaults.colors(
                    activeTrackColor = active,
                    inactiveTrackColor = inactive,
                    activeTickColor = active,
                    inactiveTickColor = inactive,
                    disabledActiveTrackColor = active,
                    disabledInactiveTrackColor = inactive,
                    disabledActiveTickColor = active,
                    disabledInactiveTickColor = inactive,
                )
            }
            isVolumeDialogVertical ->
                SliderDefaults.colors(
                    activeTickColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    disabledActiveTickColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    disabledInactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                )
            else ->
                SliderDefaults.colors(
                    activeTickColor = MaterialTheme.colorScheme.onPrimary,
                    inactiveTickColor = MaterialTheme.colorScheme.onSurface,
                    inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    disabledActiveTickColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    disabledInactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                )
        }
    val collectedSliderStateModel by viewModel.state.collectAsStateWithLifecycle(null)
    val sliderStateModel = collectedSliderStateModel ?: return
    val interactionSource = remember { MutableInteractionSource() }
    // Miku-Picked UI: the "volume slider haptics" switch applies to these sliders too.
    val context = LocalContext.current
    val isHapticsEnabled = remember {
        Settings.Secure.getIntForUser(
            context.contentResolver,
            Settings.Secure.VOLUME_DIALOG_HAPTIC_FEEDBACK,
            1,
            UserHandle.USER_CURRENT,
        ) != 0
    }

    val trackSize =
        when {
            isMyUi -> dimensionResource(R.dimen.volume_panel_myui_track_size)
            isOneUi -> dimensionResource(R.dimen.volume_panel_oneui_track_size)
            isPillStyle -> dimensionResource(R.dimen.volume_panel_expandable_track_size)
            else -> dimensions.trackSize
        }
    val trackCornerSize =
        when {
            isMyUi -> dimensionResource(R.dimen.volume_panel_myui_track_size) / 2
            isOneUi -> dimensionResource(R.dimen.volume_panel_oneui_track_corner_radius)
            isPillStyle -> dimensionResource(R.dimen.volume_panel_expandable_track_corner_radius)
            else -> 12.dp
        }
    val trackOutline = colorResource(R.color.volume_panel_expandable_track_outline)

    val bottomIcon: (@Composable BoxScope.(iconsState: SliderIconsState) -> Unit)? =
        if (isPillStyle && !isMyUi && !isCos) {
            val iconTint =
                if (isOneUi) {
                    colorResource(R.color.volume_panel_oneui_icon)
                } else {
                    val range = sliderStateModel.valueRange
                    val span = range.endInclusive - range.start
                    val fraction =
                        if (span <= 0f) 0f else (sliderStateModel.value - range.start) / span
                    val iconCoveredFraction =
                        dimensions.iconSize /
                            dimensionResource(R.dimen.volume_panel_expandable_slider_height)
                    if (fraction > iconCoveredFraction) {
                        colorResource(R.color.volume_panel_expandable_icon_on_active)
                    } else {
                        colorResource(R.color.volume_panel_expandable_icon_on_inactive)
                    }
                }
            {
                SliderIcon(
                    icon = {
                        Icon(
                            icon = sliderStateModel.icon,
                            tint = { iconTint },
                            modifier = Modifier.size(dimensions.iconSize),
                        )
                    },
                    isVisible = true,
                )
            }
        } else {
            null
        }

    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect {
            when (it) {
                is DragInteraction.Start -> viewModel.onSliderDragStarted()
                is DragInteraction.Cancel -> viewModel.onSliderDragFinished()
                is DragInteraction.Stop -> viewModel.onSliderDragFinished()
            }
        }
    }

    val slider: @Composable (Modifier) -> Unit = { sliderModifier ->
        Slider(
            value = sliderStateModel.value,
            valueRange = sliderStateModel.valueRange,
            onValueChanged = { value ->
                overscrollViewModel.setSlider(
                    value = value,
                    min = sliderStateModel.valueRange.start,
                    max = sliderStateModel.valueRange.endInclusive,
                )
                viewModel.setStreamVolume(value, true)
            },
            onValueChangeFinished = { viewModel.onSliderChangeFinished(it) },
            isEnabled = !sliderStateModel.isDisabled,
            isReverseDirection = true,
            isVertical = isVolumeDialogVertical,
            colors = colors,
            interactionSource = interactionSource,
            haptics =
                if (isHapticsEnabled) {
                    Haptics.Enabled(
                        hapticsViewModelFactory = hapticsViewModelFactory,
                        hapticConfigs =
                            VolumeHapticsConfigsProvider.continuousConfigs(
                                SliderHapticFeedbackFilter()
                            ),
                        orientation =
                            if (isVolumeDialogVertical) {
                                Orientation.Vertical
                            } else {
                                Orientation.Horizontal
                            },
                    )
                } else {
                    Haptics.Disabled
                },
            stepDistance = 1f,
            track = { sliderState ->
                SliderTrack(
                    sliderState,
                    colors = colors,
                    isEnabled = !sliderStateModel.isDisabled,
                    isVertical = isVolumeDialogVertical,
                    modifier =
                        if (isPillStyle) {
                            Modifier.clip(RoundedCornerShape(trackCornerSize))
                                .thenIf(!isOneUi && !isMyUi && !isCos) {
                                    Modifier.border(
                                        1.dp,
                                        trackOutline,
                                        RoundedCornerShape(trackCornerSize),
                                    )
                                }
                        } else {
                            Modifier
                        },
                    thumbTrackGapSize = if (isPillStyle) 0.dp else 6.dp,
                    trackCornerSize = trackCornerSize,
                    trackInsideCornerSize = if (isPillStyle) 0.dp else 2.dp,
                    activeTrackStartIcon = bottomIcon,
                    activeTrackEndIcon =
                        if (isPillStyle) {
                            null
                        } else {
                            { iconsState ->
                                SliderIcon(
                                    icon = {
                                        Icon(
                                            icon = sliderStateModel.icon,
                                            tint = null,
                                            modifier = Modifier.size(dimensions.iconSize),
                                        )
                                    },
                                    isVisible = !iconsState.isInactiveTrackEndIconVisible,
                                )
                            }
                        },
                    inactiveTrackEndIcon =
                        if (isPillStyle) {
                            null
                        } else {
                            { iconsState ->
                                SliderIcon(
                                    icon = {
                                        Icon(
                                            icon = sliderStateModel.icon,
                                            tint = null,
                                            modifier = Modifier.size(dimensions.iconSize),
                                        )
                                    },
                                    isVisible = iconsState.isInactiveTrackEndIconVisible,
                                )
                            }
                        },
                    trackSize = trackSize,
                )
            },
            thumb =
                if (isPillStyle) {
                    { _, _ -> }
                } else {
                    { sliderState, interactions ->
                        SliderDefaults.Thumb(
                            sliderState = sliderState,
                            interactionSource = interactions,
                            enabled = !sliderStateModel.isDisabled,
                            colors = colors,
                            thumbSize = DpSize(dimensions.thumbWidth, dimensions.thumbHeight),
                        )
                    }
                },
            accessibilityParams = AccessibilityParams(contentDescription = sliderStateModel.label),
            modifier =
                sliderModifier.pointerInput(Unit) {
                    coroutineScope {
                        val currentContext = currentCoroutineContext()
                        awaitPointerEventScope {
                            while (currentContext.isActive) {
                                viewModel.onTouchEvent(awaitPointerEvent())
                            }
                        }
                    }
                },
        )
    }

    if (isCos) {
        val range = sliderStateModel.valueRange
        val span = range.endInclusive - range.start
        val fraction =
            if (span <= 0f) 0f
            else ((sliderStateModel.value - range.start) / span).coerceIn(0f, 1f)
        val dragged by interactionSource.collectIsDraggedAsState()
        var touching by remember { mutableStateOf(false) }
        var slim by remember { mutableStateOf(false) }
        var nudge by remember { mutableStateOf(false) }
        val lastKeyChange = remember { longArrayOf(0L) }
        val lastTouch = remember { longArrayOf(0L) }
        LaunchedEffect(sliderStateModel.value) {
            val now = SystemClock.uptimeMillis()
            if (touching || dragged || now - lastTouch[0] < COS_VOLUME_TOUCH_GRACE_MS) {
                return@LaunchedEffect
            }
            val showing = now - lastKeyChange[0] < COS_VOLUME_SESSION_MS
            lastKeyChange[0] = now
            if (!showing) return@LaunchedEffect
            slim = true
            nudge = true
            delay(COS_VOLUME_NUDGE_MS)
            nudge = false
            delay(COS_VOLUME_SESSION_MS)
            slim = false
        }
        val barWidth by
            animateFloatAsState(
                if (slim) COS_VOLUME_SLIM_WIDTH else 1f,
                if (slim) spring(dampingRatio = 0.9f, stiffness = 320f)
                else spring(dampingRatio = 0.75f, stiffness = 250f),
                label = "cosVolumeSlim",
            )
        // Miku-Picked UI: the panel is always on the right here.
        val onLeft = false
        val width = dimensionResource(R.dimen.volume_panel_cos_slider_width)
        val edgeShift = width * ((1f - COS_VOLUME_SLIM_WIDTH) * 0.5f) * if (onLeft) -1f else 1f
        LaunchedEffect(slim) { CosVolumeSlim.slim.value = slim }
        val shift by
            animateDpAsState(
                when {
                    !slim -> 0.dp
                    nudge -> edgeShift * 0.6f
                    else -> edgeShift
                },
                spring(dampingRatio = 0.9f, stiffness = 320f),
                label = "cosVolumeNudge",
            )
        val widen by
            animateFloatAsState(
                if (dragged) 1.16f else 1f,
                spring(dampingRatio = 1f, stiffness = 987f),
                label = "cosVolumeWiden",
            )
        val shape = RoundedCornerShape(50)
        Box(
            modifier.width(width).pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        touching = event.changes.any { it.pressed }
                        lastTouch[0] = SystemClock.uptimeMillis()
                        if (touching) {
                            slim = false
                            nudge = false
                        }
                    }
                }
            }
        ) {
            Box(
                Modifier.align(Alignment.Center)
                    .offset(x = shift)
                    .fillMaxHeight()
                    .width(width * barWidth)
                    .graphicsLayer { scaleX = widen }
                    .cosGlass(shape)
            ) {
                Box(
                    Modifier.align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .fillMaxHeight(fraction)
                        .background(Color(0xF7FFFFFF))
                )
                val covered =
                    dimensionResource(R.dimen.volume_panel_cos_slider_height) * fraction > 32.dp
                Icon(
                    icon = sliderStateModel.icon,
                    tint = { if (covered) Color(0xB3000000) else Color(0xE6FFFFFF) },
                    modifier =
                        Modifier.align(Alignment.BottomCenter)
                            .padding(bottom = 20.dp)
                            .requiredSize(24.dp)
                            .graphicsLayer {
                                alpha =
                                    ((barWidth - COS_VOLUME_SLIM_WIDTH) / (1f - COS_VOLUME_SLIM_WIDTH))
                                        .coerceIn(0f, 1f)
                            },
                )
            }
            slider(Modifier.fillMaxSize())
        }
    } else if (isMyUi) {
        val iconTint = colorResource(R.color.volume_panel_myui_icon)
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
            slider(Modifier.weight(1f))
            Icon(
                icon = sliderStateModel.icon,
                tint = { iconTint },
                modifier = Modifier.padding(top = 12.dp).size(dimensions.iconSize + 4.dp),
            )
        }
    } else if (isOneUi && showLabel) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
            slider(Modifier.weight(1f))
            Text(
                text = sliderStateModel.label,
                color = colorResource(R.color.volume_panel_oneui_label),
                fontSize = 12.sp,
                maxLines = 1,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    } else {
        slider(modifier)
    }
}

private const val COS_VOLUME_SLIM_WIDTH = 0.16f
private const val COS_VOLUME_NUDGE_MS = 300L
private const val COS_VOLUME_SESSION_MS = 3000L
private const val COS_VOLUME_TOUCH_GRACE_MS = 1000L

object CosVolumeSlim {
    val slim = MutableStateFlow(false)
}

/** Slider sizes for the vertical (phone) and horizontal (desktop tile) volume dialogs. */
data class VolumeSliderDimensions(
    val iconSize: Dp,
    val thumbHeight: Dp,
    val thumbWidth: Dp,
    val trackSize: Dp,
) {
    companion object {
        val Vertical =
            VolumeSliderDimensions(
                iconSize = 20.dp,
                thumbWidth = 52.dp,
                thumbHeight = 4.dp,
                trackSize = 40.dp,
            )

        val Horizontal =
            VolumeSliderDimensions(
                iconSize = 24.dp,
                thumbHeight = 40.dp,
                thumbWidth = 3.dp,
                trackSize = 32.dp,
            )
    }
}
