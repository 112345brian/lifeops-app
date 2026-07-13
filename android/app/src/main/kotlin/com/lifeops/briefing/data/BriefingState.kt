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
                date = o.optString("date").takeIf { it.isNotEmpty() },
                text = o.optString("text").takeIf { it.isNotEmpty() },
                gymLast7d = o.optInt("gymLast7d", -1).takeIf { it >= 0 },
                gymTarget = o.optInt("gymTarget", -1).takeIf { it >= 0 },
                discretionaryDollars = o.optInt("discretionaryDollars", Int.MIN_VALUE)
                    .takeIf { it != Int.MIN_VALUE },
                courseworkHoursNext7d = o.optDouble("courseworkHoursNext7d", Double.NaN)
                    .takeIf { !it.isNaN() },
                fetchedAtEpochMillis = o.optLong("fetchedAtEpochMillis", -1L).takeIf { it >= 0 },
                attentionState = o.optString("attentionState").takeIf { it.isNotEmpty() },
                attentionSymbol = o.optString("attentionSymbol").takeIf { it.isNotEmpty() },
                attentionLabel = o.optString("attentionLabel").takeIf { it.isNotEmpty() },
                attentionHeadline = o.optString("attentionHeadline").takeIf { it.isNotEmpty() },
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
                date = o.optString("date").takeIf { it.isNotEmpty() },
                text = o.optString("text").takeIf { it.isNotEmpty() },
                gymLast7d = if (facts.has("gym_last_7d")) facts.optInt("gym_last_7d") else null,
                gymTarget = if (facts.has("gym_target")) facts.optInt("gym_target") else null,
                discretionaryDollars = if (facts.has("discretionary_dollars"))
                    facts.optInt("discretionary_dollars") else null,
                courseworkHoursNext7d = if (facts.has("coursework_hours_next_7d"))
                    facts.optDouble("coursework_hours_next_7d") else null,
                fetchedAtEpochMillis = fetchedAtEpochMillis,
                attentionState = attention.optString("state").takeIf { it.isNotEmpty() },
                attentionSymbol = attention.optString("symbol").takeIf { it.isNotEmpty() },
                attentionLabel = attention.optString("label").takeIf { it.isNotEmpty() },
                attentionHeadline = attention.optString("headline").takeIf { it.isNotEmpty() },
                reasons = parseReasons(attention.optJSONArray("reasons")),
            )
        }
    }
}
