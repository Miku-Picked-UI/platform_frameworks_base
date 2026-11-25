/*
 * SPDX-FileCopyrightText: 2026 The PenguinOS Project, Miku-Picked UI
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.picked.lockicon

import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.view.children
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.systemui.keyguard.shared.model.KeyguardSection
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.res.R
import com.android.systemui.statusbar.phone.DozeParameters
import javax.inject.Inject
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.launch

/**
 * [PenguinOS] The screen-off fingerprint icon (doze/DozeUdfpsIconController) is AOD with only
 * the fingerprint icon: while DozeParameters says so, everything else on the lock screen root
 * (clock, AOD notification icons, smartspace, ...) is faded out. PenguinOS does this in its
 * Compose lock screen; here it uses each view's transition alpha, which multiplies with the
 * alpha the lock screen's own binders keep setting. The notifications are hidden by
 * SharedNotificationContainerBinder.
 */
class UdfpsIconOnlySection @Inject constructor(private val dozeParameters: DozeParameters) :
    KeyguardSection() {

    private var binding: DisposableHandle? = null

    override fun addViews(constraintLayout: ConstraintLayout) {}

    override fun bindData(constraintLayout: ConstraintLayout) {
        binding?.dispose()
        binding =
            constraintLayout.repeatWhenAttached {
                repeatOnLifecycle(Lifecycle.State.CREATED) {
                    launch {
                        dozeParameters.udfpsIconOnly.collect { iconOnly ->
                            apply(constraintLayout, iconOnly)
                        }
                    }
                }
            }
    }

    private fun apply(root: ConstraintLayout, iconOnly: Boolean) {
        for (child in root.children) {
            child.transitionAlpha = if (iconOnly && !isIcon(child.id)) 0f else 1f
        }
    }

    private fun isIcon(id: Int) =
        id == R.id.device_entry_icon_view || id == R.id.udfps_accessibility_overlay

    override fun applyConstraints(constraintSet: ConstraintSet) {}

    override fun removeViews(constraintLayout: ConstraintLayout) {
        binding?.dispose()
        binding = null
        apply(constraintLayout, false)
    }
}
