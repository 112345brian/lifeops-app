package com.lifeops.briefing.data

import org.json.JSONArray
import org.json.JSONObject

data class NextTask(val id: String, val title: String, val start: String?)

/** A real (timed, non-all-day) calendar event happening today -- e.g. "Jane
 * BBQ at Papa's" -- shown above the next-tasks list regardless of whether
 * it's tracked as a "paid event" anywhere else. [start] is an ISO
 * local-datetime string ("2026-07-12T18:00:00"), same shape as [NextTask]. */
data class TodayEvent(val title: String, val start: String?)

/** The widget's "what's next" snapshot -- today's real calendar events plus
 * up to a few upcoming incomplete tasks. Written by NextTasksRefreshWorker
 * (periodic pull from /api/next-tasks) and by CompleteTaskAction (immediate
 * update from the fresh list a complete call returns). Persisted as JSON
 * under WidgetKeys.NEXT_TASKS_JSON. */
data class NextTasksState(
    val tasks: List<NextTask>,
    val events: List<TodayEvent> = emptyList(),
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
        put("events", JSONArray().apply {
            events.forEach { e ->
                put(JSONObject().apply {
                    put("title", e.title)
                    put("start", e.start)
                })
            }
        })
        put("fetchedAtEpochMillis", fetchedAtEpochMillis)
    }.toString()

    companion object {
        fun empty() = NextTasksState(tasks = emptyList())

        private fun parseTasks(o: JSONObject): List<NextTask> {
            val arr = o.optJSONArray("tasks") ?: JSONArray()
            return (0 until arr.length()).map { i ->
                val t = arr.getJSONObject(i)
                NextTask(
                    id = t.getString("id"),
                    title = t.optString("title"),
                    start = t.optString("start").takeIf { it.isNotEmpty() },
                )
            }
        }

        private fun parseEvents(o: JSONObject): List<TodayEvent> {
            val arr = o.optJSONArray("events") ?: JSONArray()
            return (0 until arr.length()).map { i ->
                val e = arr.getJSONObject(i)
                TodayEvent(
                    title = e.optString("title"),
                    start = e.optString("start").takeIf { it.isNotEmpty() },
                )
            }
        }

        fun fromJson(raw: String): NextTasksState {
            val o = JSONObject(raw)
            return NextTasksState(
                tasks = parseTasks(o),
                events = parseEvents(o),
                fetchedAtEpochMillis = o.optLong("fetchedAtEpochMillis", -1L).takeIf { it >= 0 },
            )
        }

        /** Parses the {tasks:[{id,title,start},...], events:[{title,start},...]}
         * shape /api/next-tasks returns. */
        fun fromApiResponse(raw: String, fetchedAtEpochMillis: Long): NextTasksState {
            val o = JSONObject(raw)
            return NextTasksState(tasks = parseTasks(o), events = parseEvents(o), fetchedAtEpochMillis = fetchedAtEpochMillis)
        }
    }
}
