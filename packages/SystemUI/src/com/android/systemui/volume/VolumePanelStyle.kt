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

package com.android.systemui.volume

import android.content.Context
import android.os.UserHandle
import android.provider.Settings

enum class VolumePanelStyle(val value: Int) {
    DEFAULT(0),

    EXPANDABLE(1),

    ONE_UI(2),

    MYUI(3),

    COLOR_OS(4);

    val isCard: Boolean
        get() = this == ONE_UI || this == MYUI

    companion object {
        const val SETTING_NAME = "volume_panel_style"

        fun fromValue(value: Int): VolumePanelStyle =
            values().firstOrNull { it.value == value } ?: DEFAULT

        fun current(context: Context): VolumePanelStyle =
            fromValue(
                Settings.Secure.getIntForUser(
                    context.contentResolver,
                    SETTING_NAME,
                    DEFAULT.value,
                    UserHandle.USER_CURRENT,
                )
            )
    }
}
