package com.lifeops.briefing.data

import org.json.JSONArray
import org.json.JSONObject

data class NextTask(val id: String, val title: String, val start: String?)

/** The widget's "what's next" snapshot -- up to a few upcoming incomplete
 * tasks. Written by NextTasksRefreshWorker (periodic pull from
 * /api/next-tasks) and by CompleteTaskAction (immediate update from the
 * fresh list a complete call returns). Persisted as JSON under
 * WidgetKeys.NEXT_TASKS_JSON. */
data class NextTasksState(
    val tasks: List<NextTask>,
    val fetchedAtEpochMillis: Long? = null,
) {
    fun toJson(): String = JSONObject().apply {
        put("tasks", JSONArray().apply {
            tasks.forEach { t ->
                put(JSONObject().apply {
                    put("id", t.id)
                    put("title", t.title)
                    put("start", t.start)
                })
            }
        })
        put("fetchedAtEpochMillis", fetchedAtEpochMillis)
    }.toString()

    companion object {
        fun empty() = NextTasksState(tasks = emptyList())

        fun fromJson(raw: String): NextTasksState {
            val o = JSONObject(raw)
            val arr = o.optJSONArray("tasks") ?: JSONArray()
            val tasks = (0 until arr.length()).map { i ->
                val t = arr.getJSONObject(i)
                NextTask(
                    id = t.getString("id"),
                    title = t.optString("title"),
                    start = t.optString("start").takeIf { it.isNotEmpty() },
                )
            }
            return NextTasksState(
                tasks = tasks,
                fetchedAtEpochMillis = o.optLong("fetchedAtEpochMillis", -1L).takeIf { it >= 0 },
            )
        }

        /** Parses the {tasks:[{id,title,start},...]} shape both
         * /api/next-tasks and /api/tasks/{id}/complete return. */
        fun fromApiResponse(raw: String, fetchedAtEpochMillis: Long): NextTasksState {
            val o = JSONObject(raw)
            val arr = o.optJSONArray("tasks") ?: JSONArray()
            val tasks = (0 until arr.length()).map { i ->
                val t = arr.getJSONObject(i)
                NextTask(
                    id = t.getString("id"),
                    title = t.optString("title"),
                    start = t.optString("start").takeIf { it.isNotEmpty() },
                )
            }
            return NextTasksState(tasks = tasks, fetchedAtEpochMillis = fetchedAtEpochMillis)
        }
    }
}
