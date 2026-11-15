/*
 * SPDX-FileCopyrightText: 2026 Miku-Picked UI
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.weather

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.View
import android.widget.ImageView

/**
 * Weather icon slot of the custom lockscreen clocks (Evolution X). Filled from
 * [WeatherRepository] (Open-Meteo) and hidden while weather is off or unknown. The layouts'
 * tint colours the icon.
 */
class WeatherImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : ImageView(context, attrs, defStyle), WeatherRepository.Listener {

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
        setImageDrawable(WeatherIconDrawable(weather.code, weather.isDay, Color.WHITE))
        contentDescription = weather.city
        super.setVisibility(View.VISIBLE)
    }
}
