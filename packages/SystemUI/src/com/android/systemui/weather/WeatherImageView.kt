/*
 * SPDX-FileCopyrightText: 2026 Miku-Picked UI
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.weather

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.ImageView

/**
 * Weather slot of the custom lockscreen clocks (Evolution X). Evolution X fills it from its
 * OmniJaws weather service; Miku-Picked UI has no weather service, so the slot stays hidden.
 */
class WeatherImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : ImageView(context, attrs, defStyle) {

    init {
        super.setVisibility(View.GONE)
    }

    override fun setVisibility(visibility: Int) {
        super.setVisibility(View.GONE)
    }
}
