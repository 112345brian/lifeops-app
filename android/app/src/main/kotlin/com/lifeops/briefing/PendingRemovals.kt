package com.lifeops.briefing

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import com.lifeops.briefing.data.NextTask
import org.json.JSONException
import org.json.JSONObject

/**
 * Tracks tasks completed locally via a widget checkbox tap that aren't yet
 * confirmed reflected server-side -- both so a concurrent full refresh (the
 * 15-min periodic pull, an FCM push, or the immediate one-time pull on
 * widget placement/Settings Save) can't silently resurrect a task the user
 * just checked off, AND so a genuinely stuck/failed completion eventually
 * un-hides again instead of staying invisible forever.
 *
 * The real completion happens asynchronously: CompleteTaskAction posts a
 * "complete:<id>" ntfy signal, and the server's ingest() cycle (which runs
 * every ~2 min -- see runner.py) is what actually calls FlowSavvy. Each
 * pending entry records the wall-clock time of the optimistic tap plus
 * enough of the original task (title/start) to restore it locally without
 * a network round trip, per two thresholds:
 *
 *  - GRACE_MS: how long a fresh snapshot that still contains the task is
 *    treated as "server hasn't caught up yet" rather than "this genuinely
 *    failed." Before this elapses, presence in a fresh snapshot is ignored
 *    (kept masked) -- a pull or push landing before ingest()'s ~2-min cycle
 *    catches up is the *expected* case for the ntfy fallback path, not a
 *    failure signal, and un-masking on it would just flicker the row back
 *    for ~2 min before it (correctly) disappears again.
 *  - HARD_TIMEOUT_MS: absolute cap, matching the "revert after ~10 minutes"
 *    behavior. If nothing has confirmed either way by this point, the task
 *    is restored to the visible list from its stored title/start and the
 *    pending record is dropped -- this works even with zero connectivity,
 *    since it doesn't depend on a fresh fetch succeeding.
 */
private const val GRACE_MS = 3 * 60 * 1000L // margin over the ~2-min ingest cycle
private const val HARD_TIMEOUT_MS = 10 * 60 * 1000L

/** One task optimistically hidden at [tappedAtMillis], with enough of its
 * original shape to restore it to the visible list without a fresh fetch. */
data class PendingCompletion(val task: NextTask, val tappedAtMillis: Long) {
    fun isPastGrace(nowMillis: Long): Boolean = nowMillis - tappedAtMillis >= GRACE_MS
    fun isExpired(nowMillis: Long): Boolean = nowMillis - tappedAtMillis >= HARD_TIMEOUT_MS
}

object PendingRemovals {

    /** Entries not yet past [HARD_TIMEOUT_MS] as of [nowMillis], keyed by
     * task id -- callers should drop these ids from any fresh, server-derived
     * task list before persisting it (unless past grace; see
     * [PendingCompletion.isPastGrace] and NextTasksRefreshWorker's use of it). */
    fun readActive(prefs: Preferences, nowMillis: Long): Map<String, PendingCompletion> {
        val active = mutableMapOf<String, PendingCompletion>()
        val all = readAll(prefs)
        for (key in all.keys()) {
            val entry = all.optJSONObject(key) ?: continue
            val completion = parseEntry(key, entry)
            if (!completion.isExpired(nowMillis)) active[key] = completion
        }
        return active
    }

    /** Marks [task] as pending-removed as of [nowMillis]. Also prunes any
     * already-expired entries while it's at it so this map can't grow
     * unbounded across many completions -- the periodic worker's
     * [takeExpired] call is the primary sweep; this is just a backstop for
     * whatever it hasn't gotten to yet. */
    fun add(prefs: MutablePreferences, task: NextTask, nowMillis: Long) {
        val current = readAll(prefs)
        pruneExpired(current, nowMillis)
        current.put(task.id, entryJson(task, nowMillis))
        prefs[WidgetKeys.PENDING_REMOVED_JSON] = current.toString()
    }

    /** Clears pending records for [confirmedIds] -- call when a fresh
     * snapshot definitively confirms them complete (absent entirely) or, once
     * past grace, genuinely failed (still present) -- either way there's
     * nothing left to mask or restore. */
    fun clearConfirmed(prefs: MutablePreferences, confirmedIds: Set<String>) {
        if (confirmedIds.isEmpty()) return
        val current = readAll(prefs)
        var changed = false
        for (id in confirmedIds) {
            if (current.has(id)) {
                current.remove(id)
                changed = true
            }
        }
        if (changed) prefs[WidgetKeys.PENDING_REMOVED_JSON] = current.toString()
    }

    /** Removes and returns the tasks whose pending record is past
     * [HARD_TIMEOUT_MS] as of [nowMillis] -- callers should merge these back
     * into the visible task list (see NextTasksRefreshWorker's revert step). */
    fun takeExpired(prefs: MutablePreferences, nowMillis: Long): List<NextTask> {
        val current = readAll(prefs)
        val expired = mutableListOf<NextTask>()
        for (key in current.keys().asSequence().toList()) {
            val entry = current.optJSONObject(key) ?: continue
            val completion = parseEntry(key, entry)
            if (completion.isExpired(nowMillis)) {
                expired.add(completion.task)
                current.remove(key)
            }
        }
        if (expired.isNotEmpty()) prefs[WidgetKeys.PENDING_REMOVED_JSON] = current.toString()
        return expired
    }

    private fun pruneExpired(current: JSONObject, nowMillis: Long) {
        for (key in current.keys().asSequence().toList()) {
            val entry = current.optJSONObject(key) ?: continue
            if (parseEntry(key, entry).isExpired(nowMillis)) current.remove(key)
        }
    }

    private fun parseEntry(id: String, entry: JSONObject): PendingCompletion {
        return PendingCompletion(
            task = NextTask(
                id = id,
                title = entry.optString("title"),
                start = entry.optString("start").takeIf { it.isNotEmpty() },
            ),
            tappedAtMillis = entry.optLong("tappedAt", 0L),
        )
    }

    private fun entryJson(task: NextTask, nowMillis: Long): JSONObject = JSONObject().apply {
        put("tappedAt", nowMillis)
        put("title", task.title)
        put("start", task.start)
    }

    private fun readAll(prefs: Preferences): JSONObject {
        val raw = prefs[WidgetKeys.PENDING_REMOVED_JSON] ?: return JSONObject()
        return try {
            JSONObject(raw)
        } catch (e: JSONException) {
            JSONObject()
        }
    }
}
