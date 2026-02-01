/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.statusbar.pipeline.shared.ui.viewmodel

import android.os.UserHandle
import android.provider.Settings
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.statusbar.pipeline.mobile.domain.interactor.MobileIconsInteractor
import com.android.systemui.util.settings.SettingsProxyExt.observerFlow
import com.android.systemui.util.settings.SystemSettings
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/**
 * View model for the operator name (aka carrier name) of the carrier for the default data
 * subscription.
 */
@SysUISingleton
class StatusBarOperatorNameViewModel
@Inject
constructor(
    mobileIconsInteractor: MobileIconsInteractor,
    systemSettings: SystemSettings,
    @Background bgDispatcher: CoroutineDispatcher,
) {
    private val carrierNameFromPipeline: Flow<String?> =
        mobileIconsInteractor.defaultDataSubId.flatMapLatest {
            if (it == null) {
                flowOf(null)
            } else {
                mobileIconsInteractor.getMobileConnectionInteractorForSubId(it).carrierName
            }
        }

    /** [Evolution X] User-defined carrier label, null or empty = use the real carrier name. */
    private val customCarrierText: Flow<String?> =
        systemSettings
            .observerFlow(UserHandle.USER_ALL, Settings.System.LOCKSCREEN_SHOW_CUSTOM_CARRIER_TEXT)
            .onStart { emit(Unit) }
            .map {
                systemSettings
                    .getStringForUser(
                        Settings.System.LOCKSCREEN_SHOW_CUSTOM_CARRIER_TEXT,
                        UserHandle.USER_CURRENT,
                    )
                    ?.trim()
            }
            .flowOn(bgDispatcher)

    val operatorName: Flow<String?> =
        combine(carrierNameFromPipeline, customCarrierText) { carrierName, customText ->
            if (!customText.isNullOrEmpty()) customText else carrierName
        }
}
