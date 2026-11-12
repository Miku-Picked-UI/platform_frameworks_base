/*
 * Copyright (C) 2026 The PenguinOS Project
 * SPDX-FileCopyrightText: 2026 Miku-Picked UI
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.picked.clockfly

import android.content.Context
import android.graphics.Rect
import android.view.View
import com.android.systemui.clocks.ClockStyle
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.keyguard.domain.interactor.KeyguardClockInteractor
import com.android.systemui.keyguard.shared.model.ClockSize
import dagger.Lazy
import javax.inject.Inject
import kotlin.math.roundToInt

/**
 * Hands the lockscreen clock to the launcher on unlock, so it can fly it onto the "Lockscreen
 * clock" home screen widget (PenguinOS' clock handoff, for the classic keyguard).
 *
 * [handoff] gives the clock's bounds on screen for ILauncherUnlockAnimationController
 * .prepareForUnlock(); the last argument, the smartspace page in AOSP, carries 0 for a large
 * clock and -1 for a small one, as in PenguinOS. [hide] takes the lockscreen clock away once the
 * launcher has it, and [restore] brings it back when the keyguard is gone or the unlock was
 * cancelled.
 */
@SysUISingleton
class ClockFlyHandoff
@Inject
constructor(
    @Application private val context: Context,
    private val clockInteractor: Lazy<KeyguardClockInteractor>,
) {
    data class Handoff(val bounds: Rect, val fontHeight: Int)

    private var hidden: View? = null
    private var hiddenAlpha = 1f

    val isEnabled: Boolean
        get() = HomeClockRenderer.isEnabled(context)

    fun handoff(): Handoff? {
        if (!isEnabled) return null
        val (view, fontHeight) = clockView() ?: return null
        if (!view.isAttachedToWindow || !view.isShown || view.width == 0) return null
        val origin = IntArray(2).also { view.getLocationOnScreen(it) }
        var scaleX = 1f
        var scaleY = 1f
        var v: View? = view
        while (v != null) {
            scaleX *= v.scaleX
            scaleY *= v.scaleY
            v = v.parent as? View
        }
        val bounds =
            Rect(
                origin[0],
                origin[1],
                origin[0] + (view.width * scaleX).roundToInt(),
                origin[1] + (view.height * scaleY).roundToInt(),
            )
        return if (bounds.isEmpty) null else Handoff(bounds, fontHeight)
    }

    fun hide() {
        val (view, _) = clockView() ?: return
        if (hidden == null) hiddenAlpha = view.alpha
        view.alpha = 0f
        hidden = view
    }

    fun restore() {
        hidden?.alpha = hiddenAlpha
        hidden = null
    }

    private fun clockView(): Pair<View, Int>? {
        ClockStyle.showing()?.clockView?.let {
            return it to 0
        }
        val interactor = clockInteractor.get()
        val clock = interactor.currentClock.value ?: return null
        val large = interactor.clockSize.value == ClockSize.LARGE
        return (if (large) clock.largeClock else clock.smallClock).view to if (large) 0 else -1
    }
}
