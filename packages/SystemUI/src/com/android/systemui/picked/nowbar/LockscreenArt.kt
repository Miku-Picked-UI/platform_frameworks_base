/*
 * SPDX-FileCopyrightText: 2026 The PenguinOS Project, Miku-Picked UI
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.picked.nowbar

import android.content.ContentResolver
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [PenguinOS] Lock screen media experiments (Penguin Laboratory > Experiments), on our own Now
 * Bar instead of PenguinOS' Compose lock screen player:
 *  - Now Bar folding (Settings.System ls_now_bar, on by default as in PenguinOS): swiping the
 *    Now Bar's media pill down folds the lock screen notifications away, and, with ls_now_bar_art
 *    (on by default), fills the screen with the album art behind the pill. Swiping it up
 *    unfolds. Remembered in ls_now_bar_minimized.
 *  - iOS-style media art (ls_media_expand): tapping the art in the Now Bar's large player fills
 *    the screen with it (notifications folded away too); tapping it again goes back.
 *    Remembered in ls_media_expanded.
 *  - Animated album art (ls_media_motion_art): Apple Music's motion artwork loops over the full
 *    screen art ([MotionArt]).
 * All of it only while the Now Bar has a media page; KeyguardMediaArtSection draws the art and
 * SharedNotificationContainerBinder hides the notifications.
 */
object LockscreenArt {
    private const val NOW_BAR = "ls_now_bar"
    private const val NOW_BAR_ART = "ls_now_bar_art"
    private const val NOW_BAR_MINIMIZED = "ls_now_bar_minimized"
    private const val MEDIA_EXPAND = "ls_media_expand"
    private const val MEDIA_EXPANDED = "ls_media_expanded"
    private const val MOTION_ART = "ls_media_motion_art"

    private var resolver: ContentResolver? = null

    var foldEnabled = false
        private set

    private var foldArtEnabled = true
    var expandEnabled = false
        private set

    var motionArtEnabled = false
        private set

    var folded = false
        private set

    var expanded = false
        private set

    /** Set by the Now Bar: whether it shows a media page. */
    private var mediaShowing = false

    private val _fullArt = MutableStateFlow(false)
    /** The album art fills the lock screen. */
    val fullArt: StateFlow<Boolean> = _fullArt.asStateFlow()

    private val _notificationsHidden = MutableStateFlow(false)
    /** The lock screen notifications are folded away. */
    val notificationsHidden: StateFlow<Boolean> = _notificationsHidden.asStateFlow()

    /** Starts following the settings; idempotent. Main thread. */
    fun init(context: Context) {
        if (resolver != null) return
        val cr = context.applicationContext.contentResolver
        resolver = cr
        val observer =
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) = read()
            }
        for (key in
            listOf(NOW_BAR, NOW_BAR_ART, NOW_BAR_MINIMIZED, MEDIA_EXPAND, MEDIA_EXPANDED,
                MOTION_ART)) {
            cr.registerContentObserver(
                Settings.System.getUriFor(key),
                false,
                observer,
                UserHandle.USER_ALL,
            )
        }
        read()
    }

    private fun read() {
        val cr = resolver ?: return
        fun get(key: String, def: Int) =
            Settings.System.getIntForUser(cr, key, def, UserHandle.USER_CURRENT) != 0
        foldEnabled = get(NOW_BAR, 1)
        foldArtEnabled = get(NOW_BAR_ART, 1)
        folded = get(NOW_BAR_MINIMIZED, 0)
        expandEnabled = get(MEDIA_EXPAND, 0)
        expanded = get(MEDIA_EXPANDED, 0)
        motionArtEnabled = get(MOTION_ART, 0)
        publish()
    }

    fun setMediaShowing(showing: Boolean) {
        if (mediaShowing == showing) return
        mediaShowing = showing
        publish()
    }

    fun setFolded(value: Boolean) {
        if (!foldEnabled || folded == value) return
        folded = value
        persist(NOW_BAR_MINIMIZED, value)
        publish()
    }

    fun toggleExpanded() {
        if (!expandEnabled) return
        expanded = !expanded
        persist(MEDIA_EXPANDED, expanded)
        publish()
    }

    private fun persist(key: String, value: Boolean) {
        resolver?.let {
            Settings.System.putIntForUser(it, key, if (value) 1 else 0, UserHandle.USER_CURRENT)
        }
    }

    private fun publish() {
        val fold = foldEnabled && folded
        val expand = expandEnabled && expanded
        _notificationsHidden.value = mediaShowing && (fold || expand)
        _fullArt.value = mediaShowing && ((fold && foldArtEnabled) || expand)
    }
}
