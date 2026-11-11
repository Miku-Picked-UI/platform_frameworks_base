/*
 * Copyright (C) 2026 The PenguinOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.shared.style

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import com.android.systemui.qs.composefragment.ConnectivityFolderExpansion
import com.android.systemui.qs.ui.viewmodel.QuickSettingsContainerViewModel
import com.android.systemui.shade.ui.viewmodel.ShadeSceneContentViewModel

object PenguinQsStyle {
    lateinit var repository: QsPanelStyleRepository
}

val QuickSettingsContainerViewModel.panelStyle: QsPanelStyle
    @Composable get() = PenguinQsStyle.repository.style.collectAsState().value

val ShadeSceneContentViewModel.panelStyle: QsPanelStyle
    @Composable get() = PenguinQsStyle.repository.style.collectAsState().value

fun ShadeSceneContentViewModel.onConnectivityFolderExpandRequested() {
    ConnectivityFolderExpansion.expanded = true
    expandQuickSettings("Connectivity folder expanded from QQS.")
}
