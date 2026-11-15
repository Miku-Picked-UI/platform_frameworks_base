/*
 * SPDX-FileCopyrightText: 2026 Miku-Picked UI
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.picked.clipboard

import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.os.UserHandle
import android.provider.Settings
import android.util.AtomicFile
import android.util.Log
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * Miku-Picked UI: clipboard history, recorded by SystemUI's ClipboardListener (which sees every
 * copy, whatever the keyboard) and shown by the Clipboard Quick Settings tile.
 *
 * Only text is kept; clips the source app marks sensitive (ClipDescription.EXTRA_IS_SENSITIVE,
 * e.g. passwords from password managers) are never stored. Up to [MAX_ITEMS] unpinned entries,
 * pinned ones stay until removed. Stored per user in SystemUI's credential encrypted storage,
 * so nothing is readable before the first unlock. Settings.Secure picked_clipboard_history
 * (on by default) turns recording off; the tile's "Clear all" empties it.
 */
object ClipboardHistory {

    private const val TAG = "PickedClipboardHistory"
    const val SETTING = "picked_clipboard_history"
    private const val MAX_ITEMS = 20
    private const val MAX_CHARS = 8000

    data class Entry(val text: String, val time: Long, val pinned: Boolean)

    private val cache = HashMap<Int, MutableList<Entry>>()

    fun isEnabled(context: Context, userId: Int): Boolean =
        Settings.Secure.getIntForUser(context.contentResolver, SETTING, 1, userId) != 0

    private fun file(context: Context, userId: Int) =
        AtomicFile(File(context.filesDir, "picked_clipboard_history_$userId.json"))

    @Synchronized
    fun entries(context: Context, userId: Int): List<Entry> = load(context, userId).toList()

    @Synchronized
    fun record(context: Context, userId: Int, clip: ClipData?) {
        if (clip == null || clip.itemCount == 0 || !isEnabled(context, userId)) return
        val extras = clip.description?.extras
        if (extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false) == true) return
        val text =
            try {
                clip.getItemAt(0).coerceToText(context)?.toString()
            } catch (e: Exception) {
                null
            }?.trim()
        if (text.isNullOrEmpty()) return
        val list = load(context, userId)
        val existing = list.firstOrNull { it.text == text }
        list.removeAll { it.text == text }
        list.add(0, Entry(text.take(MAX_CHARS), System.currentTimeMillis(),
            existing?.pinned == true))
        // Trim unpinned entries beyond the limit.
        var unpinned = 0
        list.removeAll { !it.pinned && ++unpinned > MAX_ITEMS }
        save(context, userId, list)
    }

    @Synchronized
    fun setPinned(context: Context, userId: Int, text: String, pinned: Boolean) {
        val list = load(context, userId)
        val i = list.indexOfFirst { it.text == text }
        if (i < 0) return
        list[i] = list[i].copy(pinned = pinned)
        save(context, userId, list)
    }

    @Synchronized
    fun remove(context: Context, userId: Int, text: String) {
        val list = load(context, userId)
        if (list.removeAll { it.text == text }) save(context, userId, list)
    }

    /** Removes everything that is not pinned, or everything with [includePinned]. */
    @Synchronized
    fun clear(context: Context, userId: Int, includePinned: Boolean) {
        val list = load(context, userId)
        list.removeAll { includePinned || !it.pinned }
        save(context, userId, list)
    }

    private fun load(context: Context, userId: Int): MutableList<Entry> {
        cache[userId]?.let { return it }
        val list = ArrayList<Entry>()
        try {
            val f = file(context, userId)
            if (f.baseFile.exists()) {
                val json = JSONArray(String(f.readFully(), Charsets.UTF_8))
                for (i in 0 until json.length()) {
                    val o = json.getJSONObject(i)
                    list += Entry(o.getString("t"), o.optLong("d"), o.optBoolean("p"))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cannot read the clipboard history", e)
        }
        cache[userId] = list
        return list
    }

    private fun save(context: Context, userId: Int, list: List<Entry>) {
        val json = JSONArray()
        for (e in list) json.put(JSONObject().put("t", e.text).put("d", e.time).put("p", e.pinned))
        val f = file(context, userId)
        val out =
            try {
                f.startWrite()
            } catch (e: Exception) {
                Log.w(TAG, "Cannot write the clipboard history", e)
                return
            }
        try {
            out.write(json.toString().toByteArray(Charsets.UTF_8))
            f.finishWrite(out)
        } catch (e: Exception) {
            f.failWrite(out)
        }
    }

    /** The current user, for callers without a UserTracker. */
    fun currentUser(): Int = UserHandle.myUserId()
}
