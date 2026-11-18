/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.qs.panels.ui.compose

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.android.compose.animation.scene.ContentScope
import com.android.systemui.qs.panels.ui.viewmodel.TileGridViewModel
import com.android.systemui.qs.pipeline.shared.TileSpec

/**
 * Displays a grid of tiles with an optional reveal animation.
 *
 * @param enableRevealEffect If `true`, the tiles will animate using the reveal animation.
 */
@Composable
fun ContentScope.TileGrid(
    viewModel: TileGridViewModel,
    modifier: Modifier = Modifier,
    listening: () -> Boolean = { true },
    enableRevealEffect: Boolean = false,
    // [PenguinOS] Only these tiles, on this many columns (Android 17's TileGrid).
    includeSpecs: List<TileSpec>? = null,
    columnsOverride: Int? = null,
) {
    val gridLayout = viewModel.gridLayout
    val tiles =
        viewModel.tileViewModels.let { all ->
            if (includeSpecs == null) all else all.filter { it.spec in includeSpecs }
        }
    with(gridLayout) {
        TileGrid(
            tiles = tiles,
            modifier = modifier,
            listening = listening,
            enableRevealEffect = enableRevealEffect,
            columnsOverride = columnsOverride,
        )
    }
}
