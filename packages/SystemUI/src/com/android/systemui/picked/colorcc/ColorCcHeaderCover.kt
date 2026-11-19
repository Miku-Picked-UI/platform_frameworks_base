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
 * Set by [ColorCcHost], read by ShadeHeaderController. Main thread only.
 */
object ColorCcHeaderCover {
    /** Called when [cover] changes. */
    var listener: (() -> Unit)? = null

    var cover = 0f
        set(value) {
            val v = value.coerceIn(0f, 1f)
            if (field == v) return
            field = v
            listener?.invoke()
        }
}
