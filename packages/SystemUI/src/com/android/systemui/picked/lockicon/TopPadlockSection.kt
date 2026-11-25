/*
 * SPDX-FileCopyrightText: 2026 The PenguinOS Project, Miku-Picked UI
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.picked.lockicon

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.UserHandle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.keyguard.domain.interactor.KeyguardBlueprintInteractor
import com.android.systemui.keyguard.shared.model.KeyguardSection
import com.android.systemui.keyguard.ui.view.DeviceEntryIconView
import com.android.systemui.keyguard.ui.view.layout.blueprints.transitions.IntraBlueprintTransition
import com.android.systemui.keyguard.ui.viewmodel.DeviceEntryIconViewModel
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.res.R
import com.android.systemui.util.Utils
import dagger.Lazy
import javax.inject.Inject
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * [PenguinOS] The padlock at the top of the lock screen, under the status bar, for
 * [LockIconOnTop]. It shows the device entry icon's state: closed while locked, open once the
 * device can be entered, and fades with the device entry icon (gone on AOD).
 */
class TopPadlockSection
@Inject
constructor(
    private val context: Context,
    @Main private val mainHandler: Handler,
    private val deviceEntryIconViewModel: Lazy<DeviceEntryIconViewModel>,
    private val keyguardBlueprintInteractor: Lazy<KeyguardBlueprintInteractor>,
) : KeyguardSection() {

    private var view: FrameLayout? = null
    private var observing = false
    private var binding: DisposableHandle? = null

    private val observer =
        object : ContentObserver(mainHandler) {
            override fun onChange(selfChange: Boolean) {
                // The clocks move with the padlock, so lay the lock screen out again.
                keyguardBlueprintInteractor
                    .get()
                    .refreshBlueprint(IntraBlueprintTransition.Type.DefaultTransition)
            }
        }

    override fun addViews(constraintLayout: ConstraintLayout) {
        val density = context.resources.displayMetrics.density
        val icon =
            ImageView(context).apply {
                setImageResource(R.drawable.ic_lockscreen_padlock)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
        view =
            FrameLayout(context).apply {
                id = R.id.picked_top_padlock
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                visibility = View.GONE
                alpha = 0f
                addView(
                    icon,
                    FrameLayout.LayoutParams(
                        (18 * density).toInt(),
                        (23 * density).toInt(),
                        Gravity.CENTER,
                    ),
                )
            }
        constraintLayout.addView(view)
    }

    override fun bindData(constraintLayout: ConstraintLayout) {
        if (!observing) {
            context.contentResolver.registerContentObserver(
                Settings.System.getUriFor(LockIconOnTop.SETTING),
                false,
                observer,
                UserHandle.USER_ALL,
            )
            observing = true
        }
        val v = view ?: return
        val icon = v.getChildAt(0) as ImageView
        v.visibility = if (LockIconOnTop.isOn(context)) View.VISIBLE else View.GONE
        binding?.dispose()
        binding = v.repeatWhenAttached {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    val viewModel = deviceEntryIconViewModel.get()
                    combine(viewModel.iconType, viewModel.deviceEntryViewAlpha) { type, alpha ->
                            type to alpha
                        }
                        .collect { (type, alpha) ->
                            icon.setImageResource(
                                if (type == DeviceEntryIconView.IconType.UNLOCK) {
                                    R.drawable.ic_lockscreen_padlock_open
                                } else {
                                    R.drawable.ic_lockscreen_padlock
                                }
                            )
                            v.alpha = if (type == DeviceEntryIconView.IconType.NONE) 0f else alpha
                        }
                }
            }
        }
    }

    override fun applyConstraints(constraintSet: ConstraintSet) {
        val density = context.resources.displayMetrics.density
        val id = R.id.picked_top_padlock
        constraintSet.apply {
            constrainWidth(id, (LockIconOnTop.RADIUS_DP * 2 * density).toInt())
            constrainHeight(id, (LockIconOnTop.RADIUS_DP * 2 * density).toInt())
            connect(id, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
            connect(id, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
            connect(
                id,
                ConstraintSet.TOP,
                ConstraintSet.PARENT_ID,
                ConstraintSet.TOP,
                Utils.getStatusBarHeaderHeightKeyguard(context) +
                    (LockIconOnTop.MARGIN_DP * density).toInt(),
            )
            setVisibility(id, if (LockIconOnTop.isOn(context)) View.VISIBLE else View.GONE)
        }
    }

    override fun removeViews(constraintLayout: ConstraintLayout) {
        if (observing) {
            context.contentResolver.unregisterContentObserver(observer)
            observing = false
        }
        binding?.dispose()
        binding = null
        view?.let(constraintLayout::removeView)
        view = null
    }
}
