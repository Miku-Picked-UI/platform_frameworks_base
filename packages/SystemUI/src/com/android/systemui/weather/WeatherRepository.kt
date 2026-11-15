/*
 * SPDX-FileCopyrightText: 2026 Miku-Picked UI
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.weather

import android.content.Context
import android.database.ContentObserver
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors
import org.json.JSONObject

/**
 * Miku-Picked UI: current weather for the custom lockscreen clocks, from Open-Meteo
 * (open-meteo.com: free, no API key, no account). Only the coordinates of the city the user
 * picked in Settings are sent; no device location is used.
 *
 * Settings.System: picked_weather_enabled (0/1), picked_weather_lat / _lon / _city (written by
 * Settings' city search), picked_weather_unit (0 = °C, 1 = °F).
 *
 * Refreshes at most every [REFRESH_MS] while some weather view is attached, and when the network
 * comes back after a failed fetch.
 */
object WeatherRepository {

    private const val TAG = "PickedWeather"
    const val KEY_ENABLED = "picked_weather_enabled"
    const val KEY_LAT = "picked_weather_lat"
    const val KEY_LON = "picked_weather_lon"
    const val KEY_CITY = "picked_weather_city"
    const val KEY_UNIT = "picked_weather_unit"
    private const val REFRESH_MS = 30 * 60 * 1000L
    private const val RETRY_MS = 5 * 60 * 1000L

    /** Current conditions; [code] is a WMO weather code. */
    data class Weather(val temperature: Int, val unit: String, val code: Int, val isDay: Boolean,
        val city: String)

    fun interface Listener {
        fun onWeatherChanged(weather: Weather?)
    }

    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val listeners = LinkedHashSet<Listener>()
    private var appContext: Context? = null
    private var current: Weather? = null
    private var lastFetch = 0L
    private var lastKey: String? = null
    private var fetching = false
    private var failed = false

    private val refresh = Runnable { fetchIfNeeded(force = false) }

    private val observer = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) = fetchIfNeeded(force = true)
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            main.post { if (failed) fetchIfNeeded(force = true) }
        }
    }

    /** Main thread. */
    fun addListener(context: Context, listener: Listener) {
        if (appContext == null) {
            val ctx = context.applicationContext
            appContext = ctx
            for (key in arrayOf(KEY_ENABLED, KEY_LAT, KEY_LON, KEY_UNIT)) {
                ctx.contentResolver.registerContentObserver(
                    Settings.System.getUriFor(key), false, observer, UserHandle.USER_ALL)
            }
            ctx.getSystemService(ConnectivityManager::class.java)
                ?.registerDefaultNetworkCallback(networkCallback, main)
        }
        listeners += listener
        listener.onWeatherChanged(if (enabled()) current else null)
        fetchIfNeeded(force = false)
    }

    /** Main thread. */
    fun removeListener(listener: Listener) {
        listeners -= listener
        if (listeners.isEmpty()) main.removeCallbacks(refresh)
    }

    private fun setting(key: String): String? = appContext?.let {
        Settings.System.getStringForUser(it.contentResolver, key, UserHandle.USER_CURRENT)
    }

    private fun enabled() = setting(KEY_ENABLED) == "1"

    private fun dispatch() {
        val w = if (enabled()) current else null
        for (l in listeners.toList()) l.onWeatherChanged(w)
    }

    private fun fetchIfNeeded(force: Boolean) {
        main.removeCallbacks(refresh)
        if (listeners.isEmpty()) return
        val lat = setting(KEY_LAT)?.toDoubleOrNull()
        val lon = setting(KEY_LON)?.toDoubleOrNull()
        if (!enabled() || lat == null || lon == null) {
            dispatch()
            return
        }
        val fahrenheit = setting(KEY_UNIT) == "1"
        val city = setting(KEY_CITY) ?: ""
        val key = "$lat,$lon,$fahrenheit"
        if (key != lastKey) {
            current = null
            lastFetch = 0
        }
        val age = SystemClock.elapsedRealtime() - lastFetch
        if (!force && key == lastKey && age < REFRESH_MS && current != null) {
            dispatch()
            main.postDelayed(refresh, REFRESH_MS - age)
            return
        }
        if (fetching) return
        fetching = true
        lastKey = key
        executor.execute {
            val result = fetch(lat, lon, fahrenheit, city)
            main.post {
                fetching = false
                failed = result == null
                if (result != null) {
                    current = result
                    lastFetch = SystemClock.elapsedRealtime()
                }
                dispatch()
                if (listeners.isNotEmpty()) {
                    main.postDelayed(refresh, if (result != null) REFRESH_MS else RETRY_MS)
                }
            }
        }
    }

    private fun fetch(lat: Double, lon: Double, fahrenheit: Boolean, city: String): Weather? {
        val url = String.format(Locale.US,
            "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f" +
                "&current=temperature_2m,weather_code,is_day&temperature_unit=%s",
            lat, lon, if (fahrenheit) "fahrenheit" else "celsius")
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("User-Agent", "Android-SystemUI-Weather")
            }
            if (conn.responseCode != 200) return null
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val cur = json.getJSONObject("current")
            Weather(
                temperature = Math.round(cur.getDouble("temperature_2m")).toInt(),
                unit = if (fahrenheit) "°F" else "°C",
                code = cur.getInt("weather_code"),
                isDay = cur.optInt("is_day", 1) == 1,
                city = city,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Weather fetch failed: $e")
            null
        } finally {
            conn?.disconnect()
        }
    }
}
