/*
 * Copyright (C) 2026 The PenguinOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.ui.composable

import androidx.compose.foundation.layout.Arrangement.spacedBy
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal val LocalQsWide = compositionLocalOf { false }

private val WideColumnGap = 24.dp

@Composable
internal fun QsColumns(
    gap: Dp,
    modifier: Modifier = Modifier,
    first: @Composable ColumnScope.() -> Unit,
    second: @Composable ColumnScope.() -> Unit,
) {
    if (!LocalQsWide.current) {
        Column(verticalArrangement = spacedBy(gap), modifier = modifier) {
            first()
            second()
        }
        return
    }
    Row(
        horizontalArrangement = spacedBy(WideColumnGap),
        verticalAlignment = Alignment.Top,
        modifier = modifier,
    ) {
        Column(verticalArrangement = spacedBy(gap), modifier = Modifier.weight(1f)) { first() }
        Column(verticalArrangement = spacedBy(gap), modifier = Modifier.weight(1f)) { second() }
    }
}
