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

package com.android.systemui.volume.dialog.domain.interactor

import android.content.Context
import com.android.systemui.penguin.VolumePanelLook
import com.android.systemui.penguin.VolumePanelLooks
import com.android.systemui.volume.dialog.dagger.scope.VolumeDialogScope
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@VolumeDialogScope
class VolumeDialogExpansionInteractor
@Inject
constructor(
    context: Context,
    desktopAudioTileDetailsFeatureInteractor: DesktopAudioTileDetailsFeatureInteractor,
    looks: VolumePanelLooks,
) {

    val look: VolumePanelLook? =
        if (desktopAudioTileDetailsFeatureInteractor.isEnabled()) {
            null
        } else {
            looks.current(context)
        }

    private val mutableIsExpanded = MutableStateFlow(false)

    val isExpanded: StateFlow<Boolean> = mutableIsExpanded.asStateFlow()

    fun toggle() {
        if (look != null) {
            mutableIsExpanded.value = !mutableIsExpanded.value
        }
    }
}
