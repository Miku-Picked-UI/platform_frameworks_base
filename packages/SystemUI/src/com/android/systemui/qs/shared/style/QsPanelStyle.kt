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

package com.android.systemui.qs.shared.style

enum class QsPanelStyle(val value: Int) {
    Default(0),

    Penguin(1),

    MyUi(2),

    Harmony(3),

    OneUi(4),

    ColorOs(5),

    Stock(6);

    /** Uses AOSP's own tiles and Quick Settings content rather than a Penguin layout. */
    val stockLayout: Boolean
        get() = this == Default || this == Stock

    companion object {
        const val SETTING_NAME = "qs_panel_style"

        const val ONE_UI_SETTING_NAME = "qs_oneui_experimental"

        fun fromValue(value: Int): QsPanelStyle =
            entries.firstOrNull { it.value == value && it != OneUi } ?: Penguin

        fun effective(chosen: Int, oneUi: Boolean): QsPanelStyle =
            if (oneUi) OneUi else fromValue(chosen)
    }
}
