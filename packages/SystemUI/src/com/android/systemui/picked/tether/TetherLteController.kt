/*
 * SPDX-FileCopyrightText: 2026 Miku-Picked UI
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.picked.tether

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.net.TetheringManager
import android.os.Handler
import android.provider.Settings
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import javax.inject.Inject

/**
 * Miku-Picked UI: keeps the modem off 5G while the phone is tethering (USB, Wi-Fi hotspot,
 * Bluetooth).
 *
 * On 5G NSA the modem keeps the LTE anchor and the 5G radio up at once. With a weak 5G signal
 * that costs far more power than it brings, and while tethering (which on USB also charges from
 * a weak PC port) the phone heated up past 52 °C. LTE alone easily carries tethered traffic.
 *
 * The restriction goes in through [TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_POWER], which
 * Telephony intersects with the user's preferred network type, so the user's setting is never
 * touched. The previous power mask of each subscription is saved in Settings.Global and put back
 * when tethering stops or the feature is turned off, also across a SystemUI restart.
 *
 * Settings.Secure [SETTING_ENABLED] (on by default) turns it off.
 */
@SysUISingleton
class TetherLteController
@Inject
constructor(
    private val context: Context,
    @Background private val bgHandler: Handler,
) : CoreStartable {

    private var tethering = false
    private var retries = 0
    private val retry = Runnable { update() }

    override fun start() {
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    val active =
                        intent.getStringArrayListExtra(TetheringManager.EXTRA_ACTIVE_TETHER)
                    tethering = !active.isNullOrEmpty()
                    update()
                }
            }
        // Sticky: delivers the current state right away, so a SystemUI restart catches up.
        // Exported because the Tethering module sends it from the network stack uid, not as
        // system; the action is a protected broadcast, so no app can fake it.
        context.registerReceiver(
            receiver,
            IntentFilter(TetheringManager.ACTION_TETHER_STATE_CHANGED),
            null,
            bgHandler,
            Context.RECEIVER_EXPORTED,
        )
        context.contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(SETTING_ENABLED),
            false,
            object : ContentObserver(bgHandler) {
                override fun onChange(selfChange: Boolean) = update()
            },
        )
        context
            .getSystemService(SubscriptionManager::class.java)
            ?.addOnSubscriptionsChangedListener(
                { bgHandler.post(it) },
                object : SubscriptionManager.OnSubscriptionsChangedListener() {
                    override fun onSubscriptionsChanged() = update()
                },
            )
        bgHandler.post { update() }
    }

    private fun enabled(): Boolean =
        Settings.Secure.getInt(context.contentResolver, SETTING_ENABLED, 1) != 0

    /** Applies or lifts the restriction on every active subscription. Runs on [bgHandler]. */
    private fun update() {
        bgHandler.removeCallbacks(retry)
        val restrict = tethering && enabled()
        val subs =
            try {
                context
                    .getSystemService(SubscriptionManager::class.java)
                    ?.activeSubscriptionIdList
            } catch (e: RuntimeException) {
                Log.w(TAG, "no subscriptions", e)
                null
            } ?: return
        val telephony = context.getSystemService(TelephonyManager::class.java) ?: return
        var settled = true
        for (subId in subs) {
            val held =
                try {
                    apply(telephony.createForSubscriptionId(subId), subId, restrict)
                } catch (e: RuntimeException) {
                    // Telephony not ready, or the SIM just went away.
                    Log.w(TAG, "sub $subId: could not update allowed network types", e)
                    false
                }
            if (!held) settled = false
        }
        // Telephony drops a request it can't serve right now (the SIM or radio is still coming
        // up), so try again until it holds; a subscription change also retries.
        if (settled) {
            retries = 0
        } else if (retries < MAX_RETRIES) {
            bgHandler.postDelayed(retry, RETRY_MS shl minOf(retries++, 4))
        }
    }

    /** Returns whether Telephony now holds what this subscription should have. */
    private fun apply(tm: TelephonyManager, subId: Int, restrict: Boolean): Boolean {
        val key = SAVED_PREFIX + subId
        val cr = context.contentResolver
        val saved = Settings.Global.getString(cr, key)?.toLongOrNull()
        if (restrict) {
            val current = tm.getAllowedNetworkTypesForReason(REASON)
            val base = saved ?: current
            val target = base and NR.inv()
            if (saved == null) Settings.Global.putString(cr, key, base.toString())
            if (current != target) {
                tm.setAllowedNetworkTypesForReason(REASON, target)
                Log.i(TAG, "sub $subId: tethering, 5G off")
            }
            return tm.getAllowedNetworkTypesForReason(REASON) == target
        } else if (saved != null) {
            tm.setAllowedNetworkTypesForReason(REASON, saved)
            // Forget the saved mask only once Telephony has taken it back; clearing it after a
            // dropped request left 5G off for good after tethering.
            if (tm.getAllowedNetworkTypesForReason(REASON) != saved) {
                Log.w(TAG, "sub $subId: Telephony did not take the mask back yet")
                return false
            }
            Settings.Global.putString(cr, key, null)
            Log.i(TAG, "sub $subId: 5G allowed again")
        }
        return true
    }

    companion object {
        private const val TAG = "TetherLte"

        /** Settings.Secure: 1 (default) keeps the modem on LTE while tethering. */
        const val SETTING_ENABLED = "picked_tether_lte"

        /** Settings.Global prefix: saved power reason mask per subscription while applied. */
        private const val SAVED_PREFIX = "picked_tether_lte_saved_"

        private const val REASON = TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_POWER
        private const val NR = TelephonyManager.NETWORK_TYPE_BITMASK_NR

        /** First retry delay; doubles each time, up to [MAX_RETRIES] tries. */
        private const val RETRY_MS = 2000L
        private const val MAX_RETRIES = 10
    }
}
