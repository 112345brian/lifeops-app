package com.lifeops.briefing

import android.content.Context
import android.util.Log
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import com.lifeops.briefing.data.BriefingState
import com.lifeops.briefing.data.LifeOpsDatabase
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * On-device port of `lifeops/runner.py`'s `run_digest` -- the weekly Sunday
 * accountability-coach note (`llm.py`'s `weekly_digest`, one of the 2 real
 * LLM calls this task ported; see `AnthropicClient.kt`'s top-level kdoc for
 * why there are 2, not 3).
 *
 * Piggybacks on [LifeOpsComputeWorker]'s existing 15-minute periodic tick,
 * self-gated by day-of-week + a once-a-day dedup, the same "self-gated,
 * checked every tick" convention this file's siblings already use
 * ([reportLocationIfDue], [reportWeatherIfDue], [refreshYnabCategoriesIfConfigured])
 * -- rather than a second WorkManager schedule. This is more robust against
 * exact-time drift than a periodic 7-day `PeriodicWorkRequest` (which has no
 * way to guarantee landing specifically on a Sunday), and matches the
 * pattern this codebase already established for "infrequent, tick-piggybacked"
 * background work.
 *
 * Per the locked-in product decision (biometric gating applies to
 * user-initiated writes/spends, never background automation), this call is
 * UNGATED -- it runs unattended, exactly like `run_digest` does server-side
 * today, so automation doesn't break waiting on a prompt nobody is there to
 * answer.
 */

internal const val DIGEST_GATE_PREFS_NAME = "lifeops_digest_gate"

/** ISO date string ("2026-08-02") of the last successfully-sent digest, or
 * absent if none has ever been sent. Written ONLY on success -- mirrors this
 * codebase's own "dedup only on a successful send" principle (see
 * `docs/lifeops_capability_todo.md`'s Notification Architecture section) so
 * a failed LLM call gets retried on the next 15-min tick instead of being
 * silently skipped for the rest of the day. */
internal const val KEY_LAST_DIGEST_SENT_DATE = "last_digest_sent_date"

/**
 * Ports `run_digest`'s own gate exactly: `now.strftime("%a") != "Sun"`, plus
 * a once-per-day dedup standing in for the Python source's
 * `_alert_once("digest:" + now.date().isoformat(), ...)` (which is itself a
 * once-per-day-per-key dedup; see `notify.alert`'s `dedup_key`).
 */
internal fun isDigestDue(now: LocalDateTime, lastSentDate: String?): Boolean {
    if (now.dayOfWeek != DayOfWeek.SUNDAY) return false
    return lastSentDate != now.toLocalDate().toString()
}

/**
 * Ports `run_digest`'s facts assembly for the CURRENT calendar week
 * (Monday-Sunday, matching `mon = now.date() - timedelta(days=now.date().weekday())`):
 * `wk(a) = len(history.days_with(a, mon.isoformat(), sun.isoformat()))` for
 * each domain, counting DISTINCT DAYS with at least one event, not raw event
 * counts.
 *
 * Deliberately NOT ported: `gym_adherence` (`adherence.gym(now)`, a 42-day
 * morning/evening completion-rate model keyed off a `history.events("gym_missed")`
 * domain). This on-device port has no `gym_missed` equivalent -- only "gym"
 * completions are logged today (see `HistoryEventEntity`'s domain kdoc) --
 * so inventing a partial version of that stat would silently disagree with
 * the server's real definition rather than faithfully porting it. Flagged
 * here, not silently dropped.
 */
internal suspend fun buildDigestFacts(db: LifeOpsDatabase, now: LocalDateTime): JSONObject {
    val zone = ZoneId.systemDefault()
    val monday = now.toLocalDate().minusDays((now.dayOfWeek.value - 1).toLong())
    val sundayEndExclusive = monday.plusDays(7) // Mon..Sun inclusive, exclusive upper bound
    val startMillis = monday.atStartOfDay(zone).toInstant().toEpochMilli()
    val endMillis = sundayEndExclusive.atStartOfDay(zone).toInstant().toEpochMilli()

    suspend fun daysWith(domain: String): Int =
        db.historyEventDao().getForDomainSince(domain, startMillis)
            .asSequence()
            .filter { it.timestampEpochMillis < endMillis }
            .map { Instant.ofEpochMilli(it.timestampEpochMillis).atZone(zone).toLocalDate() }
            .distinct()
            .count()

    return JSONObject().apply {
        put("gym_done", daysWith("gym"))
        put("gym_target", 4) // matches routine_store.py's own gym default
        put("chores_done", daysWith("laundry") + daysWith("clean_room") + daysWith("clean_bathroom"))
        put("saw_partner", daysWith("partner"))
        put("saw_friends", daysWith("friends"))
    }
}

/**
 * Self-gated weekly-digest tick, called from [LifeOpsComputeWorker.doWork]
 * every 15 minutes. No-ops silently (matching `run_digest`'s own early
 * returns) if it isn't Sunday, if today's digest was already sent, or if no
 * Anthropic API key is configured (`not config.ANTHROPIC_API_KEY`'s
 * on-device equivalent).
 *
 * Surfaces the digest text via [BriefingState.nudges] rather than a real
 * push notification -- this port's scope is moving the LLM call itself
 * on-device, not building a new phone-side notification-delivery mechanism
 * for it (the existing `nudges` list is the closest already-shipped surface
 * for "a sentence the widget should show," and reuses it rather than
 * inventing a parallel one). Wiring a dedicated notification for this is a
 * reasonable follow-up, not done here.
 */
internal suspend fun runWeeklyDigestIfDue(context: Context, db: LifeOpsDatabase, now: LocalDateTime) {
    val gatePrefs = context.getSharedPreferences(DIGEST_GATE_PREFS_NAME, Context.MODE_PRIVATE)
    val lastSent = gatePrefs.getString(KEY_LAST_DIGEST_SENT_DATE, null)
    if (!isDigestDue(now, lastSent)) return

    val apiKey = WidgetConfigStore.getAnthropicApiKey(context)
    if (apiKey == null) {
        Log.i("WeeklyDigest", "skipping weekly digest: no Anthropic API key configured")
        return
    }

    val facts = buildDigestFacts(db, now)
    val digestText = try {
        AnthropicClient(apiKey).weeklyDigest(facts)
    } catch (e: Exception) {
        Log.e("WeeklyDigest", "weekly digest LLM call failed", e)
        return
    }
    if (digestText.isBlank()) return

    // Mark done only now -- see KEY_LAST_DIGEST_SENT_DATE's kdoc.
    gatePrefs.edit().putString(KEY_LAST_DIGEST_SENT_DATE, now.toLocalDate().toString()).apply()

    val manager = GlanceAppWidgetManager(context)
    for (glanceId in manager.getGlanceIds(BriefingWidget::class.java)) {
        val current = try {
            getAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId)[WidgetKeys.BRIEFING_JSON]
                ?.let { BriefingState.fromJson(it) }
        } catch (e: Exception) {
            null
        } ?: BriefingState.empty()
        val updated = current.copy(nudges = current.nudges + digestText)
        updateAppWidgetState(context, glanceId) { prefs -> prefs[WidgetKeys.BRIEFING_JSON] = updated.toJson() }
        BriefingWidget().update(context, glanceId)
    }
}
