/*
 * SPDX-FileCopyrightText: 2026 Miku-Picked UI
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.picked.colorcc

/**
 * [Miku-Picked UI] How much of the stock shade header (big clock, date, carriers, status icons)
 * ColorCC covers, 0 to 1. ColorCC draws its own top row, so the stock header fades out as the
 * ColorCC panel takes over (QQS -> QS) and stays hidden while it is open.
 *
 * [panel] is set by [ColorCcHost]. In the Lunaris split shade QS reports itself fully expanded
 * even while the notifications are the panel on screen, so QuickSettingsControllerImpl also says
 * how much of QS is actually shown ([qsShown]); the header stays for the notifications.
 * Read by ShadeHeaderController. Main thread only.
 */
object ColorCcHeaderCover {
    /** Called when [cover] changes. */
    var listener: (() -> Unit)? = null

    /** How far the ColorCC panel is over the header. */
    var panel = 0f
        set(value) {
            val v = value.coerceIn(0f, 1f)
            if (field == v) return
            field = v
            listener?.invoke()
        }

    /** How much of Quick Settings is on screen: 0 while the split shade shows notifications. */
    @JvmStatic
    var qsShown = 1f
        set(value) {
            val v = value.coerceIn(0f, 1f)
            if (field == v) return
            field = v
            listener?.invoke()
        }

    val cover: Float
        get() = panel * qsShown
}
