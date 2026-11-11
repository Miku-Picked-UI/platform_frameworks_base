/*
 * Copyright (C) 2026 The PenguinOS Project
 * SPDX-License-Identifier: Apache-2.0
 *
 * Miku-Picked UI: pieces of PenguinOS' Quick Settings (StyledQuickSettingsContent.kt,
 * PanelElements.kt) the Control Centre needs, and Android 17 names the ported code uses, mapped
 * onto this Android 16 tree.
 */

package com.android.systemui.qs.ui.composable

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.android.compose.animation.scene.ContentScope
import com.android.compose.animation.scene.ElementKey
import com.android.compose.animation.scene.content.state.TransitionState
import com.android.systemui.qs.panels.ui.viewmodel.TileGridViewModel
import com.android.systemui.qs.panels.ui.viewmodel.TileUiState
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.shared.ui.QuickSettings
import com.android.systemui.qs.ui.viewmodel.QuickSettingsContainerViewModel

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
                it.fromContent == key && it.toContent != key
            }
        QsHostTransition(
            idle = { state.transitionState !is TransitionState.Transition },
            leaving = { leavingTransition() != null },
            leftProgress = { leavingTransition()?.progress?.coerceIn(0f, 1f) ?: 0f },
        )
    }
}

/** Landscape fit of the wide Control Centre column (PenguinOS' shade overlay provides it). */
internal class QsWideFit {
    var bodyPx: Int = 0
    val availableBodyPx: Int?
        get() = bodyPx.takeIf { it > 0 }
}

internal val LocalQsWideFit = compositionLocalOf<QsWideFit?> { null }

// Android 17 names, on this tree.

/** Android 17's TileUiState.visualState is this tree's state. */
val TileUiState.visualState: Int
    get() = state

/** Android 17's TileUiState.handlesToggleClick: the tile has a separate toggle target. */
val TileUiState.handlesToggleClick: Boolean
    get() = handlesSecondaryClick

/** Android 17's QuickSettingsContainerViewModel.hasMediaCards. */
val QuickSettingsContainerViewModel.hasMediaCards: Boolean
    get() = showMedia

/** The tiles shown large (QSFragmentCompose provides them from IconTilesViewModel). */
val LocalQsLargeTiles = compositionLocalOf { emptySet<TileSpec>() }

/** Android 17's TileGridViewModel.largeTiles. */
@Suppress("UnusedReceiverParameter")
val TileGridViewModel.largeTiles: Set<TileSpec>
    @Composable get() = LocalQsLargeTiles.current

private val ConnectivityFolderKey = ElementKey("ConnectivityFolder")

/** Android 17's QuickSettings.Elements.ConnectivityFolder. */
val QuickSettings.Elements.ConnectivityFolder: ElementKey
    get() = ConnectivityFolderKey
