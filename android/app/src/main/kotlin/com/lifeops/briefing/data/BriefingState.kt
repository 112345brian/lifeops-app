package com.lifeops.briefing.data

import org.json.JSONArray
import org.json.JSONObject

/** One entry from attention.compute()'s deterministic `reasons` list --
 * just enough (domain + severity) to render the widget's per-domain
 * severity dots. Title/action/due are intentionally not carried here; the
 * full reason detail already surfaces in the panel, the widget only needs
 * the glyph-level signal. */
data class AttentionReason(val domain: String, val severity: String)

/** In-memory shape of one briefing snapshot -- what BriefingReceiver parses
 * each ntfy "briefing-data" push into, and what the widget UI renders.
 * Persisted as JSON (see toJson/fromJson) under WidgetKeys.BRIEFING_JSON. */
data class BriefingState(
    val date: String?,
    val text: String?,
    val gymLast7d: Int? = null,
    val gymTarget: Int? = null,
    val discretionaryDollars: Int? = null,
    val courseworkHoursNext7d: Double? = null,
    val temperatureF: Int? = null,
    val weatherHighF: Int? = null,
    val weatherLowF: Int? = null,
    val weatherCondition: String? = null,
    val sleepMinutes: Int? = null,
    val partnerDaysSince: Int? = null,
    val friendDaysSince: Int? = null,
    val fetchedAtEpochMillis: Long? = null,
    val attentionState: String? = null,
    val attentionSymbol: String? = null,
    val attentionLabel: String? = null,
    val attentionHeadline: String? = null,
    val reasons: List<AttentionReason> = emptyList(),
) {
    fun toJson(): String = JSONObject().apply {
        put("date", date)
        put("text", text)
        put("gymLast7d", gymLast7d)
        put("gymTarget", gymTarget)
        put("discretionaryDollars", discretionaryDollars)
        put("courseworkHoursNext7d", courseworkHoursNext7d)
        put("temperatureF", temperatureF)
        put("weatherHighF", weatherHighF)
        put("weatherLowF", weatherLowF)
        put("weatherCondition", weatherCondition)
        put("sleepMinutes", sleepMinutes)
        put("partnerDaysSince", partnerDaysSince)
        put("friendDaysSince", friendDaysSince)
        put("fetchedAtEpochMillis", fetchedAtEpochMillis)
        put("attentionState", attentionState)
        put("attentionSymbol", attentionSymbol)
        put("attentionLabel", attentionLabel)
        put("attentionHeadline", attentionHeadline)
        put("reasons", JSONArray().apply {
            reasons.forEach { r ->
                put(JSONObject().apply {
                    put("domain", r.domain)
                    put("severity", r.severity)
                })
            }
        })
    }.toString()

    companion object {
        fun empty() = BriefingState(date = null, text = null)

        /** has()-and-not-null()-safe Int read -- optInt() alone can't tell
         * "key absent" apart from "key present with an explicit JSON null"
         * (both fall through to its default), and Python's json module
         * always writes explicit nulls for None facts values rather than
         * omitting the key, so has()-only silently reads back as 0 instead
         * of Kotlin null (confirmed 2026-07-13 via the weather fields'
         * first test run). */
        private fun JSONObject.optIntOrNull(key: String): Int? =
            if (has(key) && !isNull(key)) optInt(key) else null

        /** Same has()-and-not-null() concern as [optIntOrNull], but for
         * strings: optString() on an explicit JSON null returns the
         * literal STRING "null" (four characters, non-empty), not Kotlin
         * null, so isNotEmpty() alone doesn't catch it either. */
        private fun JSONObject.optStringOrNull(key: String): String? =
            if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

        private fun JSONObject.optDoubleOrNull(key: String): Double? =
            if (has(key) && !isNull(key)) optDouble(key) else null

        private fun JSONObject.optLongOrNull(key: String): Long? =
            if (has(key) && !isNull(key)) optLong(key) else null

        private fun parseReasons(arr: JSONArray?): List<AttentionReason> {
            if (arr == null) return emptyList()
            return (0 until arr.length()).map { i ->
                val r = arr.getJSONObject(i)
                AttentionReason(domain = r.optString("domain"), severity = r.optString("severity"))
            }
        }

        fun fromJson(raw: String): BriefingState {
            val o = JSONObject(raw)
            return BriefingState(
                date = o.optStringOrNull("date"),
                text = o.optStringOrNull("text"),
                gymLast7d = o.optIntOrNull("gymLast7d"),
                gymTarget = o.optIntOrNull("gymTarget"),
                discretionaryDollars = o.optIntOrNull("discretionaryDollars"),
                courseworkHoursNext7d = o.optDoubleOrNull("courseworkHoursNext7d"),
                temperatureF = o.optIntOrNull("temperatureF"),
                weatherHighF = o.optIntOrNull("weatherHighF"),
                weatherLowF = o.optIntOrNull("weatherLowF"),
                weatherCondition = o.optStringOrNull("weatherCondition"),
                sleepMinutes = o.optIntOrNull("sleepMinutes"),
                partnerDaysSince = o.optIntOrNull("partnerDaysSince"),
                friendDaysSince = o.optIntOrNull("friendDaysSince"),
                fetchedAtEpochMillis = o.optLongOrNull("fetchedAtEpochMillis"),
                attentionState = o.optStringOrNull("attentionState"),
                attentionSymbol = o.optStringOrNull("attentionSymbol"),
                attentionLabel = o.optStringOrNull("attentionLabel"),
                attentionHeadline = o.optStringOrNull("attentionHeadline"),
                reasons = parseReasons(o.optJSONArray("reasons")),
            )
        }

        /** Parses the {date, text, facts} JSON body carried by the ntfy
         * "briefing-data" push (same shape /api/briefing returns) into a
         * BriefingState. */
        fun fromApiResponse(raw: String, fetchedAtEpochMillis: Long): BriefingState {
            val o = JSONObject(raw)
            val facts = o.optJSONObject("facts") ?: JSONObject()
            val attention = facts.optJSONObject("attention") ?: JSONObject()
            return BriefingState(
                date = o.optStringOrNull("date"),
                text = o.optStringOrNull("text"),
                gymLast7d = facts.optIntOrNull("gym_last_7d"),
                gymTarget = facts.optIntOrNull("gym_target"),
                discretionaryDollars = facts.optIntOrNull("discretionary_dollars"),
                courseworkHoursNext7d = facts.optDoubleOrNull("coursework_hours_next_7d"),
                temperatureF = facts.optIntOrNull("temperature_f"),
                weatherHighF = facts.optIntOrNull("weather_high_f"),
                weatherLowF = facts.optIntOrNull("weather_low_f"),
                weatherCondition = facts.optStringOrNull("weather_condition"),
                sleepMinutes = facts.optIntOrNull("sleep_minutes"),
                partnerDaysSince = facts.optIntOrNull("partner_days_since"),
                friendDaysSince = facts.optIntOrNull("friend_days_since"),
                fetchedAtEpochMillis = fetchedAtEpochMillis,
                attentionState = attention.optStringOrNull("state"),
                attentionSymbol = attention.optStringOrNull("symbol"),
                attentionLabel = attention.optStringOrNull("label"),
                attentionHeadline = attention.optStringOrNull("headline"),
                reasons = parseReasons(attention.optJSONArray("reasons")),
            )
        }
    }
}
