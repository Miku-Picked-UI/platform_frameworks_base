/*
 * Copyright (C) 2026 The PenguinOS Project
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

package com.android.systemui.statusbar.backtoapp

import android.app.ActivityManager.RunningTaskInfo
import android.app.ActivityOptions
import android.app.ActivityTaskManager.INVALID_TASK_ID
import android.app.WindowConfiguration.ACTIVITY_TYPE_ASSISTANT
import android.app.WindowConfiguration.ACTIVITY_TYPE_DREAM
import android.app.WindowConfiguration.ACTIVITY_TYPE_HOME
import android.app.WindowConfiguration.ACTIVITY_TYPE_RECENTS
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import com.android.systemui.shared.system.ActivityManagerWrapper
import com.android.systemui.shared.system.TaskStackChangeListener
import com.android.systemui.shared.system.TaskStackChangeListeners
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Settings.System key: 1 shows the "back to app" label under the status bar clock. */
private const val BACK_TO_APP_INDICATOR = "back_to_app_indicator"

class BackToAppTracker private constructor(context: Context) {

    data class Target(
        val taskId: Int,
        val packageName: String,
        val label: CharSequence,
        val icon: Drawable?,
    )

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "BackToApp") }

    private val _target = MutableStateFlow<Target?>(null)
    val target: StateFlow<Target?> = _target.asStateFlow()

    private val chain = HashSet<Int>()
    private var lastTopTaskId = INVALID_TASK_ID
    @Volatile private var enabled = false

    private val listener =
        object : TaskStackChangeListener {
            override fun onTaskStackChanged() = refresh()

            override fun onTaskMovedToFront(taskInfo: RunningTaskInfo) = refresh()

            override fun onTaskRemoved(taskId: Int) {
                worker.execute {
                    chain.remove(taskId)
                    if (_target.value?.taskId == taskId) _target.value = null
                }
            }
        }

    init {
        val resolver = appContext.contentResolver
        val observer =
            object : ContentObserver(mainHandler) {
                override fun onChange(selfChange: Boolean) = readEnabled()
            }
        resolver.registerContentObserver(
            Settings.System.getUriFor(BACK_TO_APP_INDICATOR),
            false,
            observer,
            UserHandle.USER_ALL,
        )
        readEnabled()
        TaskStackChangeListeners.getInstance().registerTaskStackListener(listener)
    }

    private fun readEnabled() {
        enabled =
            Settings.System.getIntForUser(
                appContext.contentResolver,
                BACK_TO_APP_INDICATOR,
                0,
                UserHandle.USER_CURRENT,
            ) != 0
        refresh()
    }

    private fun refresh() {
        worker.execute {
            if (!enabled) {
                chain.clear()
                lastTopTaskId = INVALID_TASK_ID
                _target.value = null
                return@execute
            }
            val top = ActivityManagerWrapper.getInstance().getRunningTask() ?: return@execute
            update(top)
        }
    }

    private fun update(top: RunningTaskInfo) {
        val taskId = top.taskId
        val previous = lastTopTaskId
        if (top.isSystemSurface()) {
            chain.clear()
            lastTopTaskId = taskId
            _target.value = null
            return
        }
        val origin = top.launchedFromTaskId
        if (DEBUG) {
            Log.d(TAG, "top=$taskId ${top.topActivity} previous=$previous origin=$origin " +
                "from=${top.launchedFromPackage} chain=$chain")
        }
        if (taskId != previous) {
            val openedFromPrevious = origin != INVALID_TASK_ID && origin == previous
            if (previous != INVALID_TASK_ID && !openedFromPrevious) chain.remove(previous)
            if (openedFromPrevious) chain.add(taskId)
            lastTopTaskId = taskId
        }
        if (origin == INVALID_TASK_ID || top.launchedFromPackage == null) chain.remove(taskId)

        val packageName = top.launchedFromPackage
        if (taskId !in chain || packageName == null) {
            _target.value = null
            return
        }
        if (_target.value?.let { it.taskId == origin && it.packageName == packageName } == true) {
            return
        }
        _target.value = describe(origin, packageName, top.userId)
    }

    private fun describe(taskId: Int, packageName: String, userId: Int): Target? =
        try {
            val pm = appContext.packageManager
            val info = pm.getApplicationInfoAsUser(packageName, 0, userId)
            Target(taskId, packageName, pm.getApplicationLabel(info), pm.getApplicationIcon(info))
        } catch (e: Exception) {
            Log.w(TAG, "No app to go back to for $packageName", e)
            null
        }

    fun goBack(target: Target) {
        worker.execute {
            val options = ActivityOptions.makeBasic()
            if (ActivityManagerWrapper.getInstance().startActivityFromRecents(target.taskId, options)) {
                return@execute
            }
            appContext.packageManager.getLaunchIntentForPackage(target.packageName)?.let { intent ->
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                mainHandler.post { appContext.startActivityAsUser(intent, UserHandle.CURRENT) }
            }
            _target.value = null
        }
    }

    private fun RunningTaskInfo.isSystemSurface(): Boolean =
        when (topActivityType) {
            ACTIVITY_TYPE_HOME,
            ACTIVITY_TYPE_RECENTS,
            ACTIVITY_TYPE_ASSISTANT,
            ACTIVITY_TYPE_DREAM -> true
            else -> false
        }

    companion object {
        private const val TAG = "BackToAppTracker"
        private val DEBUG = Log.isLoggable(TAG, Log.DEBUG)

        @Volatile private var instance: BackToAppTracker? = null

        fun get(context: Context): BackToAppTracker =
            instance ?: synchronized(this) {
                instance ?: BackToAppTracker(context).also { instance = it }
            }
    }
}
