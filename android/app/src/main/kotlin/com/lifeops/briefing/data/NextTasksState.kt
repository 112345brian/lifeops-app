package com.lifeops.briefing.data

import org.json.JSONArray
import org.json.JSONObject

data class NextTask(val id: String, val title: String, val start: String?)

/** A real (timed, non-all-day) calendar event happening today -- e.g. "Jane
 * BBQ at Papa's" -- shown above the next-tasks list regardless of whether
 * it's tracked as a "paid event" anywhere else. [start] is an ISO
 * local-datetime string ("2026-07-12T18:00:00"), same shape as [NextTask]. */
data class TodayEvent(val title: String, val start: String?)

/** The widget's gym ring: [fill] (0..1) is the pure trailing-7-day
 * adherence ratio -- it only grows as real sessions accumulate, never from
 * completing today's session alone. [color] ("red"/"yellow"/"green") is a
 * separate same-day action signal, decoupled from fill by design -- see
 * gather.gym_ring's docstring on the server for the full state machine. */
data class GymRing(
    val fill: Float,
    val color: String,
    val gymLast7d: Int = 0,
    val gymTarget: Int = 0,
    val todayDone: Boolean = false,
)

/** Current conditions, refreshed on the same ~15-min pull as [GymRing] --
 * see web.py's _tasks_and_events docstring for why weather moved here
 * (2026-07-15) instead of only ever being computed once/day inside
 * run_briefing's BriefingState snapshot. Any field can be null (matches
 * weather.current()'s own "unconfigured or NWS unreachable" contract). */
data class WeatherInfo(
    val temperatureF: Int?,
    val highF: Int?,
    val lowF: Int?,
    val condition: String?,
)

/** The widget's "what's next" snapshot -- today's real calendar events plus
 * up to a few upcoming incomplete tasks. Written by NextTasksRefreshWorker
 * (periodic pull from /api/next-tasks) and by CompleteTaskAction (immediate
 * update from the fresh list a complete call returns). Persisted as JSON
 * under WidgetKeys.NEXT_TASKS_JSON. */
data class NextTasksState(
    val tasks: List<NextTask>,
    val events: List<TodayEvent> = emptyList(),
    val gymRing: GymRing? = null,
    val weather: WeatherInfo? = null,
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
        gymRing?.let { r ->
            put("gym_ring", JSONObject().apply {
                put("fill", r.fill.toDouble())
                put("color", r.color)
                put("gym_last_7d", r.gymLast7d)
                put("gym_target", r.gymTarget)
                put("today_done", r.todayDone)
            })
        }
        weather?.let { w ->
            put("weather", JSONObject().apply {
                put("temperature_f", w.temperatureF)
                put("high_f", w.highF)
                put("low_f", w.lowF)
                put("condition", w.condition)
            })
        }
        put("fetchedAtEpochMillis", fetchedAtEpochMillis)
    }.toString()

    companion object {
        fun empty() = NextTasksState(tasks = emptyList())

        // Same has()-and-not-null() concern BriefingState.kt's own
        // optIntOrNull/optStringOrNull document -- optInt/optString on an
        // explicit JSON null (weather.current() returns None for any field
        // it can't determine) silently reads back as 0/"null" instead of
        // Kotlin null.
        private fun JSONObject.optIntOrNull(key: String): Int? =
            if (has(key) && !isNull(key)) optInt(key) else null

        private fun JSONObject.optStringOrNull(key: String): String? =
            if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

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

        private fun parseGymRing(o: JSONObject): GymRing? {
            val r = o.optJSONObject("gym_ring") ?: return null
            return GymRing(
                fill = r.optDouble("fill", 0.0).toFloat(),
                color = r.optString("color", "red"),
                gymLast7d = r.optInt("gym_last_7d", 0),
                gymTarget = r.optInt("gym_target", 0),
                todayDone = r.optBoolean("today_done", false),
            )
        }

        /** null if the key is absent/an explicit JSON null (weather.current()
         * returns None when unconfigured or NWS is unreachable) -- NOT the
         * same as "every field inside happens to be null," which still
         * parses to a real WeatherInfo(all nulls). */
        private fun parseWeather(o: JSONObject): WeatherInfo? {
            val w = o.optJSONObject("weather") ?: return null
            return WeatherInfo(
                temperatureF = w.optIntOrNull("temperature_f"),
                highF = w.optIntOrNull("high_f"),
                lowF = w.optIntOrNull("low_f"),
                condition = w.optStringOrNull("condition"),
            )
        }

        fun fromJson(raw: String): NextTasksState {
            val o = JSONObject(raw)
            return NextTasksState(
                tasks = parseTasks(o),
                events = parseEvents(o),
                gymRing = parseGymRing(o),
                weather = parseWeather(o),
                fetchedAtEpochMillis = o.optLong("fetchedAtEpochMillis", -1L).takeIf { it >= 0 },
            )
        }

        /** Parses the {tasks:[{id,title,start},...], events:[{title,start},...],
         * gym_ring:{fill,color,...}, weather:{temperature_f,high_f,low_f,condition}}
         * shape /api/next-tasks returns. */
        fun fromApiResponse(raw: String, fetchedAtEpochMillis: Long): NextTasksState {
            val o = JSONObject(raw)
            return NextTasksState(
                tasks = parseTasks(o),
                events = parseEvents(o),
                gymRing = parseGymRing(o),
                weather = parseWeather(o),
                fetchedAtEpochMillis = fetchedAtEpochMillis,
            )
        }
    }
}
