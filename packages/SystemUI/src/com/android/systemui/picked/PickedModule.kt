/*
 * SPDX-FileCopyrightText: 2026 Miku-Picked UI
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.picked

import com.android.systemui.qs.QsEventLogger
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.shared.model.TileCategory
import com.android.systemui.qs.tileimpl.QSTileImpl
import com.android.systemui.qs.tiles.AutoBrightnessTile
import com.android.systemui.qs.tiles.SleepModeTile
import com.android.systemui.qs.tiles.base.shared.model.QSTileConfig
import com.android.systemui.qs.tiles.base.shared.model.QSTileUIConfig
import com.android.systemui.res.R
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.multibindings.IntoMap
import dagger.multibindings.StringKey

/**
 * Dagger bindings for features picked from Infinity X, AxionOS, uwuAOSP and Evolution X
 * that do not belong to an existing SystemUI module.
 */
@Module
interface PickedModule {

    /** [uwuAOSP] Inject AutoBrightnessTile into tileMap in QSModule */
    @Binds
    @IntoMap
    @StringKey(AutoBrightnessTile.TILE_SPEC)
    fun bindAutoBrightnessTile(autoBrightnessTile: AutoBrightnessTile): QSTileImpl<*>

    /** [Evolution X] Inject SleepModeTile into tileMap in QSModule */
    @Binds
    @IntoMap
    @StringKey(SleepModeTile.TILE_SPEC)
    fun bindSleepModeTile(sleepModeTile: SleepModeTile): QSTileImpl<*>

    companion object {
        /** [uwuAOSP] Auto brightness tile config */
        @Provides
        @IntoMap
        @StringKey(AutoBrightnessTile.TILE_SPEC)
        fun provideAutoBrightnessTileConfig(uiEventLogger: QsEventLogger): QSTileConfig =
            QSTileConfig(
                tileSpec = TileSpec.create(AutoBrightnessTile.TILE_SPEC),
                uiConfig =
                    QSTileUIConfig.Resource(
                        iconRes = R.drawable.ic_qs_autobrightness,
                        labelRes = R.string.quick_settings_autobrightness_label,
                    ),
                instanceId = uiEventLogger.getNewInstanceId(),
                category = TileCategory.DISPLAY,
            )

        /** [Evolution X] Sleep Mode tile config */
        @Provides
        @IntoMap
        @StringKey(SleepModeTile.TILE_SPEC)
        fun provideSleepModeTileConfig(uiEventLogger: QsEventLogger): QSTileConfig =
            QSTileConfig(
                tileSpec = TileSpec.create(SleepModeTile.TILE_SPEC),
                uiConfig =
                    QSTileUIConfig.Resource(
                        iconRes = com.android.internal.R.drawable.ic_sleep,
                        labelRes = R.string.quick_settings_sleep_mode_label,
                    ),
                instanceId = uiEventLogger.getNewInstanceId(),
                category = TileCategory.UTILITIES,
            )
    }
}
