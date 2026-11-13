/*
 * SPDX-FileCopyrightText: 2026 Miku-Picked UI
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.tiles

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.RemoteException
import android.os.ServiceManager
import android.service.quicksettings.Tile
import android.util.Log
import android.widget.Switch
import com.android.internal.logging.MetricsLogger
import com.android.systemui.animation.Expandable
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.plugins.qs.QSTile
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.qs.QSHost
import com.android.systemui.qs.QsEventLogger
import com.android.systemui.qs.logging.QSLogger
import com.android.systemui.qs.tileimpl.QSTileImpl
import com.android.systemui.res.R
import com.android.systemui.statusbar.policy.BatteryController
import vendor.lineage.powershare.IPowerShare
import javax.inject.Inject

/**
 * [Miku-Picked UI] Wireless PowerShare (reverse wireless charging): charges another phone,
 * earbuds or a watch laid on the back of this one, through LineageOS' PowerShare HAL
 * (vendor.lineage.powershare, the Samsung implementation on the Galaxy S21). The HAL stops
 * sharing below its minimum battery level, which Settings → Picked features sets.
 */
class PowerShareTile
@Inject
constructor(
    host: QSHost,
    uiEventLogger: QsEventLogger,
    @Background backgroundLooper: Looper,
    @Main mainHandler: Handler,
    falsingManager: FalsingManager,
    metricsLogger: MetricsLogger,
    private val statusBarStateController: StatusBarStateController,
    activityStarter: ActivityStarter,
    private val qsLogger: QSLogger,
    batteryController: BatteryController,
) :
    QSTileImpl<QSTile.BooleanState>(
        host,
        uiEventLogger,
        backgroundLooper,
        mainHandler,
        falsingManager,
        metricsLogger,
        statusBarStateController,
        activityStarter,
        qsLogger,
    ) {

    private var service: IPowerShare? = null

    init {
        // The HAL turns sharing off by itself (low battery, nothing on the back for a while);
        // battery changes are a good moment to look again.
        batteryController.observe(
            lifecycle,
            object : BatteryController.BatteryStateChangeCallback {
                override fun onBatteryLevelChanged(level: Int, plugged: Boolean, charging: Boolean) =
                    refreshState()

                override fun onPowerSaveChanged(isPowerSave: Boolean) = refreshState()
            },
        )
    }

    /** The HAL, or null where the device has none. Binder calls: background thread only. */
    private fun powerShare(): IPowerShare? {
        service?.let { if (it.asBinder().isBinderAlive) return it }
        if (!ServiceManager.isDeclared(SERVICE)) return null
        service =
            try {
                IPowerShare.Stub.asInterface(ServiceManager.waitForDeclaredService(SERVICE))
            } catch (e: RuntimeException) {
                Log.e(LOG_TAG, "PowerShare HAL unavailable", e)
                null
            }
        return service
    }

    override fun isAvailable(): Boolean = ServiceManager.isDeclared(SERVICE)

    override fun newTileState() = QSTile.BooleanState()

    override fun handleSetListening(listening: Boolean) {
        super.handleSetListening(listening)
        if (listening) refreshState()
    }

    override fun handleClick(expandable: Expandable?) {
        if (mState.state == Tile.STATE_UNAVAILABLE) return
        val enable = !mState.value
        qsLogger.logTileClick(tileSpec, statusBarStateController.state, mState.state, mState.state)
        try {
            powerShare()?.setEnabled(enable)
        } catch (e: RemoteException) {
            Log.e(LOG_TAG, "could not set PowerShare", e)
        } catch (e: RuntimeException) {
            Log.e(LOG_TAG, "could not set PowerShare", e)
        }
        refreshState()
    }

    override fun getLongClickIntent(): Intent? = null

    override fun getMetricsCategory() = 0

    override fun handleUpdateState(state: QSTile.BooleanState, arg: Any?) {
        state.label = mContext.getString(R.string.quick_settings_powershare_label)
        state.icon = ResourceIcon.get(R.drawable.ic_qs_powershare)
        val hal = powerShare()
        val enabled =
            try {
                hal?.isEnabled ?: false
            } catch (e: RemoteException) {
                false
            } catch (e: RuntimeException) {
                false
            }
        state.value = enabled
        state.state =
            when {
                hal == null -> Tile.STATE_UNAVAILABLE
                enabled -> Tile.STATE_ACTIVE
                else -> Tile.STATE_INACTIVE
            }
        state.secondaryLabel =
            if (enabled) mContext.getString(R.string.quick_settings_powershare_on) else null
        state.contentDescription = state.label
        state.expandedAccessibilityClassName = Switch::class.java.name
    }

    override fun getTileLabel(): CharSequence =
        mContext.getString(R.string.quick_settings_powershare_label)

    companion object {
        const val TILE_SPEC = "powershare"
        private const val LOG_TAG = "PowerShareTile"
        private const val SERVICE = "vendor.lineage.powershare.IPowerShare/default"
    }
}
