package com.lifeops.briefing.ui

import android.content.Context
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.lifeops.briefing.AlertLevel
import com.lifeops.briefing.LifeOpsComputeWorker
import com.lifeops.briefing.applyGymPlanAlertToNudges
import com.lifeops.briefing.computeGymPlanRespectingBlocks
import com.lifeops.briefing.computeGymRing
import com.lifeops.briefing.data.BlockedDayEntity
import com.lifeops.briefing.data.HistoryEventEntity
import com.lifeops.briefing.data.LifeOpsDatabase
import com.lifeops.briefing.refreshGymStateForAllWidgets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** Thrown when a quick action gets an invalid date string -- the on-device
 * equivalent of `lifeops/web.py`'s `datetime.date.fromisoformat(date)`
 * raising `ValueError` (which those routes turn into an HTTP 400). [date]
 * must be an ISO-8601 calendar date ("YYYY-MM-DD"). */
class InvalidDateException(message: String) : IllegalArgumentException(message)

/**
 * On-device backing for the Today screen's quick actions (docs/
 * lifeops_capability_todo.md's "Full App Home" section: "run catchup, log
 * gym, skip gym, block today/tomorrow"). This REPLACES the previous
 * implementation, which called the home server's small JSON Actions API
 * (`lifeops/web.py`'s `POST /api/gym/log`, `/api/gym/skip`,
 * `/api/schedule/block-day`, `/api/domains/{name}/run`) -- per the
 * locked-in product decision to retire non-Canvas server-side dependency
 * once the phone can compute independently, every one of these 4 actions is
 * now a real Room write plus an immediate on-device recompute of whatever
 * piece of state it affects, with no network call and no panel URL/token
 * required.
 *
 * Public function names/signatures (`logGym`/`skipGym`/`blockDay`/
 * `runDomain`/`runCatchup`) are UNCHANGED from the retired HTTP client, so
 * `TodayScreen.kt`'s existing call sites need no surgery.
 *
 * ## No server-HTTP fallback
 *
 * Deliberately NOT kept, not even behind a flag. The old client's fallback
 * would mean carrying two complete, independent implementations of the same
 * 4 actions indefinitely -- one of them (the HTTP path) being exactly the
 * dependency the user's own decision is to retire -- for zero functional
 * gain: every one of these 4 actions has a full on-device equivalent with no
 * capability gap versus the server path (unlike, say,
 * [TodayRepository.completeTask]'s ntfy-signal fallback, which covers a
 * genuine "server unreachable, no on-device equivalent yet" gap that
 * doesn't apply here). If a real gap shows up later (e.g. multi-device sync
 * of blocked days), that is a reason to design a real sync mechanism, not to
 * quietly keep a parallel HTTP implementation around "just in case."
 *
 * ## Biometric gating
 *
 * None of these 4 actions call [com.lifeops.briefing.BiometricGate.authenticate].
 * Per the locked-in product decision (`BiometricGate.kt`'s own kdoc):
 * gating applies to user-initiated writes that are financial (YNAB) or spend
 * LLM API credit -- never to routine/schedule/task actions like these. Gym
 * log/skip, block-day, and run-catchup are all in the latter category (same
 * as `TodayRepository.completeTask`'s task-completion write), so they are
 * intentionally left ungated, matching the actual criterion rather than
 * wiring the gate onto every write just because the primitive now exists.
 */
object PanelActionsClient {

    private fun requireValidDate(date: String): LocalDate = try {
        LocalDate.parse(date)
    } catch (e: Exception) {
        throw InvalidDateException("date required (YYYY-MM-DD), got '$date'")
    }

    /** On-device equivalent of `POST /api/gym/log`: logs a same-day gym
     * completion as a [HistoryEventEntity] under domain="gym" -- the EXACT
     * domain key [com.lifeops.briefing.computeGymRing] already reads (see
     * `LifeOpsComputeWorker.kt`'s own kdoc: gym history is fed through
     * [com.lifeops.briefing.Routine]/[com.lifeops.briefing.status] keyed off
     * that same "gym" domain) -- then immediately recomputes the gym ring
     * ([refreshGymStateForAllWidgets]) so the Today screen reflects it
     * without waiting for the next periodic tick. */
    suspend fun logGym(context: Context): JSONObject = withContext(Dispatchers.IO) {
        val db = LifeOpsDatabase.getInstance(context)
        val now = LocalDateTime.now()
        db.historyEventDao().insert(
            HistoryEventEntity(
                domain = "gym",
                timestampEpochMillis = now.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                eventType = HistoryEventEntity.EVENT_COMPLETED,
            ),
        )
        refreshGymStateForAllWidgets(context, db, now)
        val ring = computeGymRing(db, now)
        JSONObject().apply {
            put("ok", true)
            put("gymLast7d", ring.gymLast7d)
            put("gymTarget", ring.gymTarget)
        }
    }

    /** On-device equivalent of `POST /api/gym/skip`: logs today as a
     * deliberate skip (not a missed session) under its OWN domain key
     * ("gym_skip", matching `lifeops/history.py`'s real
     * `history.append("gym_skip", ...)` action exactly) -- NOT
     * domain="gym" with a "skipped" [HistoryEventEntity.eventType], which
     * would silently corrupt [com.lifeops.briefing.computeGymRing]'s window
     * count (that function counts every row under domain "gym" toward
     * adherence, regardless of `eventType` -- `eventType` isn't consulted
     * anywhere on Android yet, see [HistoryEventEntity]'s own kdoc). Also
     * re-runs the gym-ring recompute so nothing about "today" looks stale,
     * even though a skip doesn't change the ring's own count. */
    suspend fun skipGym(context: Context): JSONObject = withContext(Dispatchers.IO) {
        val db = LifeOpsDatabase.getInstance(context)
        val now = LocalDateTime.now()
        db.historyEventDao().insert(
            HistoryEventEntity(
                domain = "gym_skip",
                timestampEpochMillis = now.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                eventType = HistoryEventEntity.EVENT_SKIPPED,
            ),
        )
        refreshGymStateForAllWidgets(context, db, now)
        val ring = computeGymRing(db, now)
        JSONObject().apply {
            put("ok", true)
            put("gymLast7d", ring.gymLast7d)
            put("gymTarget", ring.gymTarget)
        }
    }

    /** On-device equivalent of `POST /api/schedule/block-day`: persists a
     * [BlockedDayEntity] row (see that entity's kdoc for what this
     * deliberately does and doesn't port relative to `lifeops/web.py`'s
     * `_block_day`), then actually re-runs `GymSchedule.kt`'s real
     * scheduling engine ([computeGymPlanRespectingBlocks]) against the
     * updated blocked-day set -- surfacing any resulting floor-risk alert
     * via [com.lifeops.briefing.data.BriefingState.nudges], the same "a
     * sentence the widget should show" surface `WeeklyDigest.kt` already
     * uses. [date] must be an ISO-8601 calendar date ("YYYY-MM-DD");
     * throws [InvalidDateException] otherwise (the on-device equivalent of
     * the server route's 400). */
    suspend fun blockDay(context: Context, date: String): JSONObject = withContext(Dispatchers.IO) {
        requireValidDate(date)
        val db = LifeOpsDatabase.getInstance(context)
        db.blockedDayDao().upsert(BlockedDayEntity(date = date, source = "ui"))

        val plan = computeGymPlanRespectingBlocks(db, LocalDateTime.now())
        if (plan.alert.level != AlertLevel.NONE) {
            applyGymPlanAlertToNudges(context, plan.alert.text)
        }
        JSONObject().apply {
            put("ok", true)
            put("date", date)
            put("alertLevel", plan.alert.level.name.lowercase())
            put("alertText", plan.alert.text)
        }
    }

    /** On-device equivalent of `POST /api/domains/{name}/run`. Unlike the
     * Python source's real per-domain dispatch, [LifeOpsComputeWorker] has
     * no per-domain scoping -- it always recomputes every wired engine in
     * one tick (see that file's own kdoc) -- so every [name] here enqueues
     * the SAME one-time full compute tick [TodayRepository.forceRefresh]
     * already enqueues, fire-and-forget (not synchronously awaitable --
     * WorkManager has no cheap synchronous-completion API this codebase
     * already uses elsewhere). [name] is accepted for call-site/API-shape
     * compatibility and echoed back in the response, but does not change
     * what runs. */
    suspend fun runDomain(context: Context, name: String): JSONObject = withContext(Dispatchers.IO) {
        WorkManager.getInstance(context).enqueue(OneTimeWorkRequestBuilder<LifeOpsComputeWorker>().build())
        JSONObject().apply {
            put("ok", true)
            put("domain", name)
            put("queued", true)
        }
    }

    /** "Run catchup" is this call with `name = "catchup"`; see [runDomain]. */
    suspend fun runCatchup(context: Context): JSONObject = runDomain(context, "catchup")
}
