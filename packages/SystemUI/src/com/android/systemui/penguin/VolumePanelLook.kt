/*
 * Copyright (C) 2026 The PenguinOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.penguin

import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import androidx.annotation.ColorRes
import androidx.annotation.LayoutRes
import androidx.compose.runtime.Composable
import com.android.systemui.haptics.slider.compose.ui.SliderHapticsViewModel
import com.android.systemui.volume.dialog.sliders.ui.viewmodel.VolumeDialogOverscrollViewModel
import com.android.systemui.volume.dialog.sliders.ui.viewmodel.VolumeDialogSliderViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

interface VolumePanelLooks {
    fun current(context: Context): VolumePanelLook?
}

interface VolumePanelLook {
    val streams: Set<Int>

    @get:LayoutRes val cardLayout: Int?

    @get:LayoutRes val floatingSliderLayout: Int

    val ringerColors: RingerColors?

    fun CoroutineScope.bindPanel(view: View, host: PanelHost)

    fun onFloatingSliderAdded(container: View) {}

    fun bindCaptionsButton(button: ImageView) {}

    @ColorRes fun captionsIconColor(isEnabled: Boolean): Int?

    fun bindSettingsButton(button: ImageButton): Boolean = false

    fun settingsIntent(): Intent? = null

    @Composable fun Slider(slider: SliderHost)

    interface PanelHost {
        val mainSliderContainer: View
        val floatingSlidersContainer: ViewGroup
        val showBlur: Boolean
        val isBlurSupported: StateFlow<Boolean>
        val isExpanded: StateFlow<Boolean>

        fun toggleExpanded()

        fun addTouchableBounds(vararg views: View)
    }

    class SliderHost(
        val viewModel: VolumeDialogSliderViewModel,
        val overscrollViewModel: VolumeDialogOverscrollViewModel,
        val hapticsViewModelFactory: SliderHapticsViewModel.Factory,
        val isExpanded: StateFlow<Boolean>,
    )

    class RingerColors(
        @ColorRes val icon: Int,
        @ColorRes val background: Int,
        @ColorRes val selectedIcon: Int,
        @ColorRes val selectedBackground: Int,
        val hideCard: Boolean,
    )
}
