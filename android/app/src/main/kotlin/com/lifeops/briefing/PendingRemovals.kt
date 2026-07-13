package com.lifeops.briefing

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import org.json.JSONException
import org.json.JSONObject

/**
 * Tracks tasks completed locally via a widget checkbox tap that aren't yet
 * confirmed reflected server-side, so a concurrent full refresh (the 15-min
 * periodic pull, or the immediate one-time pull on widget placement/Settings
 * Save) can't silently resurrect a task the user just checked off.
 *
 * The real completion happens asynchronously: CompleteTaskAction posts a
 * "complete:<id>" ntfy signal, and the server's ingest() cycle (up to ~2 min
 * away) is what actually calls FlowSavvy. If a full next-tasks refresh lands
 * in that window using pre-completion server data, it would otherwise
 * overwrite the optimistic local removal -- a real, confirmed race (not
 * data corruption, since DataStore writes are serialized, but a visibly
 * confusing "task un-checks itself" glitch).
 */
private const val PENDING_TTL_MS = 3 * 60 * 1000L // margin over the ~2-min ingest cycle

object PendingRemovals {
    /** IDs still within their TTL as of [nowMillis] -- callers should drop
     * these from any fresh, server-derived task list before persisting it. */
    fun readActive(prefs: Preferences, nowMillis: Long): Set<String> {
        val raw = prefs[WidgetKeys.PENDING_REMOVED_JSON] ?: return emptySet()
        return try {
            val o = JSONObject(raw)
            val active = mutableSetOf<String>()
            for (key in o.keys()) {
                if (o.optLong(key, 0L) > nowMillis) active.add(key)
            }
            active
        } catch (e: JSONException) {
            emptySet()
        }
    }

    /** Marks [taskId] as pending-removed for [PENDING_TTL_MS], and prunes any
     * already-expired entries while it's at it so this map can't grow
     * unbounded across many completions. */
    fun add(prefs: MutablePreferences, taskId: String, nowMillis: Long) {
        val current = try {
            prefs[WidgetKeys.PENDING_REMOVED_JSON]?.let { JSONObject(it) } ?: JSONObject()
        } catch (e: JSONException) {
            JSONObject()
        }
        for (key in current.keys().asSequence().toList()) {
            if (current.optLong(key, 0L) <= nowMillis) current.remove(key)
        }
        current.put(taskId, nowMillis + PENDING_TTL_MS)
        prefs[WidgetKeys.PENDING_REMOVED_JSON] = current.toString()
    }
}
