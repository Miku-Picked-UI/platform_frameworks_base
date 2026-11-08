/*
 * SPDX-FileCopyrightText: 2026 Miku-Picked UI
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.picked.oneuisland

import android.app.StatusBarManager
import android.content.Context
import android.os.UserHandle
import android.provider.Settings
import android.service.notification.StatusBarNotification
import android.util.Log
import android.view.View
import android.view.ViewTreeObserver
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.WakefulnessLifecycle
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.shade.ShadeExpansionStateManager
import com.android.systemui.statusbar.CommandQueue
import com.android.systemui.statusbar.SysuiStatusBarStateController
import com.android.systemui.statusbar.chips.ui.model.OngoingActivityChipModel
import com.android.systemui.statusbar.notification.collection.NotifCollection
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionListener
import com.android.systemui.statusbar.notification.headsup.HeadsUpManager
import com.android.systemui.statusbar.notification.headsup.OnHeadsUpChangedListener
import com.android.systemui.statusbar.phone.ongoingcall.OngoingCallController
import com.android.systemui.statusbar.pipeline.shared.ui.model.ChipsVisibilityModel
import dagger.Lazy
import dev.flexy.dynamicisland.island.IslandController
import dev.flexy.dynamicisland.island.IslandView
import java.lang.ref.WeakReference
import java.util.function.BooleanSupplier
import javax.inject.Inject

/**
 * Runs the OneUisland dynamic island (https://github.com/csabaikende-ctrl/OneUisland, by Spottq)
 * inside SystemUI.
 *
 * OneUisland is an LSPosed module whose island code (`dev.flexy.dynamicisland.island`) is linked
 * into SystemUI as a library (vendor/miku/oneuisland). This class does natively what the module's
 * `SystemUiHooks` does with Xposed hooks on AOSP Android 16: it creates the [IslandController] and
 * feeds it the status bar view, shade, keyguard, wakefulness, heads-up, window state, notification
 * and call signals. SystemUI code calls the companion helpers where the module hooked methods
 * (status bar view attach/detach, heads-up decisions, status bar chip visibility).
 *
 * One UI-only parts of the module (Now Bar, Samsung cast/call controllers, One UI Home exit
 * animation, shade card restyling) have no AOSP counterpart and are not used.
 *
 * Every call into the island is guarded, so a problem in the island can never take SystemUI down.
 * The island is on by default; [Settings.System.ONEUISLAND_ENABLED] = 0 turns it off after the
 * next SystemUI start.
 */
@SysUISingleton
class OneUislandHost
@Inject
constructor(
    private val context: Context,
    private val shadeExpansionStateManager: ShadeExpansionStateManager,
    private val statusBarStateController: SysuiStatusBarStateController,
    private val wakefulnessLifecycle: WakefulnessLifecycle,
    private val headsUpManager: HeadsUpManager,
    private val commandQueue: CommandQueue,
    private val notifCollection: NotifCollection,
    private val ongoingCallController: Lazy<OngoingCallController>,
) : CoreStartable {

    override fun start() {
        val enabled =
            Settings.System.getIntForUser(
                context.contentResolver,
                Settings.System.ONEUISLAND_ENABLED,
                1,
                UserHandle.USER_CURRENT,
            ) != 0
        if (!enabled) {
            Log.i(TAG, "OneUisland is turned off")
            return
        }
        val controller =
            guard("init") {
                IslandController.init(context.applicationContext, OneUislandHost::class.java.classLoader)
            } ?: return
        island = controller
        Log.i(TAG, "OneUisland started")

        guard("signals") { registerSignals(controller) }

        // The status bar view may have been attached before this CoreStartable ran.
        statusBarView?.get()?.let { onStatusBarViewAttached(it) }
    }

    private fun registerSignals(c: IslandController) {
        // Shade expansion and panel state.
        shadeExpansionStateManager.addExpansionListener { e ->
            guard("shade expansion") {
                c.setShadeExpansion(if (!e.expanded && !e.tracking) 0f else e.fraction)
            }
        }
        shadeExpansionStateManager.addStateListener { state ->
            guard("panel state") {
                c.setPanelState(state)
                if (state == STATE_CLOSED) c.setShadeExpansion(0f)
            }
        }

        // Keyguard state and dozing.
        statusBarStateController.addCallback(
            object : StatusBarStateController.StateListener {
                override fun onStateChanged(newState: Int) {
                    guard("bar state") { c.setBarState(newState) }
                }

                override fun onDozingChanged(isDozing: Boolean) {
                    guard("dozing") { c.setDozing(isDozing) }
                }
            }
        )
        c.setBarState(statusBarStateController.state)
        c.setDozing(statusBarStateController.isDozing)

        // Screen on/off.
        wakefulnessLifecycle.addObserver(
            object : WakefulnessLifecycle.Observer {
                override fun onStartedGoingToSleep() {
                    guard("going to sleep") { c.onStartedGoingToSleep() }
                }

                override fun onStartedWakingUp() {
                    guard("waking up") { c.onStartedWakingUp() }
                }
            }
        )

        // Heads-up notifications.
        val hum = WeakReference(headsUpManager)
        IslandView.headsUpProbe = BooleanSupplier {
            val h = hum.get()
            h != null &&
                (runCatching { h.isTrackingHeadsUp().value }.getOrDefault(false) ||
                    h.hasNotifications())
        }
        headsUpManager.addListener(
            object : OnHeadsUpChangedListener {
                override fun onHeadsUpPinnedModeChanged(inPinnedMode: Boolean) {
                    guard("heads-up pinned") { c.setHeadsUpPinned(inPinnedMode) }
                }
            }
        )

        // Status bar window shown/hidden (immersive mode).
        commandQueue.addCallback(
            object : CommandQueue.Callbacks {
                override fun setWindowState(displayId: Int, window: Int, state: Int) {
                    if (displayId != 0 || window != StatusBarManager.WINDOW_STATUS_BAR) return
                    guard("window state") { c.setStatusBarWindowState(state) }
                }
            }
        )

        // Notifications: live activities and calls come from the notification collection.
        c.setNotifCollection(notifCollection)
        notifCollection.addCollectionListener(
            object : NotifCollectionListener {
                override fun onEntryAdded(entry: NotificationEntry) = refresh()

                override fun onEntryUpdated(entry: NotificationEntry) = refresh()

                override fun onEntryRemoved(entry: NotificationEntry, reason: Int) = refresh()

                override fun onRankingApplied() = refresh()
            }
        )

        // Ongoing call state.
        guard("call controller") { c.setCallController(ongoingCallController.get()) }
    }

    private fun refresh() {
        guard("refresh") { island?.requestRefresh() }
    }

    companion object {
        private const val TAG = "OneUislandHost"
        private const val STATE_CLOSED = 0
        private const val TAG_CHIP_HIDER = 0x7e0c4155

        @Volatile private var island: IslandController? = null
        @Volatile private var statusBarView: WeakReference<View>? = null

        private inline fun <T> guard(what: String, block: () -> T): T? =
            try {
                block()
            } catch (t: Throwable) {
                Log.e(TAG, "OneUisland: $what failed", t)
                null
            }

        /** Whether the island is running (and replaces the status bar chips and heads-ups). */
        @JvmStatic fun isActive(): Boolean = island != null

        /** Called by PhoneStatusBarViewController when the status bar view is attached. */
        @JvmStatic
        fun onStatusBarViewAttached(view: View) {
            statusBarView = WeakReference(view)
            val c = island ?: return
            guard("status bar attached") {
                c.onStatusBarViewAttached(view)
                hideAospChips(view)
            }
        }

        /** Called by PhoneStatusBarViewController when the status bar view is detached. */
        @JvmStatic
        fun onStatusBarViewDetached(view: View) {
            if (statusBarView?.get() === view) statusBarView = null
            val c = island ?: return
            guard("status bar detached") { c.onStatusBarViewDetached(view) }
        }

        /**
         * Offers a notification that SystemUI wants to show as a heads-up to the island. Returns
         * true if the island shows it instead, in which case no heads-up is shown.
         */
        @JvmStatic
        fun offerHeadsUp(sbn: StatusBarNotification?): Boolean {
            val c = island ?: return false
            if (sbn == null) return false
            return guard("heads-up") { c.offerHeadsUp(sbn) } ?: false
        }

        /**
         * The island shows calls, screen recording (with its countdown), casting and promoted
         * notifications itself, so the status bar chips are never allowed while it runs. A
         * screen recording countdown chip is forwarded to the island.
         */
        @JvmStatic
        fun adjustChips(model: ChipsVisibilityModel): ChipsVisibilityModel {
            val c = island ?: return model
            guard("chips") {
                val countdown =
                    model.chips.active.firstNotNullOfOrNull {
                        (it.content as? OngoingActivityChipModel.Content.Countdown)
                            ?.secondsUntilStarted
                    } ?: -1L
                c.setRecordingCountdown(countdown)
            }
            return if (model.areChipsAllowed) model.copy(areChipsAllowed = false) else model
        }

        /**
         * The view-based status bar chips for calls, promoted notifications, screen recording and
         * casting. The island shows these itself, so they are kept hidden.
         */
        private fun hideAospChips(statusBar: View) {
            val res = statusBar.resources
            val pkg = statusBar.context.packageName
            for (name in arrayOf("ongoing_activity_chip_primary", "ongoing_activity_chip_secondary")) {
                val id = res.getIdentifier(name, "id", pkg)
                if (id == 0) continue
                val chip = statusBar.findViewById<View>(id) ?: continue
                if (chip.getTag(TAG_CHIP_HIDER) != null) continue
                chip.setTag(TAG_CHIP_HIDER, true)
                chip.visibility = View.GONE
                chip.viewTreeObserver.addOnPreDrawListener(
                    ViewTreeObserver.OnPreDrawListener {
                        if (island != null && chip.visibility != View.GONE) {
                            chip.visibility = View.GONE
                        }
                        true
                    }
                )
            }
        }
    }
}
