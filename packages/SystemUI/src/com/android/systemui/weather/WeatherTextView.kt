/*
 * SPDX-FileCopyrightText: 2026 Miku-Picked UI
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.weather

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.TextView

/**
 * Weather text slot of the custom lockscreen clocks (Evolution X): the temperature, e.g. "21°C".
 * Filled from [WeatherRepository] (Open-Meteo) and hidden while weather is off or unknown.
 */
class WeatherTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : TextView(context, attrs, defStyle), WeatherRepository.Listener {

    init {
        super.setVisibility(View.GONE)
    }

    /** The clock layouts do not control this slot; the weather does. */
    override fun setVisibility(visibility: Int) {}

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        WeatherRepository.addListener(context, this)
    }

    override fun onDetachedFromWindow() {
        WeatherRepository.removeListener(this)
        super.onDetachedFromWindow()
    }

    override fun onWeatherChanged(weather: WeatherRepository.Weather?) {
        if (weather == null) {
            super.setVisibility(View.GONE)
            return
        }
        text = "${weather.temperature}${weather.unit}"
        super.setVisibility(View.VISIBLE)
    }
}
