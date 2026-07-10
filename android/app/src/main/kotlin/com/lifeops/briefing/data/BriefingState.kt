package com.lifeops.briefing.data

import org.json.JSONObject

/** In-memory shape of one briefing snapshot -- what BriefingReceiver parses
 * each ntfy "briefing-data" push into, and what the widget UI renders.
 * Persisted as JSON (see toJson/fromJson) under WidgetKeys.BRIEFING_JSON. */
data class BriefingState(
    val date: String?,
    val text: String?,
    val gymThisWeek: Int? = null,
    val gymTarget: Int? = null,
    val discretionaryDollars: Int? = null,
    val courseworkHoursNext7d: Double? = null,
    val fetchedAtEpochMillis: Long? = null,
) {
    fun toJson(): String = JSONObject().apply {
        put("date", date)
        put("text", text)
        put("gymThisWeek", gymThisWeek)
        put("gymTarget", gymTarget)
        put("discretionaryDollars", discretionaryDollars)
        put("courseworkHoursNext7d", courseworkHoursNext7d)
        put("fetchedAtEpochMillis", fetchedAtEpochMillis)
    }.toString()

    companion object {
        fun empty() = BriefingState(date = null, text = null)

        fun fromJson(raw: String): BriefingState {
            val o = JSONObject(raw)
            return BriefingState(
                date = o.optString("date").takeIf { it.isNotEmpty() },
                text = o.optString("text").takeIf { it.isNotEmpty() },
                gymThisWeek = o.optInt("gymThisWeek", -1).takeIf { it >= 0 },
                gymTarget = o.optInt("gymTarget", -1).takeIf { it >= 0 },
                discretionaryDollars = o.optInt("discretionaryDollars", Int.MIN_VALUE)
                    .takeIf { it != Int.MIN_VALUE },
                courseworkHoursNext7d = o.optDouble("courseworkHoursNext7d", Double.NaN)
                    .takeIf { !it.isNaN() },
                fetchedAtEpochMillis = o.optLong("fetchedAtEpochMillis", -1L).takeIf { it >= 0 },
            )
        }

        /** Parses the {date, text, facts} JSON body carried by the ntfy
         * "briefing-data" push (same shape /api/briefing returns) into a
         * BriefingState. */
        fun fromApiResponse(raw: String, fetchedAtEpochMillis: Long): BriefingState {
            val o = JSONObject(raw)
            val facts = o.optJSONObject("facts") ?: JSONObject()
            return BriefingState(
                date = o.optString("date").takeIf { it.isNotEmpty() },
                text = o.optString("text").takeIf { it.isNotEmpty() },
                gymThisWeek = if (facts.has("gym_this_week")) facts.optInt("gym_this_week") else null,
                gymTarget = if (facts.has("gym_target")) facts.optInt("gym_target") else null,
                discretionaryDollars = if (facts.has("discretionary_dollars"))
                    facts.optInt("discretionary_dollars") else null,
                courseworkHoursNext7d = if (facts.has("coursework_hours_next_7d"))
                    facts.optDouble("coursework_hours_next_7d") else null,
                fetchedAtEpochMillis = fetchedAtEpochMillis,
            )
        }
    }
}
