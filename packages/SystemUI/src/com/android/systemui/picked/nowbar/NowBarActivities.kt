/*
 * SPDX-FileCopyrightText: 2026 Miku-Picked UI
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.picked.nowbar

import android.app.Notification
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.statusbar.notification.collection.NotifPipeline
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionListener
import com.android.systemui.statusbar.policy.FlashlightController
import javax.inject.Inject

/**
 * Miku-Picked UI: the system activities the Now Bar shows besides media, like One UI's Now Bar:
 * the flashlight while it is on, and ongoing "live" notifications (timers, stopwatches, calls,
 * navigation, recorders: ongoing notifications that run a chronometer, use the call /
 * stopwatch / navigation category, or are promoted ongoing).
 *
 * One instance for SystemUI; the Now Bar view subscribes while it is attached. Listeners are
 * always called on the main thread: the flashlight reports changes on SystemUI's background
 * thread, so those are posted over first.
 */
@SysUISingleton
class NowBarActivities
@Inject
constructor(
    private val flashlightController: FlashlightController,
    private val notifPipeline: NotifPipeline,
) {
    fun interface Listener {
        fun onActivitiesChanged()
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashSet<Listener>()
    private var started = false

    /** Ongoing notifications by key, oldest first. */
    private val ongoing = LinkedHashMap<String, NotificationEntry>()

    var flashlightOn = false
        private set

    val ongoingEntries: List<NotificationEntry>
        get() = ongoing.values.toList()

    private val flashlightListener = object : FlashlightController.FlashlightListener {
        override fun onFlashlightChanged(enabled: Boolean) {
            mainHandler.post {
                if (flashlightOn != enabled) {
                    flashlightOn = enabled
                    dispatch()
                }
            }
        }

        override fun onFlashlightError() {
            onFlashlightChanged(false)
        }

        override fun onFlashlightAvailabilityChanged(available: Boolean) {
            if (!available) onFlashlightChanged(false)
        }
    }

    private val collectionListener = object : NotifCollectionListener {
        override fun onEntryAdded(entry: NotificationEntry) = track(entry)

        override fun onEntryUpdated(entry: NotificationEntry) = track(entry)

        override fun onEntryRemoved(entry: NotificationEntry, reason: Int) {
            if (ongoing.remove(entry.key) != null) dispatch()
        }
    }

    fun addListener(listener: Listener) {
        listeners.add(listener)
        if (!started) {
            started = true
            try {
                flashlightOn = flashlightController.isEnabled
                flashlightController.addCallback(flashlightListener)
            } catch (t: Throwable) {
                Log.e(TAG, "flashlight unavailable", t)
            }
            try {
                notifPipeline.allNotifs.forEach { if (isOngoingActivity(it)) ongoing[it.key] = it }
                notifPipeline.addCollectionListener(collectionListener)
            } catch (t: Throwable) {
                Log.e(TAG, "notifications unavailable", t)
            }
        }
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    fun turnOffFlashlight() {
        flashlightController.setFlashlight(false)
    }

    private fun track(entry: NotificationEntry) {
        val changed =
            if (isOngoingActivity(entry)) {
                ongoing[entry.key] = entry
                true
            } else {
                ongoing.remove(entry.key) != null
            }
        if (changed) dispatch()
    }

    private fun dispatch() {
        listeners.toList().forEach { it.onActivitiesChanged() }
    }

    companion object {
        private const val TAG = "NowBarActivities"

        private val CATEGORIES =
            setOf(
                Notification.CATEGORY_CALL,
                Notification.CATEGORY_STOPWATCH,
                Notification.CATEGORY_NAVIGATION,
            )

        fun isOngoingActivity(entry: NotificationEntry): Boolean {
            val sbn = entry.sbn
            val n = sbn.notification
            if (!sbn.isOngoing) return false
            // Media has its own page; secret notifications never show on the lockscreen.
            if (n.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)) return false
            if (n.visibility == Notification.VISIBILITY_SECRET) return false
            return n.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER) ||
                n.category in CATEGORIES ||
                n.isPromotedOngoing
        }
    }
}
