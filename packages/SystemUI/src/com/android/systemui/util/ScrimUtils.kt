/*
 * Copyright (C) 2025 The AxionAOSP Project
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

package com.android.systemui.util

import android.service.notification.StatusBarNotification

/**
 * Event hub used by the Lunaris Bar (AxionAOSP "ScrimUtils").
 *
 * Miku-Picked UI: only the notification events the Lunaris Bar needs are kept. They are fed from
 * [com.android.systemui.statusbar.NotificationListener]. AxionOS's scrim, keyguard and wallpaper
 * depth hooks are not part of Miku, so those callbacks exist for API compatibility but are never
 * dispatched.
 */
class ScrimUtils private constructor() {

    interface ScrimEventListener {
        fun onKeyguardShowingChanged(showing: Boolean) {}
        fun onKeyguardFadingAwayChanged(fadingAway: Boolean) {}
        fun onKeyguardGoingAwayChanged(goingAway: Boolean) {}
        fun onPrimaryBouncerShowingChanged(showing: Boolean) {}
        fun onDozingChanged(dozing: Boolean) {}
        fun onExpandedFractionChanged(expandedFraction: Float) {}
        fun onBarStateChanged(state: Int) {}
        fun onQsVisibilityChanged(visible: Boolean) {}
        fun onStartedWakingUp() {}
        fun onScreenTurnedOff() {}
        fun onUserChanged() {}
        fun setPulsing(pulsing: Boolean) {}
        fun onNotificationPosted(sbn: StatusBarNotification) {}
        fun onNotificationRemoved(sbn: StatusBarNotification) {}
        fun onKeyguardLayoutChanged() {}
        fun onKeyguardAlphaChanged(alpha: Float) {}
        fun onMediaArtVisibilityChanged(visible: Boolean) {}
    }

    private val listeners = WeakListenerManager<ScrimEventListener>()

    fun addListener(listener: ScrimEventListener) = listeners.addListener(listener)

    fun removeListener(listener: ScrimEventListener) = listeners.removeListener(listener)

    fun onNotificationPosted(sbn: StatusBarNotification) {
        listeners.notifyOnMain { it.onNotificationPosted(sbn) }
    }

    fun onNotificationRemoved(sbn: StatusBarNotification) {
        listeners.notifyOnMain { it.onNotificationRemoved(sbn) }
    }

    companion object {
        @Volatile private var instance: ScrimUtils? = null

        @JvmStatic
        fun get(): ScrimUtils =
            instance ?: synchronized(this) { instance ?: ScrimUtils().also { instance = it } }
    }
}
