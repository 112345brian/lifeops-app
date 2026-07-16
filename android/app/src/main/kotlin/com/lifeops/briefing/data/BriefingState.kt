package com.lifeops.briefing.data

import org.json.JSONArray
import org.json.JSONObject

/** One entry from attention.compute()'s deterministic `reasons` list --
 * just enough (domain + severity) to render the widget's per-domain
 * severity dots. Title/action/due are intentionally not carried here; the
 * full reason detail already surfaces in the panel, the widget only needs
 * the glyph-level signal. */
data class AttentionReason(val domain: String, val severity: String)

/** One entry from the server's deterministic notable_events.py -- an
 * infrequent/one-off calendar event worth a heads-up, upcoming in the
 * rolling next-7-days window (NOT a fixed calendar week -- see
 * notable_events.upcoming_notable_events's docstring), as opposed to
 * TodayEvent (every timed event happening today regardless of how often
 * it recurs). [date] is an ISO
 * calendar date ("2026-07-18"); [weekday] is already spelled out
 * server-side ("Saturday") so the widget never needs its own date-math.
 * [start] is the raw startTime timestamp (same field TodayEvent already
 * carries) -- nullable since older/malformed server responses may not have
 * it; the widget falls back to day-only when it's absent rather than
 * hiding the event. */
data class NotableEvent(val title: String, val date: String, val weekday: String, val start: String? = null)
data class YnabCategoryBalance(val name: String, val dollars: Int)

/** In-memory shape of one briefing snapshot -- what BriefingReceiver parses
 * each ntfy "briefing-data" push into, and what the widget UI renders.
 * Persisted as JSON (see toJson/fromJson) under WidgetKeys.BRIEFING_JSON. */
data class BriefingState(
    val date: String?,
    val text: String?,
    val gymLast7d: Int? = null,
    val gymTarget: Int? = null,
    val discretionaryDollars: Int? = null,
    val discretionaryCurrentDollars: Int? = null,
    val ynabCategoryBalances: List<YnabCategoryBalance> = emptyList(),
    /** Just today's (days_until == 0) earmarked event costs -- what's safe
     * to spend on tonight's already-budgeted plans, as opposed to
     * [discretionaryDollars] which nets ALL upcoming plans (including
     * today's) against the balance and reads as "broke" mid-outing even
     * when the money for that exact outing was already set aside
     * (confirmed 2026-07-15: "if I check my phone and see -125, I won't
     * spend anything, but that doesn't work if I've already budgeted for
     * this specific outing"). See gather.spend_input's docstring. */
    val discretionaryTodayDollars: Int? = null,
    val courseworkHoursNext7d: Double? = null,
    val temperatureF: Int? = null,
    val weatherHighF: Int? = null,
    val weatherLowF: Int? = null,
    val weatherCondition: String? = null,
    val sleepMinutes: Int? = null,
    val partnerDaysSince: Int? = null,
    val friendDaysSince: Int? = null,
    val partnerDaysUntil: Int? = null,
    val friendDaysUntil: Int? = null,
    val fetchedAtEpochMillis: Long? = null,
    val attentionState: String? = null,
    val attentionSymbol: String? = null,
    val attentionLabel: String? = null,
    val attentionHeadline: String? = null,
    val reasons: List<AttentionReason> = emptyList(),
    val notableEvents: List<NotableEvent> = emptyList(),
) {
    fun toJson(): String = JSONObject().apply {
        put("date", date)
        put("text", text)
        put("gymLast7d", gymLast7d)
        put("gymTarget", gymTarget)
        put("discretionaryDollars", discretionaryDollars)
        put("discretionaryCurrentDollars", discretionaryCurrentDollars)
        put("ynabCategoryBalances", JSONArray().apply {
            ynabCategoryBalances.forEach { c ->
                put(JSONObject().apply {
                    put("name", c.name)
                    put("dollars", c.dollars)
                })
            }
        })
        put("discretionaryTodayDollars", discretionaryTodayDollars)
        put("courseworkHoursNext7d", courseworkHoursNext7d)
        put("temperatureF", temperatureF)
        put("weatherHighF", weatherHighF)
        put("weatherLowF", weatherLowF)
        put("weatherCondition", weatherCondition)
        put("sleepMinutes", sleepMinutes)
        put("partnerDaysSince", partnerDaysSince)
        put("friendDaysSince", friendDaysSince)
        put("partnerDaysUntil", partnerDaysUntil)
        put("friendDaysUntil", friendDaysUntil)
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
        put("notableEvents", JSONArray().apply {
            notableEvents.forEach { e ->
                put(JSONObject().apply {
                    put("title", e.title)
                    put("date", e.date)
                    put("weekday", e.weekday)
                    put("start", e.start)
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

        private fun parseNotableEvents(arr: JSONArray?): List<NotableEvent> {
            if (arr == null) return emptyList()
            return (0 until arr.length()).map { i ->
                val e = arr.getJSONObject(i)
                NotableEvent(title = e.optString("title"), date = e.optString("date"),
                    weekday = e.optString("weekday"), start = e.optStringOrNull("start"))
            }
        }

        private fun parseYnabCategoryBalances(arr: JSONArray?): List<YnabCategoryBalance> {
            if (arr == null) return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                val c = arr.getJSONObject(i)
                val name = c.optStringOrNull("name") ?: return@mapNotNull null
                YnabCategoryBalance(name = name, dollars = c.optInt("dollars"))
            }
        }

        private fun parseYnabCategoryBalanceObject(o: JSONObject?): List<YnabCategoryBalance> {
            if (o == null) return emptyList()
            return o.keys().asSequence().map { name ->
                YnabCategoryBalance(name = name, dollars = o.optInt(name))
            }.sortedBy { it.name.lowercase() }.toList()
        }

        fun fromJson(raw: String): BriefingState {
            val o = JSONObject(raw)
            return BriefingState(
                date = o.optStringOrNull("date"),
                text = o.optStringOrNull("text"),
                gymLast7d = o.optIntOrNull("gymLast7d"),
                gymTarget = o.optIntOrNull("gymTarget"),
                discretionaryDollars = o.optIntOrNull("discretionaryDollars"),
                discretionaryCurrentDollars = o.optIntOrNull("discretionaryCurrentDollars"),
                ynabCategoryBalances = parseYnabCategoryBalances(o.optJSONArray("ynabCategoryBalances")),
                discretionaryTodayDollars = o.optIntOrNull("discretionaryTodayDollars"),
                courseworkHoursNext7d = o.optDoubleOrNull("courseworkHoursNext7d"),
                temperatureF = o.optIntOrNull("temperatureF"),
                weatherHighF = o.optIntOrNull("weatherHighF"),
                weatherLowF = o.optIntOrNull("weatherLowF"),
                weatherCondition = o.optStringOrNull("weatherCondition"),
                sleepMinutes = o.optIntOrNull("sleepMinutes"),
                partnerDaysSince = o.optIntOrNull("partnerDaysSince"),
                friendDaysSince = o.optIntOrNull("friendDaysSince"),
                partnerDaysUntil = o.optIntOrNull("partnerDaysUntil"),
                friendDaysUntil = o.optIntOrNull("friendDaysUntil"),
                fetchedAtEpochMillis = o.optLongOrNull("fetchedAtEpochMillis"),
                attentionState = o.optStringOrNull("attentionState"),
                attentionSymbol = o.optStringOrNull("attentionSymbol"),
                attentionLabel = o.optStringOrNull("attentionLabel"),
                attentionHeadline = o.optStringOrNull("attentionHeadline"),
                reasons = parseReasons(o.optJSONArray("reasons")),
                notableEvents = parseNotableEvents(o.optJSONArray("notableEvents")),
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
                discretionaryCurrentDollars = facts.optIntOrNull("discretionary_current_dollars"),
                ynabCategoryBalances = parseYnabCategoryBalanceObject(facts.optJSONObject("ynab_category_balances")),
                discretionaryTodayDollars = facts.optIntOrNull("discretionary_today_dollars"),
                courseworkHoursNext7d = facts.optDoubleOrNull("coursework_hours_next_7d"),
                temperatureF = facts.optIntOrNull("temperature_f"),
                weatherHighF = facts.optIntOrNull("weather_high_f"),
                weatherLowF = facts.optIntOrNull("weather_low_f"),
                weatherCondition = facts.optStringOrNull("weather_condition"),
                sleepMinutes = facts.optIntOrNull("sleep_minutes"),
                partnerDaysSince = facts.optIntOrNull("partner_days_since"),
                friendDaysSince = facts.optIntOrNull("friend_days_since"),
                partnerDaysUntil = facts.optIntOrNull("partner_days_until"),
                friendDaysUntil = facts.optIntOrNull("friend_days_until"),
                fetchedAtEpochMillis = fetchedAtEpochMillis,
                attentionState = attention.optStringOrNull("state"),
                attentionSymbol = attention.optStringOrNull("symbol"),
                attentionLabel = attention.optStringOrNull("label"),
                attentionHeadline = attention.optStringOrNull("headline"),
                reasons = parseReasons(attention.optJSONArray("reasons")),
                notableEvents = parseNotableEvents(facts.optJSONArray("notable_events")),
            )
        }
    }
}
