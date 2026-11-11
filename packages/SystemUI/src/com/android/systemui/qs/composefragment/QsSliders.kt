/*
 * Copyright (C) 2026 The PenguinOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.composefragment

import com.android.systemui.penguin.CosFeature
import com.android.systemui.penguin.rememberCosFeature
import com.android.systemui.qs.shared.style.QsPanelStyle

import com.android.systemui.qs.shared.style.LocalQsPanelStyle

import android.animation.Animator
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.media.AudioManager
import android.os.Bundle
import android.os.Trace
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.VisibleForTesting
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Arrangement.spacedBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.approachLayout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onLayoutRectChanged
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.round
import androidx.compose.ui.util.fastRoundToInt
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.android.app.tracing.coroutines.launchTraced
import com.android.compose.animation.scene.ContentKey
import com.android.compose.animation.scene.ContentScope
import com.android.compose.animation.scene.ElementKey
import com.android.compose.animation.scene.ElementMatcher
import com.android.compose.animation.scene.MutableSceneTransitionLayoutState
import com.android.compose.animation.scene.SceneKey
import com.android.compose.animation.scene.SceneTransitionLayout
import com.android.compose.animation.scene.content.state.TransitionState
import com.android.compose.animation.scene.rememberMutableSceneTransitionLayoutState
import com.android.compose.animation.scene.transitions
import com.android.compose.gesture.gesturesDisabled
import com.android.compose.modifiers.height
import com.android.compose.modifiers.padding
import com.android.compose.modifiers.thenIf
import com.android.compose.theme.LocalAndroidColorScheme
import com.android.systemui.qs.shared.style.isStockQsStyle
import com.android.compose.theme.PlatformTheme
import com.android.settingslib.display.BrightnessUtils.GAMMA_SPACE_MAX
import com.android.settingslib.display.BrightnessUtils.GAMMA_SPACE_MIN
import com.android.settingslib.display.BrightnessUtils.convertGammaToLinearFloat
import com.android.settingslib.display.BrightnessUtils.convertLinearToGammaFloat
import com.android.systemui.Dumpable
import com.android.systemui.Flags
import com.android.systemui.Flags.notificationShadeBlur
import com.android.systemui.brightness.ui.compose.BrightnessSliderContainer
import com.android.systemui.brightness.ui.compose.ContainerColors
import com.android.systemui.compose.modifiers.sysUiResTagContainer
import com.android.systemui.compose.modifiers.sysuiResTag
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dump.DumpManager
import com.android.systemui.initOnBackPressedDispatcherOwner
import com.android.systemui.keyboard.shortcut.ui.composable.InteractionsConfig
import com.android.systemui.keyboard.shortcut.ui.composable.ProvideShortcutHelperIndication
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.lifecycle.setSnapshotBinding
import com.android.systemui.log.table.TableLogBuffer
import com.android.systemui.media.controls.ui.view.MediaHost
import com.android.systemui.media.remedia.shared.flag.MediaControlsInComposeFlag
import com.android.systemui.media.remedia.ui.compose.Media
import com.android.systemui.media.remedia.ui.compose.MediaPresentationStyle
import com.android.systemui.media.remedia.ui.compose.MediaUiBehavior
import com.android.systemui.media.remedia.ui.viewmodel.MediaViewModel
import com.android.systemui.plugins.qs.QS
import com.android.systemui.plugins.qs.QSContainerController
import com.android.systemui.qs.composefragment.SceneKeys.QuickQuickSettings
import com.android.systemui.qs.composefragment.SceneKeys.QuickSettings
import com.android.systemui.qs.composefragment.SceneKeys.debugName
import com.android.systemui.qs.composefragment.SceneKeys.toIdleSceneKey
import com.android.systemui.qs.composefragment.ui.GridAnchor
import com.android.systemui.qs.composefragment.ui.NotificationScrimClipParams
import com.android.systemui.qs.composefragment.ui.quickQuickSettingsToQuickSettings
import com.android.systemui.qs.composefragment.ui.toEditMode
import com.android.systemui.qs.composefragment.viewmodel.QSFragmentComposeViewModel
import com.android.systemui.qs.footer.ui.compose.FooterActions
import com.android.systemui.qs.panels.shared.model.QSFragmentComposeClippingTableLog
import com.android.systemui.qs.panels.shared.model.splitInRowsSequence
import com.android.systemui.qs.panels.ui.compose.EditMode
import com.android.systemui.qs.panels.ui.compose.QuickQuickSettings
import com.android.systemui.qs.panels.ui.compose.TileGrid
import com.android.systemui.qs.shared.style.LiquidGlassBrightness
import com.android.systemui.qs.shared.style.LiquidGlassVolume
import com.android.systemui.qs.shared.style.glassRim
import com.android.systemui.qs.shared.style.liquidGlassOn
import com.android.systemui.qs.shared.ui.QuickSettings.Elements
import com.android.systemui.qs.ui.composable.QuickSettingsShade
import com.android.systemui.qs.ui.composable.QuickSettingsShade.systemGestureExclusionInShade
import com.android.systemui.qs.ui.composable.QuickSettingsTheme
import com.android.systemui.res.R
import com.android.systemui.shade.ShadeDisplayAware
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.statusbar.policy.ConfigurationController.ConfigurationListener
import com.android.systemui.util.LifecycleFragment
import com.android.systemui.util.asIndenting
import com.android.systemui.util.kotlin.pairwise
import com.android.systemui.util.printSection
import com.android.systemui.util.println
import java.io.PrintWriter
import java.util.function.Consumer
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import android.database.ContentObserver
import android.hardware.display.BrightnessInfo
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import com.android.systemui.qs.shared.ui.slider.SharedSliderKey

@Composable
@VisibleForTesting
fun spacerLayout(height: Dp) {
    Spacer(modifier = Modifier.height(height))
}

@Composable
@VisibleForTesting
fun DragHandle(
    vm: QSFragmentComposeViewModel,
    enable: Boolean
) {
    val translationY = with(LocalDensity.current) { 100.dp.toPx() }

    val qqsMin = 0.01f
    val qqsMax = 0.4f

    val expansionProgress = vm.expansionState.progress

    val progress = run {
        val range = expansionProgress.coerceIn(qqsMin, qqsMax)
        ((qqsMax - range) / (qqsMax - qqsMin)).coerceIn(0f, 1f)
    }

    val expansionAlpha by animateFloatAsState(targetValue = progress, label = "dragHandleAlpha")

    val offsetY by
        animateFloatAsState(
            targetValue = translationY * (1f - progress),
            label = "dragHandleOffsetY",
        )

    Box(
        modifier =
            Modifier.offset { IntOffset(0, offsetY.roundToInt()) }
                .alpha(expansionAlpha)
                .systemGestureExclusionInShade(enabled = { enable })
                .fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier =
                Modifier.size(width = 56.dp, height = 4.dp)
                    .background(
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                        shape = RoundedCornerShape(2.dp),
                    )
        )
    }
}

@Composable
@VisibleForTesting
fun ContentScope.AnimatedSliders(vm: QSFragmentComposeViewModel, enable: Boolean, sliderHeight: Dp) {
    Box(modifier = Modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement =
                Arrangement.spacedBy(
                    dimensionResource(id = R.dimen.qs_tile_margin_horizontal),
                    Alignment.CenterHorizontally,
                ),
        ) {
            Element(SharedSliderKey.Brightness, modifier = Modifier) {
                BrightnessLayout(enable = enable, vm = vm, sliderHeight = sliderHeight)
            }
            Element(SharedSliderKey.Volume, modifier = Modifier) {
                VolumeLayout(enable = enable, vm = vm, sliderHeight = sliderHeight)
            }
        }
    }
}

@Composable
@VisibleForTesting
fun VolumeLayout(
    enable: Boolean,
    horizontal: Boolean = false,
    verticalCornerRadius: Dp? = null,
    verticalWidth: Dp? = 75.dp,
    vm: QSFragmentComposeViewModel? = null,
    sliderHeight: Dp,
    interactive: Boolean = true,
    capsule: Boolean = false,
) {
    val context = LocalContext.current
    val audioManager = remember(context) {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    var sliderValue by remember { mutableFloatStateOf(0f) }
    var isMuted by remember { mutableStateOf(false) }
    var currentVolume by remember { mutableStateOf(0) }

    val maxVolume = remember { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }

    val updateVolumeState = {
        currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        sliderValue = currentVolume.toFloat() / maxVolume.toFloat()
        isMuted = audioManager.isStreamMute(AudioManager.STREAM_MUSIC) || currentVolume == 0
    }

    DisposableEffect(context) {
        val volumeObserver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == AudioManager.VOLUME_CHANGED_ACTION) {
                    updateVolumeState()
                }
            }
        }
        val filter = IntentFilter(AudioManager.VOLUME_CHANGED_ACTION)
        context.registerReceiver(volumeObserver, filter, Context.RECEIVER_NOT_EXPORTED)

        updateVolumeState()

        onDispose {
            context.unregisterReceiver(volumeObserver)
        }
    }

    val onValueChanged = { newProgress: Float ->
        sliderValue = newProgress
        val newVolume = (newProgress * maxVolume).roundToInt()
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVolume, 0)
        Unit
    }

    val onValueChangeFinished = { finalProgress: Float ->
        val newVolume = (finalProgress * maxVolume).roundToInt()
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVolume, 0)
        Unit
    }

    val cosVolume =
        LocalQsPanelStyle.current == QsPanelStyle.ColorOs || rememberCosFeature(CosFeature.QuickSettings)

    val toggleMute = {
        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, 0)
        updateVolumeState()
        Unit
    }

    val commonModifier =
        Modifier.systemGestureExclusionInShade(enabled = { enable })

    Box(modifier = commonModifier) {
        AlwaysDarkMode {
            val scope = rememberCoroutineScope()
            val icon =
                painterResource(
                    id =
                        if (isMuted || currentVolume == 0) R.drawable.ic_volume_off
                        else if (liquidGlassOn) R.drawable.ic_volume_media
                        else R.drawable.ic_volume_media
                )
            if (horizontal) {
                HorizontalSlider(
                    scope = scope,
                    value = sliderValue,
                    onValueChanged = onValueChanged,
                    onValueChangeFinished = onValueChangeFinished,
                    onLongPress = toggleMute,
                    icon = icon,
                    modifier = Modifier,
                    housingHeight = sliderHeight,
                    interactive = interactive,
                )
            } else if (cosVolume) {
                CosGlassSlider(
                    width = verticalWidth ?: 75.dp,
                    height = sliderHeight,
                    fraction = sliderValue,
                    icon = painterResource(
                        if (isMuted || currentVolume == 0) R.drawable.ic_volume_off
                        else R.drawable.ic_volume_media
                    ),
                    iconTint = CosVolumeTint,
                ) {
                    Box(
                        Modifier.fillMaxSize().thenIf(interactive) {
                            Modifier.sliderGestures(
                                scope, onValueChanged, onValueChangeFinished, toggleMute,
                            )
                        }
                    )
                }
            } else {
                VerticalSlider(
                    scope = scope,
                    value = sliderValue,
                    onValueChanged = onValueChanged,
                    onValueChangeFinished = onValueChangeFinished,
                    onLongPress = toggleMute,
                    icon = icon,
                    glassIconTint = LiquidGlassVolume,
                    modifier = Modifier,
                    housingHeight = sliderHeight,
                    cornerRadius = verticalCornerRadius,
                    housingWidth = verticalWidth,
                    interactive = interactive,
                    capsule = capsule,
                )
            }
        }
    }
}

@Composable
@VisibleForTesting
fun BrightnessLayout(
    enable: Boolean,
    horizontal: Boolean = false,
    verticalCornerRadius: Dp? = null,
    verticalWidth: Dp? = 75.dp,
    vm: QSFragmentComposeViewModel? = null,
    sliderHeight: Dp,
    interactive: Boolean = true,
    capsule: Boolean = false,
) {
    val context = LocalContext.current
    val contentResolver = context.contentResolver
    val displayManager =
        remember(context) {
            context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        }

    var sliderValue by remember { mutableFloatStateOf(0f) }
    var isAuto by remember { mutableStateOf(false) }

    val updateBrightnessState = {
        isAuto = Settings.System.getInt(
            contentResolver,
            Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
        ) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC

        val info: BrightnessInfo? = context.display?.getBrightnessInfo()
        val newBrightness = info?.let {
            val value = convertLinearToGammaFloat(it.brightness, it.brightnessMinimum, it.brightnessMaximum)
            val range = GAMMA_SPACE_MAX - GAMMA_SPACE_MIN
            if (range > 0) {
                ((value - GAMMA_SPACE_MIN).toFloat() / range).coerceIn(0f, 1f)
            } else {
                0f
            }
        } ?: 0f
        sliderValue = newBrightness
    }

    DisposableEffect(context) {
        val handler = Handler(Looper.getMainLooper())
        val brightnessObserver = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                updateBrightnessState()
            }
        }
        val displayListener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {}
            override fun onDisplayRemoved(displayId: Int) {}
            override fun onDisplayChanged(displayId: Int) {
                updateBrightnessState()
            }
        }

        val brightnessModeUri = Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS_MODE)
        val brightnessAdjUri = Settings.System.getUriFor(Settings.System.SCREEN_AUTO_BRIGHTNESS_ADJ)

        contentResolver.registerContentObserver(brightnessModeUri, false, brightnessObserver)
        contentResolver.registerContentObserver(brightnessAdjUri, false, brightnessObserver)
        displayManager.registerDisplayListener(displayListener, handler)

        updateBrightnessState()

        onDispose {
            contentResolver.unregisterContentObserver(brightnessObserver)
            displayManager.unregisterDisplayListener(displayListener)
        }
    }

    val onValueChanged = { newProgress: Float ->
        sliderValue = newProgress
        val info: BrightnessInfo? = context.display?.getBrightnessInfo()
        info?.let {
            val linear = convertGammaToLinearFloat(
                (newProgress * GAMMA_SPACE_MAX).roundToInt(),
                it.brightnessMinimum,
                it.brightnessMaximum
            )
            val brightnessValue = linear.coerceIn(it.brightnessMinimum, it.brightnessMaximum)
            displayManager.setTemporaryBrightness(context.displayId, brightnessValue)
        }
        Unit
    }

    val onValueChangeFinished = { finalProgress: Float ->
        val info: BrightnessInfo? = context.display?.getBrightnessInfo()
        info?.let {
            val linear = convertGammaToLinearFloat(
                (finalProgress * GAMMA_SPACE_MAX).roundToInt(),
                it.brightnessMinimum,
                it.brightnessMaximum
            )
            val brightnessValue = linear.coerceIn(it.brightnessMinimum, it.brightnessMaximum)
            displayManager.setBrightness(context.displayId, brightnessValue)
        }
        Unit
    }

    val toggleBrightnessMode = {
        val newMode = if (isAuto) {
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
        } else {
            Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
        }
        Settings.System.putIntForUser(
            contentResolver,
            Settings.System.SCREEN_BRIGHTNESS_MODE,
            newMode,
            UserHandle.USER_CURRENT
        )
        Unit
    }

    val cosExperience = rememberCosFeature(CosFeature.QuickSettings)
    val cosStyle = LocalQsPanelStyle.current == QsPanelStyle.ColorOs
    val commonModifier =
        Modifier.systemGestureExclusionInShade(enabled = { enable })

    Box(modifier = commonModifier) {
        AlwaysDarkMode {
            val scope = rememberCoroutineScope()
            val icon =
                painterResource(
                    id =
                        if (liquidGlassOn) R.drawable.ic_brightness_full
                        else if (isAuto) R.drawable.ic_qs_brightness_auto_on_new
                        else R.drawable.ic_qs_brightness_auto_off_new
                )
            if (horizontal) {
                HorizontalSlider(
                    scope = scope,
                    value = sliderValue,
                    onValueChanged = onValueChanged,
                    onValueChangeFinished = onValueChangeFinished,
                    onLongPress = toggleBrightnessMode,
                    icon = icon,
                    modifier = Modifier,
                    housingHeight = sliderHeight,
                    interactive = interactive,
                )
            } else if (cosExperience || cosStyle) {
                CosGlassSlider(
                    width = verticalWidth ?: 75.dp,
                    height = sliderHeight,
                    fraction = sliderValue,
                    icon = painterResource(
                        if (isAuto) R.drawable.ic_qs_brightness_auto_on_new
                        else R.drawable.ic_qs_brightness_auto_off_new
                    ),
                    iconTint = if (isAuto) CosAutoBrightnessTint else null,
                ) {
                    Box(
                        Modifier.fillMaxSize().thenIf(interactive) {
                            Modifier.sliderGestures(
                                scope, onValueChanged, onValueChangeFinished,
                                toggleBrightnessMode,
                            )
                        }
                    )
                }
            } else {
                VerticalSlider(
                    scope = scope,
                    value = sliderValue,
                    onValueChanged = onValueChanged,
                    onValueChangeFinished = onValueChangeFinished,
                    onLongPress = toggleBrightnessMode,
                    icon = icon,
                    glassIconTint = LiquidGlassBrightness,
                    modifier = Modifier,
                    housingHeight = sliderHeight,
                    cornerRadius = verticalCornerRadius,
                    housingWidth = verticalWidth,
                    interactive = interactive,
                    capsule = capsule,
                )
            }
        }
    }
}

@Composable
fun VerticalSlider(
    scope: CoroutineScope,
    value: Float,
    onValueChanged: (Float) -> Unit,
    onValueChangeFinished: (Float) -> Unit,
    onLongPress: () -> Unit,
    icon: Painter,
    modifier: Modifier = Modifier,
    housingHeight: Dp,
    interactive: Boolean = true,
    cornerRadius: Dp? = null,
    housingWidth: Dp? = 75.dp,
    capsule: Boolean = false,
    glassIconTint: Color? = null,
) {
    val glass = liquidGlassOn && !isStockQsStyle
    val iconSize = if (capsule) 28.dp else 24.dp
    val iconBottomPadding = if (capsule) housingHeight * 0.16f else 16.dp

    val iconCoveredFraction =
        if (housingHeight > 0.dp) (iconBottomPadding + iconSize) / housingHeight else 0f
    val iconTint =
        if (glass && glassIconTint != null) {
            glassIconTint
        } else if (value > iconCoveredFraction) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurface
        }

    Box(
        modifier = modifier
            .then(housingWidth?.let { Modifier.width(it) } ?: Modifier.fillMaxWidth())
            .height(housingHeight)
            .clip(
                if (capsule) RoundedCornerShape(percent = 50)
                else cornerRadius?.let { RoundedCornerShape(it) } ?: RoundedCornerShape(percent = 50)
            )
            .background(glassSurface())
            .glassRim(
                if (capsule) RoundedCornerShape(percent = 50)
                else cornerRadius?.let { RoundedCornerShape(it) } ?: RoundedCornerShape(percent = 50)
            )
            .thenIf(interactive) {
                Modifier.sliderGestures(scope, onValueChanged, onValueChangeFinished, onLongPress)
            },
        contentAlignment = Alignment.BottomCenter
    ) {
        val fillHeight = housingHeight * value
        val fillCapRadius =
            if (cornerRadius != null || capsule) 0.dp
            else ((housingWidth ?: 0.dp) / 2).coerceAtMost(fillHeight / 2)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(value)
                .clip(RoundedCornerShape(topStart = fillCapRadius, topEnd = fillCapRadius))
                .background(
                    color =
                        if (glass) Color.White
                        else MaterialTheme.colorScheme.primary
                )
        )

        Icon(
            painter = icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier
                .padding(bottom = iconBottomPadding)
                .size(iconSize)
        )
    }
}

@Composable
fun HorizontalSlider(
    scope: CoroutineScope,
    value: Float,
    onValueChanged: (Float) -> Unit,
    onValueChangeFinished: (Float) -> Unit,
    onLongPress: () -> Unit,
    icon: Painter,
    modifier: Modifier = Modifier,
    housingHeight: Dp,
    interactive: Boolean = true,
) {
    val iconSize = 22.dp
    val iconStartPadding = 14.dp
    BoxWithConstraints(
        modifier =
            modifier
                .fillMaxWidth()
                .height(housingHeight)
                .clip(RoundedCornerShape(percent = 50))
                .background(glassSurface())
                .glassRim(RoundedCornerShape(percent = 50))
                .thenIf(interactive) {
                    Modifier.sliderGesturesHorizontal(
                        scope,
                        onValueChanged,
                        onValueChangeFinished,
                        onLongPress,
                    )
                },
        contentAlignment = Alignment.CenterStart,
    ) {
        val fillWidth = maxWidth * value
        val fillCapRadius = (housingHeight / 2).coerceAtMost(fillWidth / 2)
        Box(
            modifier =
                Modifier.fillMaxWidth(value)
                    .fillMaxHeight()
                    .clip(
                        RoundedCornerShape(topEnd = fillCapRadius, bottomEnd = fillCapRadius)
                    )
                    .background(MaterialTheme.colorScheme.primary)
        )
        val iconCovered = fillWidth > iconStartPadding + iconSize
        Icon(
            painter = icon,
            contentDescription = null,
            tint =
                if (iconCovered) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = iconStartPadding).size(iconSize),
        )
    }
}

fun Modifier.sliderGesturesHorizontal(
    scope: CoroutineScope,
    onValueChanged: (Float) -> Unit,
    onValueChangeFinished: (Float) -> Unit,
    onLongPress: () -> Unit,
): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val longPressTimeout = viewConfiguration.longPressTimeoutMillis
        var isLongPress = false
        val longPressJob =
            scope.launch {
                delay(longPressTimeout)
                onLongPress()
                isLongPress = true
            }
        var lastProgress = 0f
        drag(down.id) { change ->
            if (change.pressed) {
                longPressJob.cancel()
                if (!isLongPress) {
                    change.consume()
                    val newProgress = (change.position.x / size.width).coerceIn(0f, 1f)
                    lastProgress = newProgress
                    onValueChanged(newProgress)
                }
            }
        }
        longPressJob.cancel()
        if (!isLongPress) {
            onValueChangeFinished(lastProgress)
        }
    }
}

fun Modifier.sliderGestures(
    scope: CoroutineScope,
    onValueChanged: (Float) -> Unit,
    onValueChangeFinished: (Float) -> Unit,
    onLongPress: () -> Unit
): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val longPressTimeout = viewConfiguration.longPressTimeoutMillis
        var isLongPress = false
        val longPressJob =
            scope.launch {
                delay(longPressTimeout)
                onLongPress()
                isLongPress = true
            }
        var lastProgress = 0f
        drag(down.id) { change ->
            if (change.pressed) {
                longPressJob.cancel()
                if (!isLongPress) {
                    change.consume()
                    val height = size.height
                    val newProgress = (1f - (change.position.y / height)).coerceIn(0f, 1f)
                    lastProgress = newProgress
                    onValueChanged(newProgress)
                }
            }
        }
        longPressJob.cancel()
        if (!isLongPress) {
            onValueChangeFinished(lastProgress)
        }
    }
}

@Composable
private fun AlwaysDarkMode(content: @Composable () -> Unit) {
    if (notificationShadeBlur()) {
        content()
    } else {
        val currentConfig = LocalConfiguration.current
        val darkConfig =
            Configuration(currentConfig).apply {
                uiMode =
                    (uiMode and (Configuration.UI_MODE_NIGHT_MASK.inv())) or
                        Configuration.UI_MODE_NIGHT_YES
            }
        val newContext = LocalContext.current.createConfigurationContext(darkConfig)
        CompositionLocalProvider(
            LocalConfiguration provides darkConfig,
            LocalContext provides newContext,
        ) {
            content()
        }
    }
}
