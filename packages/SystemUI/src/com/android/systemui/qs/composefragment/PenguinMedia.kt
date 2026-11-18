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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import androidx.compose.foundation.Canvas
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextStyle
import com.android.systemui.penguin.CosFeature
import com.android.systemui.penguin.rememberCosFeature
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import com.android.compose.ui.graphics.painter.rememberDrawablePainter
import com.android.compose.modifiers.thenIf
import com.android.systemui.common.shared.model.Icon as CommonIcon
import com.android.systemui.common.ui.compose.Icon
import androidx.compose.material3.Icon as M3Icon
import com.android.systemui.lifecycle.rememberViewModel
import com.android.systemui.media.remedia.ui.compose.MediaUiBehavior
import com.android.systemui.media.remedia.ui.viewmodel.MediaCardViewModel
import com.android.systemui.media.remedia.ui.viewmodel.MediaNavigationViewModel
import com.android.systemui.media.remedia.ui.viewmodel.MediaSecondaryActionViewModel
import com.android.systemui.media.remedia.ui.viewmodel.MediaViewModel
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.widthIn
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.android.compose.animation.Expandable
import com.android.compose.animation.rememberExpandableController
import com.android.systemui.animation.Expandable
import com.android.systemui.media.remedia.shared.model.MediaCardActionButtonLayout
import com.android.systemui.media.remedia.shared.model.MediaSessionState
import com.android.systemui.res.R
import com.android.systemui.media.remedia.ui.viewmodel.MediaDeviceChipViewModel
import com.android.systemui.qs.shared.style.glassPress
import com.android.systemui.qs.shared.style.liquidGlassOn
import com.android.systemui.qs.shared.style.glassRim
import kotlin.math.PI
import kotlin.math.sin

private val ArtworkCorner = 28.dp
private val LogoSize = 44.dp
private val ArtSize = 52.dp
private val LargeArtSize = 112.dp
private val WideHeight = 116.dp
private val TrackHeight = 4.dp
private val RingStroke = 3.dp
private val ControlSize = 40.dp
private val PageDotSize = 5.dp
private val PageGap = 12.dp
private val ScrubTouchHeight = 24.dp
private val ExpandedCorner = 36.dp
private val ExpandedPadding = 20.dp
private val ExpandedPlaySize = 64.dp

private val LocalMediaCardInteractive = compositionLocalOf { true }

private val LocalMediaCardExpand = compositionLocalOf<(() -> Unit)?> { null }

@Composable
fun PenguinMediaCard(
    viewModelFactory: MediaViewModel.Factory,
    behavior: MediaUiBehavior,
    modifier: Modifier = Modifier,
    square: Boolean = true,
    interactive: Boolean = true,
    large: Boolean = false,
    height: Dp? = null,
    onExpand: (() -> Unit)? = null,
    controlCentre: Boolean = false,
    expanded: Boolean = false,
    tall: Boolean = false,
) {
    CompositionLocalProvider(
        LocalMediaCardInteractive provides interactive,
        LocalMediaCardExpand provides onExpand,
    ) {
        PenguinMediaCardContent(
            viewModelFactory,
            behavior,
            modifier,
            square,
            large,
            height,
            controlCentre,
            expanded,
            tall,
        )
    }
}

@Composable
private fun PenguinMediaCardContent(
    viewModelFactory: MediaViewModel.Factory,
    behavior: MediaUiBehavior,
    modifier: Modifier,
    square: Boolean,
    large: Boolean,
    height: Dp?,
    controlCentre: Boolean,
    expanded: Boolean,
    tall: Boolean,
) {
    val context = LocalContext.current
    val viewModel =
        rememberViewModel("PenguinMediaCard") {
            viewModelFactory.create(
                context = context,
                carouselVisibility = behavior.carouselVisibility,
            )
        }
    val cards = viewModel.cards
    if (cards.isEmpty()) return
    val pagerState = rememberPagerState(pageCount = { cards.size })
    LaunchedEffect(pagerState, cards.size) {
        snapshotFlow { pagerState.settledPage }
            .collect { if (it < cards.size) viewModel.onCardSelected(it) }
    }
    HorizontalPager(state = pagerState, pageSpacing = PageGap, modifier = modifier) { page ->
        val card = cards[page]
        val dots: @Composable () -> Unit = { PageDots(cards.size, pagerState.currentPage) }
        val seekColor = rememberCosMediaColor((card.background as? CommonIcon.Loaded)?.drawable)
        CompositionLocalProvider(LocalCosSeekColor provides seekColor) {
        when {
            expanded -> ExpandedMediaPlayer(card, dots)
            tall -> TallMediaCard(card, card.navigation, height ?: qsModuleHeight(6), dots)
            large -> LargeMediaCard(card, card.navigation, height ?: qsModuleHeight(4), dots)
            square -> SquareMediaCard(card, card.navigation, dots, height)
            controlCentre ->
                ControlCentreWideMediaCard(card, card.navigation, height ?: qsModuleHeight(2), dots)
            else -> WideMediaCard(card, card.navigation, Modifier, dots, height)
        }
        }
    }
}

@Composable
private fun SquareMediaCard(
    card: MediaCardViewModel,
    navigation: MediaNavigationViewModel,
    dots: @Composable () -> Unit,
    height: Dp? = null,
) {
    val moduleHeight = height ?: qsModuleHeight(2)
    val cos = rememberCosFeature(CosFeature.QuickSettings)

    MediaCardContainer(card, RoundedCornerShape(ArtworkCorner)) {
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .height(moduleHeight)
                .then(
                    if (cos) Modifier.cosGlass(RoundedCornerShape(ArtworkCorner))
                    else Modifier.clip(RoundedCornerShape(ArtworkCorner)).background(glassSurface())
                )
                .cosMediaLight(
                    (card.background as? CommonIcon.Loaded)?.drawable,
                    ArtworkCorner,
                    with(LocalDensity.current) { Offset((14.dp + ArtSize / 2).toPx(), (14.dp + ArtSize / 2).toPx()) },
                )
                .then(if (cos) Modifier else Modifier.glassRim(RoundedCornerShape(ArtworkCorner)))
                .padding(14.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Artwork(card.background, Modifier.size(ArtSize).clip(RoundedCornerShape(10.dp)))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                dots()
                OutputButton(card)
            }
        }
        Column {
            Text(
                text = card.title,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = card.subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MediaControl(
                navigation.left,
                ControlSize,
                iconRes = R.drawable.ic_penguin_media_previous,
            )
            card.playPauseAction?.let { play ->
                play.icon?.let { icon ->
                    MediaControl(
                        MediaSecondaryActionViewModel.Action(icon, play.onClick),
                        ControlSize + 6.dp,
                        iconRes = playPauseIcon(play.state),
                    )
                }
            }
            MediaControl(
                navigation.right,
                ControlSize,
                iconRes = R.drawable.ic_penguin_media_next,
            )
        }
    }
}
}

@Composable
private fun WideMediaCard(
    card: MediaCardViewModel,
    navigation: MediaNavigationViewModel,
    modifier: Modifier,
    dots: @Composable () -> Unit,
    height: Dp? = null,
) {
    val progress =
        (navigation as? MediaNavigationViewModel.Showing)?.progress?.coerceIn(0f, 1f) ?: 0f
    MediaCardContainer(card, RoundedCornerShape(ArtworkCorner)) {
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(height ?: WideHeight)
                .clip(RoundedCornerShape(ArtworkCorner))
    ) {
        Artwork(card.background, Modifier.fillMaxSize())
        Box(
            Modifier.fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        listOf(Color.Black.copy(alpha = 0.65f), Color.Black.copy(alpha = 0.35f))
                    )
                )
        )
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(
                    text = card.title,
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = card.subtitle,
                    color = Color.White.copy(alpha = 0.75f),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.weight(1f).height(TrackHeight).clip(CircleShape)) {
                    Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.3f)))
                    Box(
                        Modifier.fillMaxWidth(progress)
                            .fillMaxHeight()
                            .clip(CircleShape)
                            .background(Color.White)
                    )
                }
                card.playPauseAction?.let { play ->
                    play.icon?.let {
                        MediaControl(
                            MediaSecondaryActionViewModel.Action(it, play.onClick),
                            ControlSize,
                            Color.White,
                            playPauseIcon(play.state),
                        )
                    }
                }
                MediaControl(
                    navigation.right,
                    ControlSize,
                    Color.White,
                    R.drawable.ic_penguin_media_next,
                )
            }
        }
        Box(Modifier.align(Alignment.TopEnd).padding(horizontal = 16.dp, vertical = 12.dp)) {
            dots()
        }
    }
}
}

@Composable
private fun LargeMediaCard(
    card: MediaCardViewModel,
    navigation: MediaNavigationViewModel,
    height: Dp,
    dots: @Composable () -> Unit,
) {
    MediaCardContainer(card, RoundedCornerShape(ArtworkCorner)) {
        Column(
            modifier =
                Modifier.fillMaxWidth()
                    .height(height)
                    .clip(RoundedCornerShape(ArtworkCorner))
                    .background(glassSurface())
                    .glassRim(RoundedCornerShape(ArtworkCorner))
                    .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Artwork(card.background, Modifier.size(ArtSize + 20.dp).clip(RoundedCornerShape(10.dp)))
                Box(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                    TrackText(card, MaterialTheme.typography.titleMedium)
                }
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Waveform(card)
                    dots()
                }
            }
            ScrubBar(navigation)
            Transport(card, navigation, ControlSize + 8.dp, ControlSize + 16.dp)
            MusicVolumeSlider()
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                OutputPill(card)
            }
        }
    }
}

@Composable
private fun ControlCentreWideMediaCard(
    card: MediaCardViewModel,
    navigation: MediaNavigationViewModel,
    height: Dp,
    dots: @Composable () -> Unit,
) {
    MediaCardContainer(card, RoundedCornerShape(ArtworkCorner)) {
        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .height(height)
                    .clip(RoundedCornerShape(ArtworkCorner))
                    .background(glassSurface())
                    .glassRim(RoundedCornerShape(ArtworkCorner))
                    .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Artwork(
                card.background,
                Modifier.fillMaxHeight().aspectRatio(1f).clip(RoundedCornerShape(12.dp)),
            )
            Column(
                modifier = Modifier.weight(1f).fillMaxHeight().padding(start = 14.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.Top) {
                    Box(Modifier.weight(1f).padding(end = 8.dp)) {
                        TrackText(card, MaterialTheme.typography.titleSmall)
                    }
                    Column(
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        OutputButton(card)
                        dots()
                    }
                }
                Transport(card, navigation, ControlSize, ControlSize + 4.dp)
                ScrubBar(navigation, compact = true)
            }
        }
    }
}

@Composable
private fun TallMediaCard(
    card: MediaCardViewModel,
    navigation: MediaNavigationViewModel,
    height: Dp,
    dots: @Composable () -> Unit,
) {
    MediaCardContainer(card, RoundedCornerShape(ArtworkCorner)) {
        Column(
            modifier =
                Modifier.fillMaxWidth()
                    .height(height)
                    .clip(RoundedCornerShape(ArtworkCorner))
                    .background(glassSurface())
                    .glassRim(RoundedCornerShape(ArtworkCorner))
                    .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Artwork(
                    card.background,
                    Modifier.fillMaxHeight().aspectRatio(1f, matchHeightConstraintsFirst = true)
                        .clip(RoundedCornerShape(14.dp)),
                )
                Box(Modifier.align(Alignment.TopEnd)) { dots() }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = card.outputSwitcherChip.text?.toString() ?: card.title,
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text =
                            if (card.outputSwitcherChip.text != null) {
                                "${card.title} — ${card.subtitle}"
                            } else {
                                card.subtitle
                            },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Waveform(card)
            }
            ScrubBar(navigation, inline = true)
            Transport(card, navigation, ControlSize + 8.dp, ControlSize + 16.dp)
            MusicVolumeSlider()
        }
    }
}

@Composable
private fun ExpandedMediaPlayer(card: MediaCardViewModel, dots: @Composable () -> Unit) {
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(ExpandedCorner))
                .background(glassSurface())
                .glassRim(RoundedCornerShape(ExpandedCorner))
                .padding(ExpandedPadding),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box {
            Artwork(
                card.background,
                Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(14.dp)),
            )
            Box(Modifier.align(Alignment.TopEnd).padding(10.dp)) { dots() }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { TrackText(card, MaterialTheme.typography.titleMedium) }
            Waveform(card)
        }
        ScrubBar(card.navigation)
        Transport(card, card.navigation, ControlSize + 8.dp, ExpandedPlaySize)
        MusicVolumeSlider()
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { OutputPill(card) }
    }
}

@Composable
private fun OutputButton(card: MediaCardViewModel) {
    val action = card.outputSwitcherChipButton as? MediaSecondaryActionViewModel.Action ?: return
    Box(
        Modifier.size(ControlSize + 4.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center,
    ) {
        MediaControl(action, ControlSize)
    }
}

@Composable
private fun OutputPill(card: MediaCardViewModel) {
    OutputChip(
        card.outputSwitcherChip,
        background = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
        foreground = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun mediaWavePhase(animated: Boolean, durationMillis: Int, label: String): State<Float> {
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    if (!animated || !lifecycleState.isAtLeast(Lifecycle.State.STARTED)) {
        return rememberUpdatedState(0f)
    }
    // Keep the state read inside Canvas; reading the phase here would recompose the
    // media card every frame instead of only invalidating its drawing.
    return rememberInfiniteTransition(label = label)
            .animateFloat(
                initialValue = 0f,
                targetValue = (2 * PI).toFloat(),
                animationSpec = infiniteRepeatable(tween(durationMillis, easing = LinearEasing)),
                label = "$label.phase",
            )
}

@Composable
private fun Waveform(card: MediaCardViewModel) {
    val playing = card.playPauseAction?.state == MediaSessionState.Playing
    val phase by mediaWavePhase(playing, 1100, "Waveform")
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(Modifier.size(width = 30.dp, height = 20.dp)) {
        val bars = 6
        val gap = size.width / bars
        val stroke = gap * 0.45f
        for (i in 0 until bars) {
            val level =
                if (playing) 0.35f + 0.65f * ((sin(phase * (1f + i * 0.37f) + i * 1.3f) + 1f) / 2f)
                else 0.2f
            val h = size.height * level
            val x = gap * i + gap / 2
            drawLine(
                color,
                start = Offset(x, (size.height - h) / 2),
                end = Offset(x, (size.height + h) / 2),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun TrackText(card: MediaCardViewModel, titleStyle: TextStyle) {
    val tileTitle = MaterialTheme.typography.titleSmallEmphasized
    val tileLabel = MaterialTheme.typography.labelMedium
    Column {
        Text(
            text = card.title,
            color = MaterialTheme.colorScheme.onSurface,
            style =
                if (liquidGlassOn) {
                    titleStyle.copy(
                        fontFamily = tileTitle.fontFamily,
                        fontWeight = tileTitle.fontWeight,
                    )
                } else {
                    titleStyle
                },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = card.subtitle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style =
                if (liquidGlassOn) {
                    MaterialTheme.typography.bodyMedium.copy(fontFamily = tileLabel.fontFamily)
                } else {
                    MaterialTheme.typography.bodyMedium
                },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun Transport(
    card: MediaCardViewModel,
    navigation: MediaNavigationViewModel,
    size: Dp,
    playSize: Dp,
    filledPlay: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MediaControl(navigation.left, size, iconRes = R.drawable.ic_penguin_media_previous)
        card.playPauseAction?.let { play ->
            play.icon?.let { icon ->
                val action = MediaSecondaryActionViewModel.Action(icon, play.onClick)
                if (filledPlay) {
                    Box(
                        Modifier.size(playSize)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.onSurface),
                        contentAlignment = Alignment.Center,
                    ) {
                        MediaControl(
                            action,
                            playSize,
                            MaterialTheme.colorScheme.surface,
                            playPauseIcon(play.state),
                        )
                    }
                } else {
                    MediaControl(action, playSize, iconRes = playPauseIcon(play.state))
                }
            }
        }
        MediaControl(navigation.right, size, iconRes = R.drawable.ic_penguin_media_next)
    }
}

@Composable
private fun ScrubBar(
    navigation: MediaNavigationViewModel,
    compact: Boolean = false,
    inline: Boolean = false,
) {
    val showing = navigation as? MediaNavigationViewModel.Showing ?: return
    val currentShowing by rememberUpdatedState(showing)
    val interactive = LocalMediaCardInteractive.current
    var touching by remember { mutableStateOf(false) }
    val rest = if (compact) 4.dp else 6.dp
    val thickness by animateDpAsState(if (touching) 10.dp else rest, label = "ScrubBar")
    val scrubbable = interactive && showing.onScrubChange != null
    val progressText: @Composable () -> Unit = {
        Text(
            text = showing.progressText,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
        )
    }
    val durationText: @Composable () -> Unit = {
        Text(
            text = showing.durationText,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
        )
    }
    val bar: @Composable (Modifier) -> Unit = { barModifier ->
        Box(
            modifier =
                barModifier
                    .height(if (compact) 16.dp else ScrubTouchHeight)
                    .cosSeekGlow(showing.progress, showing.isSquiggly, touching || showing.isScrubbing)
                    .semantics { contentDescription = showing.contentDescription }
                    .thenIf(scrubbable) {
                        Modifier.pointerInput(Unit) {
                                detectTapGestures(
                                    onPress = {
                                        touching = true
                                        tryAwaitRelease()
                                        touching = false
                                    },
                                    onTap = {
                                        currentShowing.onScrubChange?.invoke(
                                            (it.x / size.width).coerceIn(0f, 1f)
                                        )
                                        currentShowing.onScrubFinished?.invoke(Offset.Zero)
                                    },
                                )
                            }
                            .pointerInput(Unit) {
                                var total = Offset.Zero
                                detectHorizontalDragGestures(
                                    onDragStart = {
                                        total = Offset.Zero
                                        touching = true
                                    },
                                    onDragEnd = {
                                        touching = false
                                        currentShowing.onScrubFinished?.invoke(total)
                                    },
                                    onDragCancel = {
                                        touching = false
                                        currentShowing.onScrubFinished?.invoke(Offset.Zero)
                                    },
                                ) { change, dragAmount ->
                                    total += Offset(dragAmount, 0f)
                                    currentShowing.onScrubChange?.invoke(
                                        (change.position.x / size.width).coerceIn(0f, 1f)
                                    )
                                }
                            }
                    },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.fillMaxWidth().height(thickness).clip(CircleShape)) {
                Box(
                    Modifier.fillMaxSize()
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f))
                )
                Box(
                    Modifier.fillMaxWidth(showing.progress.coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.onSurface)
                )
            }
        }
    }
    if (inline) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            progressText()
            bar(Modifier.weight(1f))
            durationText()
        }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(if (compact) 0.dp else 4.dp)) {
        bar(Modifier.fillMaxWidth())
        Row(modifier = Modifier.fillMaxWidth()) {
            progressText()
            Spacer(Modifier.weight(1f))
            durationText()
        }
    }
}

@Composable
private fun MusicVolumeSlider() {
    val context = LocalContext.current
    val audioManager = remember(context) { context.getSystemService(AudioManager::class.java) }
    val max = remember(audioManager) {
        audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    }
    var volume by remember { mutableIntStateOf(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)) }
    DisposableEffect(context, audioManager) {
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    volume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                }
            }
        context.registerReceiver(
            receiver,
            IntentFilter(AudioManager.VOLUME_CHANGED_ACTION),
            Context.RECEIVER_NOT_EXPORTED,
        )
        onDispose { context.unregisterReceiver(receiver) }
    }
    val interactive = LocalMediaCardInteractive.current
    fun setFrom(x: Float, width: Int) {
        val target = ((x / width).coerceIn(0f, 1f) * max).roundToInt()
        if (target != volume) {
            volume = target
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        M3Icon(
            imageVector = Icons.AutoMirrored.Rounded.VolumeDown,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Box(
            modifier =
                Modifier.weight(1f)
                    .height(ScrubTouchHeight)
                    .padding(horizontal = 10.dp)
                    .thenIf(interactive) {
                        Modifier.pointerInput(max) {
                                detectTapGestures { setFrom(it.x, size.width) }
                            }
                            .pointerInput(max) {
                                detectHorizontalDragGestures { change, _ ->
                                    setFrom(change.position.x, size.width)
                                }
                            }
                    },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.fillMaxWidth().height(8.dp).clip(CircleShape)) {
                Box(
                    Modifier.fillMaxSize()
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f))
                )
                Box(
                    Modifier.fillMaxWidth(volume.toFloat() / max)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.onSurface)
                )
            }
        }
        M3Icon(
            imageVector = Icons.AutoMirrored.Rounded.VolumeUp,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun PageDots(count: Int, current: Int) {
    if (count < 2) return
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { index ->
            Box(
                Modifier.size(PageDotSize)
                    .clip(CircleShape)
                    .background(
                        MaterialTheme.colorScheme.onSurface.copy(
                            alpha = if (index == current) 0.9f else 0.3f
                        )
                    )
            )
        }
    }
}

@Composable
private fun Artwork(artwork: CommonIcon?, modifier: Modifier) {
    when (artwork) {
        is CommonIcon.Loaded ->
            Image(
                painter = rememberDrawablePainter(artwork.drawable),
                contentDescription = null,
                modifier = modifier,
                contentScale = ContentScale.Crop,
            )
        is CommonIcon.Resource ->
            Image(
                painter = painterResource(artwork.resId),
                contentDescription = null,
                modifier = modifier,
                contentScale = ContentScale.Crop,
            )
        null -> Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant))
    }
}

@Composable
private fun AppLogoWithProgress(icon: CommonIcon, progress: Float) {
    Box(contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(LogoSize + RingStroke * 4)) {
            val stroke = RingStroke.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(
                color = Color.White.copy(alpha = 0.25f),
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke),
            )
            drawArc(
                color = Color.White,
                startAngle = -90f,
                sweepAngle = 360f * progress,
                useCenter = false,
                topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke),
            )
        }
        Box(
            modifier =
                Modifier.size(LogoSize)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon = icon, tint = Color.White, modifier = Modifier.size(LogoSize / 2))
        }
    }
}

@Composable
private fun MediaCardContainer(
    card: MediaCardViewModel,
    shape: Shape,
    content: @Composable () -> Unit,
) {
    if (!LocalMediaCardInteractive.current) {
        content()
        return
    }
    val expand = LocalMediaCardExpand.current
    if (expand != null) {
        Box(Modifier.clickable(onClick = expand)) { content() }
        return
    }
    Expandable(
        controller = rememberExpandableController(color = Color.Transparent, shape = shape),
        useModifierBasedImplementation = true,
        defaultMinSize = false,
        onClick = { card.onClick(it) },
    ) {
        content()
    }
}

@Composable
private fun MediaControl(
    action: MediaSecondaryActionViewModel,
    size: Dp = ControlSize,
    tint: Color? = null,
    iconRes: Int? = null,
) {
    val model = action as? MediaSecondaryActionViewModel.Action ?: return
    val iconRes =
        if (liquidGlassOn) {
            when (iconRes) {
                R.drawable.ic_penguin_media_previous -> R.drawable.ic_penguin_media_previous
                R.drawable.ic_penguin_media_next -> R.drawable.ic_penguin_media_next
                R.drawable.ic_penguin_media_play -> R.drawable.ic_penguin_media_play
                R.drawable.ic_penguin_media_pause -> R.drawable.ic_penguin_media_pause
                else -> iconRes
            }
        } else {
            iconRes
        }
    val interactive = LocalMediaCardInteractive.current
    Box(
        modifier =
            Modifier.size(size)
                .clip(CircleShape)
                .then(
                    model.onClick
                        ?.takeIf { interactive }
                        ?.let { Modifier.clickable(onClick = it) } ?: Modifier
                ),
        contentAlignment = Alignment.Center,
    ) {
        if (iconRes == null) {
            Icon(
                icon = model.icon,
                tint = tint ?: if (liquidGlassOn) Color.White else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(size * 0.6f),
            )
        } else {
            M3Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = tint ?: if (liquidGlassOn) Color.White else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(size * 0.6f),
            )
        }
    }
}

private fun playPauseIcon(state: MediaSessionState) =
    if (state == MediaSessionState.Playing) {
        R.drawable.ic_penguin_media_pause
    } else {
        R.drawable.ic_penguin_media_play
    }

private val MyUiMediaCorner = 32.dp
private val MyUiControlSize = 36.dp
internal val MyUiMinHeight = 100.dp

@Composable
fun MyUiMediaCard(
    viewModelFactory: MediaViewModel.Factory,
    behavior: MediaUiBehavior,
    modifier: Modifier = Modifier,
    interactive: Boolean = true,
) {
    CompositionLocalProvider(LocalMediaCardInteractive provides interactive) {
        MyUiMediaCardContent(viewModelFactory, behavior, modifier)
    }
}

@Composable
private fun MyUiMediaCardContent(
    viewModelFactory: MediaViewModel.Factory,
    behavior: MediaUiBehavior,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val viewModel =
        rememberViewModel("MyUiMediaCard") {
            viewModelFactory.create(
                context = context,
                carouselVisibility = behavior.carouselVisibility,
            )
        }
    val cards = viewModel.cards
    if (cards.isEmpty()) return
    val pagerState = rememberPagerState(pageCount = { cards.size })
    LaunchedEffect(pagerState, cards.size) {
        snapshotFlow { pagerState.settledPage }
            .collect { if (it < cards.size) viewModel.onCardSelected(it) }
    }
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val height = maxOf(maxWidth / 4, MyUiMinHeight)
        HorizontalPager(state = pagerState, pageSpacing = PageGap) { page ->
            MyUiMediaPage(cards[page], height)
        }
    }
}

@Composable
private fun MyUiMediaPage(card: MediaCardViewModel, height: Dp) {
    MediaCardContainer(card, RoundedCornerShape(MyUiMediaCorner)) {
    Box(
        modifier =
            Modifier.fillMaxWidth()
                .height(height)
                .clip(RoundedCornerShape(MyUiMediaCorner))
                .background(Color.Black)
    ) {
        Artwork(card.background, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)))
        Column(
            modifier =
                Modifier.fillMaxSize().padding(start = 20.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            icon = card.icon,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = card.title,
                            color = Color.White,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                    Text(
                        text = card.subtitle,
                        color = Color.White.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                OutputChip(card.outputSwitcherChip, Color.White, Color.Black)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MyUiSeekBar(card.navigation, Modifier.weight(1f))
                myUiControls(card).forEach { (action, iconRes) ->
                    MediaControl(action, MyUiControlSize, Color.White, iconRes)
                }
            }
        }
    }
}
}

private fun myUiControls(
    card: MediaCardViewModel
): List<Pair<MediaSecondaryActionViewModel.Action, Int?>> {
    val extras = card.additionalActions.filterIsInstance<MediaSecondaryActionViewModel.Action>()
    if (card.actionButtonLayout != MediaCardActionButtonLayout.WithPlayPause) {
        return extras.map { it to null }
    }
    val play =
        card.playPauseAction?.let { action ->
            action.icon?.let {
                MediaSecondaryActionViewModel.Action(it, action.onClick) to
                    playPauseIcon(action.state)
            }
        }
    return listOfNotNull(
        extras.getOrNull(0)?.let { it to null },
        (card.navigation.left as? MediaSecondaryActionViewModel.Action)?.let {
            it to R.drawable.ic_penguin_media_previous
        },
        play,
        (card.navigation.right as? MediaSecondaryActionViewModel.Action)?.let {
            it to R.drawable.ic_penguin_media_next
        },
        extras.getOrNull(1)?.let { it to null },
    )
}

@Composable
private fun OutputChip(chip: MediaDeviceChipViewModel, background: Color, foreground: Color) {
    val interactive = LocalMediaCardInteractive.current
    Expandable(
        controller =
            rememberExpandableController(color = background, shape = RoundedCornerShape(50)),
        useModifierBasedImplementation = false,
        defaultMinSize = false,
        modifier = Modifier.padding(start = 8.dp),
    ) { expandable ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier.clip(RoundedCornerShape(50))
                    .background(background)
                    .thenIf(interactive) {
                        Modifier.clickable { chip.onClick(expandable) }
                    }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
        ) {
            Icon(icon = chip.icon, tint = foreground, modifier = Modifier.size(14.dp))
            chip.text?.let { text ->
                Text(
                    text = text.toString(),
                    color = foreground,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 6.dp).widthIn(max = 96.dp),
                )
            }
        }
    }
}

@Composable
private fun MyUiSeekBar(navigation: MediaNavigationViewModel, modifier: Modifier) {
    val showing = navigation as? MediaNavigationViewModel.Showing
    if (showing == null) {
        Spacer(modifier)
        return
    }
    val progress = showing.progress.coerceIn(0f, 1f)
    val amplitude by
        animateDpAsState(
            targetValue = if (showing.isSquiggly) 2.dp else 0.dp,
            label = "MyUiSeekBar.amplitude",
        )
    val phase by mediaWavePhase(showing.isSquiggly || amplitude.value > 0f, 1500,
                               "MyUiSeekBar.phase")
    val currentShowing by rememberUpdatedState(showing)
    val interactive = LocalMediaCardInteractive.current
    Canvas(
        modifier =
            modifier
                .height(MyUiControlSize)
                .padding(end = 8.dp)
                .semantics { contentDescription = showing.contentDescription }
                .thenIf(interactive && showing.onScrubChange != null) {
                    Modifier.pointerInput(Unit) {
                        var total = Offset.Zero
                        detectHorizontalDragGestures(
                            onDragStart = { total = Offset.Zero },
                            onDragEnd = {
                                currentShowing.onScrubFinished?.invoke(total)
                            },
                            onDragCancel = {
                                currentShowing.onScrubFinished?.invoke(Offset.Zero)
                            },
                        ) { change, dragAmount ->
                            total += Offset(dragAmount, 0f)
                            currentShowing.onScrubChange?.invoke(
                                (change.position.x / size.width).coerceIn(0f, 1f)
                            )
                        }
                    }
                }
    ) {
        val y = size.height / 2
        val stroke = 2.dp.toPx()
        val thumbX = size.width * progress
        val amp = amplitude.toPx()
        val wavelength = 12.dp.toPx()
        val wave = Path()
        wave.moveTo(0f, y)
        var x = 0f
        while (x < thumbX) {
            x = minOf(x + 1f, thumbX)
            wave.lineTo(x, y + amp * sin(x / wavelength * 2 * PI.toFloat() - phase))
        }
        drawPath(wave, Color.White, style = Stroke(width = stroke, cap = StrokeCap.Round))
        drawLine(
            Color.White.copy(alpha = 0.3f),
            start = Offset(thumbX, y),
            end = Offset(size.width, y),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawCircle(Color.White, radius = 3.dp.toPx(), center = Offset(thumbX, y))
    }
}

@Composable
internal fun ControlCentreNotPlaying(height: Dp) {
    val glass = liquidGlassOn
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .height(height)
                .clip(RoundedCornerShape(ArtworkCorner))
                .background(glassSurface())
                .glassRim(RoundedCornerShape(ArtworkCorner))
                .padding(14.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                Modifier.size(ArtSize)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
            )
            Box(
                Modifier.size(ControlSize + 4.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                M3Icon(
                    painter = painterResource(R.drawable.ic_cast),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Text(
            text = stringResource(R.string.penguin_media_not_playing),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
            NotPlayingGlyph(
                if (glass) R.drawable.ic_penguin_media_previous else R.drawable.ic_penguin_media_previous,
                ControlSize,
                dim,
            )
            NotPlayingGlyph(
                if (glass) R.drawable.ic_penguin_media_play else R.drawable.ic_penguin_media_play,
                ControlSize + 6.dp,
                MaterialTheme.colorScheme.onSurface,
            )
            NotPlayingGlyph(
                if (glass) R.drawable.ic_penguin_media_next else R.drawable.ic_penguin_media_next,
                ControlSize,
                dim,
            )
        }
    }
}

@Composable
private fun NotPlayingGlyph(iconRes: Int, size: Dp, tint: Color) {
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        M3Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(size * 0.6f),
        )
    }
}

// Miku-Picked UI: Android 17's MediaNavigationViewModel, on this tree's.

/** The button to the left of the seek bar, whether or not the seek bar shows. */
private val MediaNavigationViewModel.left: MediaSecondaryActionViewModel
    get() = (this as? MediaNavigationViewModel.Showing)?.left ?: MediaSecondaryActionViewModel.None

/** The button to the right of the seek bar, whether or not the seek bar shows. */
private val MediaNavigationViewModel.right: MediaSecondaryActionViewModel
    get() = (this as? MediaNavigationViewModel.Showing)?.right ?: MediaSecondaryActionViewModel.None

/** This tree's media model has no position texts. */
private val MediaNavigationViewModel.Showing.progressText: String
    get() = ""

private val MediaNavigationViewModel.Showing.durationText: String
    get() = ""
