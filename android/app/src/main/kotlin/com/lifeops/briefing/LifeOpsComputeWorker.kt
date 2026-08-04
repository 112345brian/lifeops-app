package com.lifeops.briefing

import android.content.Context
import android.util.Log
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.lifeops.briefing.data.BriefingState
import com.lifeops.briefing.data.GymRing
import com.lifeops.briefing.data.LifeOpsDatabase
import com.lifeops.briefing.data.NextTask
import com.lifeops.briefing.data.NextTasksState
import com.lifeops.briefing.data.TaskCacheEntity
import com.lifeops.briefing.data.TodayEvent
import com.lifeops.briefing.data.toRoutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import com.lifeops.briefing.data.AttentionReason as UiAttentionReason

/**
 * The real on-device compute tick this whole migration has been building
 * toward -- see docs/lifeops_capability_todo.md's "actual bottleneck" note:
 * every engine port (Routine/YnabEngine/GymSchedule/ChoreSchedule/
 * SocialSchedule/DeadlineRisk/Attention) plus the Room persistence layer
 * (RoutineEntity/HistoryEventEntity/TaskCacheEntity) existed, tested, but
 * completely unwired before this file. [LifeOpsComputeWorker] is the
 * WorkManager periodic worker that actually fetches real data
 * ([FlowSavvyClient]), reads/writes it durably (Room), runs the pure engines
 * against it, and folds the result into [Attention]'s deterministic
 * `attention_state`/`attention_reasons` -- replacing the old
 * `NextTasksRefreshWorker`'s direct HTTP fetch-and-parse of the home
 * server's pre-computed `/api/next-tasks`/`/api/briefing` JSON, which is
 * DELETED by this change (see this file's bottom section).
 *
 * ## What this tick actually wires, and what it deliberately does NOT
 *
 * Wired, tested, real:
 * - FlowSavvy fetch (schedule + all incomplete tasks) via [FlowSavvyClient],
 *   abstracted behind [FlowSavvyFetch] purely so tests can supply fixture
 *   JSON without a real network call or a mock HTTP server (this project has
 *   neither OkHttp nor MockWebServer -- see `FlowSavvyClientTest.kt`'s own
 *   kdoc on why its tests exercise the retry policy directly instead).
 * - [TaskCacheDao] as the durable local task cache (upserted every tick),
 *   so "what's outstanding" survives a transient FlowSavvy fetch failure --
 *   the exact gap [TaskCacheEntity]'s kdoc calls out.
 * - Gym: [Routine]/[status] (anchor=WINDOW) fed real [HistoryEventDao] rows
 *   for the "gym" domain, using [RoutineDao]'s persisted target if a "gym"
 *   routine row exists, falling back to `times=4` (routine_store.py's own
 *   default) otherwise.
 * - Social: [SocialSchedule.kt]'s `plan` fed real days-since-last from
 *   [HistoryEventDao] for "partner"/"friends", using persisted [RoutineDao]
 *   rows when present.
 * - Meal: [MealSchedule.kt]'s `planMeal` fed the real last-"meal"-event
 *   timestamp from [HistoryEventDao].
 * - Deadline risk / coursework-at-risk / overdue / due-today: [DeadlineRisk.kt]'s
 *   `atRiskAssignments`/`deadlineCrunchItem`, fed [Assignment]s built from
 *   every fetched incomplete FlowSavvy task's real `dueDateTime`/
 *   `durationMinutes`/`progressMinutes` -- ports `lifeops/briefing_service.py`'s
 *   `build()` overdue/due_today/coursework_at_risk assembly (verified against
 *   that function directly), simplified to run over EVERY incomplete task
 *   rather than `gather.py`'s `LIST_COURSE`-scoped subset (Android has no
 *   equivalent of `config.LIST_COURSE` wired yet) -- a documented, deliberate
 *   scope narrowing, not an oversight.
 * - Money: [Attention]'s money branch fed the real
 *   `discretionaryCurrentDollars` [refreshYnabCategoriesIfConfigured] (already
 *   wired, unchanged by this file) computes from a live YNAB category-balance
 *   read.
 * - [Attention.compute] itself, folding every one of the above into a real
 *   `attention_state`/`attention_reasons`/headline, persisted into
 *   [BriefingState] so [BriefingWidget] renders it exactly like the old
 *   server-pushed value.
 * - **Chore's actual next-occurrence task creation**, via [runChoreCycle]
 *   (`ChoreCycle.kt`) -- a live "completed tasks carrying a `[cycle:Nd]` note
 *   tag" FlowSavvy read plus a `FlowSavvyClient.createTask` WRITE, with its
 *   own dedup-by-processed-id persistence ([ChoreCycleStateEntity]) so a
 *   completion FlowSavvy still reports on a second tick doesn't create a
 *   duplicate next occurrence. See `ChoreCycle.kt`'s own top-level kdoc for
 *   the full write-back path and the one known (Python-source-inherited)
 *   partial-failure gap it flags.
 *
 * Wired since this tick's original version:
 * - **YnabEngine's categorize/approve/hold/cover WRITE actions.** See
 *   `YnabWrite.kt` -- the live "unapproved transactions" YNAB read plus YNAB
 *   PATCH write this file's own comment used to flag as missing.
 *   [runYnabWriteTickIfConfigured] runs self-gated (once/day) alongside
 *   [runWeeklyDigestIfDue] above.
 * - **GymSchedule.kt's full `plan()`** (slot-booking/gym-task creation/
 *   wind-down blocks), via [runGymPlanCycle] (`GymPlanCycle.kt`) -- a real
 *   14-day candidate window (calendar-blocked days from [BlockedDayDao],
 *   real evening/day-after-show/prior-night-blocked flags from fetched
 *   FlowSavvy events, and real deadline-heavy days from this tick's own
 *   fetched incomplete-task load), fed to `plan()`, with its `create`/
 *   wind-down actions applied via [FlowSavvyClient.createTask] (a TASK, not
 *   a calendar event -- verified against `runner.py`'s `run_gym`/
 *   `_logged_create` directly; see `GymPlanCycle.kt`'s own kdoc for the full
 *   trace). Dedup is a live FlowSavvy re-read every tick (already-scheduled
 *   "Gym" tasks feed `plan()`'s own busy-date exclusion; an existing
 *   "Wind down" task is checked before each wind-down create) rather than a
 *   new persisted id-list, since FlowSavvy's own current schedule is already
 *   the authoritative source of "was this already booked" -- see
 *   `GymPlanCycle.kt`'s top-level kdoc for why no new Room entity was added.
 *   Gym's WINDOW-adherence math ([computeGymRing], below) is UNCHANGED by
 *   this and still reused directly for the widget/attention 7-day-count
 *   surfaces, which don't need a booked slot, just a count.
 *
 * Wired since the on-device SystemHealth design landed:
 * - **`SystemHealth`** -- see `OnDeviceSystemHealth.kt`'s top-level kdoc for
 *   the full design (per-data-source "last successful sync" freshness,
 *   deliberately NOT a literal port of the server's "automation process
 *   health" concept, which has no on-device equivalent). Computed via
 *   [computeOnDeviceSystemHealth] BEFORE this tick's own FlowSavvy fetch
 *   below, and threaded into [runComputeTick]'s `systemHealth` parameter,
 *   which feeds [Attention.compute]'s `system` argument.
 *
 * ## FlowSavvy config
 *
 * Reads `WidgetConfigStore.getFlowSavvyBaseUrl`/`getFlowSavvyToken` -- if
 * either is unconfigured, `doWork` no-ops (`Result.success()`) the same way
 * the old worker no-op'd on a missing panel URL/token. Set from the Settings
 * screen (base URL + token fields alongside the panel/YNAB/Anthropic ones)
 * or via `WidgetConfigStore.importFlowSavvyConfigFileIfPresent`'s sideload
 * path, mirroring the existing YNAB_TOKEN import mechanism.
 */

// ---------------------------------------------------------------------
// FlowSavvy fetch -- a small seam so the compute logic below is testable
// with fixture JSON, without a real network call or a mock HTTP server.
// ---------------------------------------------------------------------

/** One `scheduleItems[]` entry from FlowSavvy's `get_schedule` response --
 * matches the fields `gather.py`'s `next_tasks_input`/`today_events_input`
 * actually read (verified against that source directly), nothing more. */
internal data class RawScheduleItem(
    val itemId: String?,
    val itemType: String?,
    val title: String?,
    val startTime: String?,
    val allDay: Boolean = false,
    val completed: Boolean = false,
    val thisPartCompleted: Boolean = false,
)

/** One `items[]` entry from FlowSavvy's `list_items(itemType="task", ...)`
 * response -- matches the fields `gather.py`'s `homework_input`/
 * `deadline_input` actually read. */
internal data class RawTaskItem(
    val id: String?,
    val title: String?,
    val dueDateTime: String?,
    val durationMinutes: Int?,
    val progressMinutes: Int?,
    val completed: Boolean = false,
)

internal data class FlowSavvyFetchResult(
    val scheduleItems: List<RawScheduleItem>,
    val incompleteTasks: List<RawTaskItem>,
)

/** Testability seam: a real implementation ([RealFlowSavvyFetch]) wraps
 * [FlowSavvyClient]; tests supply a fake returning fixture data built
 * in-memory, with no network/mock-server dependency. */
internal fun interface FlowSavvyFetch {
    fun fetch(now: LocalDateTime): FlowSavvyFetchResult
}

/** has()-and-not-null()-safe String read -- `optString` on an explicit JSON
 * `null` (FlowSavvy leaves `dueDateTime`/`startTime` explicitly null for
 * plenty of real items, e.g. an auto-scheduled task with no fixed start yet)
 * returns the literal STRING `"null"` (non-empty), not Kotlin `null` --
 * same hazard `BriefingState.kt`'s own `optStringOrNull` documents and
 * guards against. Guarding it here too rather than relying on the
 * accidental save of a downstream `LocalDateTime.parse("null")` throwing and
 * being caught. */
private fun JSONObject.optStringOrNull(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

internal fun parseScheduleItem(o: JSONObject): RawScheduleItem = RawScheduleItem(
    itemId = o.optStringOrNull("itemId"),
    itemType = o.optStringOrNull("itemType"),
    title = o.optStringOrNull("title"),
    startTime = o.optStringOrNull("startTime"),
    allDay = o.optBoolean("allDay", false),
    completed = o.optBoolean("completed", false),
    thisPartCompleted = o.optBoolean("thisPartCompleted", false),
)

internal fun parseTaskItem(o: JSONObject): RawTaskItem = RawTaskItem(
    id = o.optStringOrNull("id"),
    title = o.optStringOrNull("title"),
    dueDateTime = o.optStringOrNull("dueDateTime"),
    durationMinutes = if (o.has("durationMinutes") && !o.isNull("durationMinutes")) o.optInt("durationMinutes") else null,
    progressMinutes = if (o.has("progressMinutes") && !o.isNull("progressMinutes")) o.optInt("progressMinutes") else null,
    completed = o.optBoolean("completed", false),
)

/** Real network-backed [FlowSavvyFetch]. Mirrors `gather.py`'s
 * `_upcoming_schedule` (21-day window) and `deadline_input`'s
 * `list_items(itemType="task", completed=False)` fetch shapes exactly. */
internal class RealFlowSavvyFetch(private val client: FlowSavvyClient) : FlowSavvyFetch {
    override fun fetch(now: LocalDateTime): FlowSavvyFetchResult {
        val start = now.toLocalDate().toString()
        val end = now.toLocalDate().plusDays(21).toString()
        val scheduleArr = client.getSchedule(start, end).optJSONArray("scheduleItems") ?: JSONArray()
        val scheduleItems = (0 until scheduleArr.length()).map { parseScheduleItem(scheduleArr.getJSONObject(it)) }

        val tasksArr = client.listItems(mapOf("itemType" to "task", "completed" to false)).optJSONArray("items") ?: JSONArray()
        val tasks = (0 until tasksArr.length()).map { parseTaskItem(tasksArr.getJSONObject(it)) }

        return FlowSavvyFetchResult(scheduleItems, tasks)
    }
}

// ---------------------------------------------------------------------
// Pure compute helpers -- each directly unit-testable, no Android/Room/
// network dependency.
// ---------------------------------------------------------------------

/** ISO-8601 local date-time truncated to whole seconds, e.g.
 * "2026-08-02T14:05:00" -- matches Python's `now.isoformat(timespec="seconds")`
 * closely enough that lexical string comparison agrees with chronological
 * order (both zero-pad every field), which is exactly what
 * `next_tasks_input`'s `st < now.isoformat(...)` filter relies on. Plain
 * `LocalDateTime.toString()` is NOT used here because it omits the seconds
 * field when they're zero, which would break that same lexical comparison. */
internal fun isoSeconds(dt: LocalDateTime): String {
    val d = dt.toLocalDate()
    val t = dt.toLocalTime().withNano(0)
    return "%04d-%02d-%02dT%02d:%02d:%02d".format(d.year, d.monthValue, d.dayOfMonth, t.hour, t.minute, t.second)
}

/** Ports `gather.py`'s `next_tasks_input`: the next [n] incomplete,
 * non-all-day, future-starting tasks across the whole schedule window,
 * sorted by real start time. */
internal fun nextTasksFromSchedule(items: List<RawScheduleItem>, now: LocalDateTime, n: Int = 3): List<NextTask> {
    val nowIso = isoSeconds(now)
    return items.asSequence()
        .filter { it.itemType == "task" && !it.allDay && !it.completed && !it.thisPartCompleted }
        .mapNotNull { i -> i.startTime?.takeIf { it >= nowIso }?.let { st -> Triple(i.itemId, i.title, st) } }
        .sortedBy { it.third }
        .take(n)
        .map { NextTask(id = it.first ?: "", title = it.second ?: "", start = it.third) }
        .toList()
}

/** Ports `gather.py`'s `today_events_input`: today's real (timed) events
 * across the whole schedule window, sorted by start time. */
internal fun todayEventsFromSchedule(items: List<RawScheduleItem>, now: LocalDateTime, n: Int = 5): List<TodayEvent> {
    val today = now.toLocalDate().toString()
    return items.asSequence()
        .filter { it.itemType == "event" && !it.allDay && (it.startTime ?: "").startsWith(today) }
        .sortedBy { it.startTime ?: "" }
        .take(n)
        .map { TodayEvent(title = it.title ?: "", start = it.startTime) }
        .toList()
}

/** Ports the [Assignment] shape `gather.py`'s `homework_input`/
 * `deadline_input` build from a raw FlowSavvy task dict -- see this file's
 * top-level kdoc for the deliberate "every incomplete task, not just
 * LIST_COURSE" scope narrowing versus the Python source's two separate
 * course-scoped/all-tasks functions. */
internal fun assignmentsFromTasks(tasks: List<RawTaskItem>, now: LocalDateTime): List<Assignment> =
    tasks.mapNotNull { t ->
        val due = t.dueDateTime ?: return@mapNotNull null
        val dueDt = try {
            LocalDateTime.parse(due)
        } catch (e: Exception) {
            return@mapNotNull null
        }
        val hours = Duration.between(now, dueDt).toMinutes() / 60.0
        // Matches homework_input's own semester-ish staleness bound (skip
        // anything abandoned so long ago it can't still be actionable).
        if (hours < -24 * 120) return@mapNotNull null
        val dur = (t.durationMinutes ?: 0).toDouble()
        val prog = (t.progressMinutes ?: 0).toDouble()
        Assignment(
            title = t.title ?: "",
            dueInHours = hours,
            dueInDays = hours / 24.0,
            remainingMin = maxOf(0.0, dur - prog),
            progress = prog,
        )
    }

/** Ports `briefing_service.py`'s `build()` overdue-list comprehension
 * exactly: `due_in_h < 0 and remaining_min > 0`. */
internal fun overdueItemsFrom(assignments: List<Assignment>): List<OverdueItem> =
    assignments.filter { (it.dueInHours ?: 0.0) < 0 && (it.remainingMin ?: 0.0) > 0 }
        .map { OverdueItem(title = it.title, dueInHours = it.dueInHours ?: 0.0, due = null) }

/** Ports `briefing_service.py`'s `due_today` comprehension exactly:
 * `0 <= due_in_h <= 24`. */
internal fun dueTodayTitles(assignments: List<Assignment>): List<String> =
    assignments.filter { (it.dueInHours ?: 1e9) in 0.0..24.0 }.map { it.title ?: "" }

/** Ports `briefing_service.py`'s `coursework_at_risk` assembly: the
 * heavy-plus-soon items ([atRiskAssignments]) plus the single earliest
 * binding deadline-crunch item ([deadlineCrunchItem]), title-deduplicated
 * (the Python source appends the crunch item to the same list without
 * deduplication, but a crunch item is very often ALSO one of the
 * heavy-plus-soon items -- deduplicating here avoids a double-counted
 * reason in a UI surface, whereas the Python source's `at_risk_items`
 * feeds risk_tracking, not straight into a UI list). */
internal fun courseworkAtRiskTitles(assignments: List<Assignment>): List<String> {
    val heavy = atRiskAssignments(assignments).map { it.title ?: "" }
    val crunch = deadlineCrunchItem(assignments)?.title
    return (heavy + listOfNotNull(crunch)).distinct()
}

/** Ports `briefing_service.py`'s `load_7d_h` exactly: total remaining
 * minutes across every assignment due within 7 days, in hours. */
internal fun courseworkHoursNext7d(assignments: List<Assignment>): Double =
    assignments.filter { (it.dueInDays ?: 99.0) <= 7.0 }.sumOf { it.remainingMin ?: 0.0 } / 60.0

/** Gym adherence: real [HistoryEventDao] rows for the "gym" domain, fed
 * through [Routine]/[status] (anchor=WINDOW) -- the same shared primitive
 * `GymSchedule.kt`'s own `gymRoutine()` wraps, reused directly here since
 * the widget/attention surfaces only need "how many times in the last 7
 * days," not a booked calendar slot (see this file's top-level kdoc). Uses
 * the persisted "gym" [RoutineEntity] row's target when one exists,
 * falling back to `routine_store.py`'s own default (`times=4`) otherwise. */
internal suspend fun computeGymRing(db: LifeOpsDatabase, now: LocalDateTime): GymRing {
    val persistedGym = db.routineDao().getById("gym")
    val target = persistedGym?.timesPerWindow ?: 4
    val timestamps = db.historyEventDao().getTimestampsIsoForDomain("gym")
    // Anchor/perDays are pinned to WINDOW/7 here regardless of what a
    // persisted "gym" RoutineEntity's own anchor field says -- gym's ring is
    // inherently a WINDOW computation (see GymSchedule.kt's own gymRoutine()),
    // and trusting an arbitrary persisted anchor value would risk an
    // unrepresentable RoutineStatus.SinceLast result here. Only the target
    // count is taken from the persisted row.
    val routine = Routine(id = "gym", times = target, perDays = 7, anchor = Anchor.WINDOW)
    val gymStatus = status(routine, timestamps, now) as RoutineStatus.Window
    val todayDone = timestamps.any { runCatching { LocalDateTime.parse(it).toLocalDate() }.getOrNull() == now.toLocalDate() }
    val gymLast7d = gymStatus.timesThisWindow
    val color = when {
        todayDone || gymLast7d >= target -> "green"
        gymLast7d >= maxOf(0, target - 2) -> "yellow"
        else -> "red"
    }
    return GymRing(
        fill = if (target > 0) (gymLast7d.toFloat() / target).coerceIn(0f, 1f) else 0f,
        color = color,
        gymLast7d = gymLast7d,
        gymTarget = target,
        todayDone = todayDone,
    )
}

/**
 * Recomputes just the gym-adherence ring ([computeGymRing]) against
 * whatever [HistoryEventDao] rows exist RIGHT NOW, and pushes it into every
 * placed widget instance's [NextTasksState.gymRing]/[BriefingState.gymLast7d]/
 * [BriefingState.gymTarget] -- the "one code path for 'given current
 * history, what does gym status look like now'" this file's log/skip-gym
 * quick actions need, shared with [LifeOpsComputeWorker]'s own periodic tick
 * (which reaches the same [computeGymRing] call via [runComputeTick] /
 * [applyComputeTickResult]). Called from `ui/PanelActionsClient.logGym`/
 * `skipGym` so a tap is reflected immediately instead of waiting up to 15
 * minutes for the next periodic tick.
 *
 * Deliberately narrower than [applyComputeTickResult]: it does NOT
 * recompute [Attention.compute]'s full reasons/state (that needs the same
 * real overdue/courseworkAtRisk/dueToday/discretionary facts
 * [runComputeTick] assembles from a live FlowSavvy fetch, which a gym
 * log/skip tap has no reason to trigger -- and BriefingState doesn't persist
 * those raw facts, only their rendered [AttentionReason]s, so there is
 * nothing to losslessly recompute them from without a real fetch) -- only
 * the gym-specific fields are touched here. Any gym-cadence attention
 * reason reconciles on the next periodic tick or a manual "Run
 * catchup"/force-refresh, same as every other attention input already does
 * between ticks (e.g. weather/temperature).
 */
internal suspend fun refreshGymStateForAllWidgets(context: Context, db: LifeOpsDatabase, now: LocalDateTime) {
    val gymRing = computeGymRing(db, now)
    val manager = GlanceAppWidgetManager(context)
    for (glanceId in manager.getGlanceIds(BriefingWidget::class.java)) {
        val currentNextTasks = try {
            getAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId)[WidgetKeys.NEXT_TASKS_JSON]
                ?.let { NextTasksState.fromJson(it) }
        } catch (e: JSONException) {
            null
        } ?: NextTasksState(tasks = emptyList())
        persistNextTasksForInstance(context, glanceId, currentNextTasks.copy(gymRing = gymRing))

        val currentBriefing = try {
            getAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId)[WidgetKeys.BRIEFING_JSON]
                ?.let { BriefingState.fromJson(it) }
        } catch (e: JSONException) {
            null
        } ?: BriefingState.empty()
        val updated = currentBriefing.copy(gymLast7d = gymRing.gymLast7d, gymTarget = gymRing.gymTarget)
        updateAppWidgetState(context, glanceId) { prefs -> prefs[WidgetKeys.BRIEFING_JSON] = updated.toJson() }
        BriefingWidget().update(context, glanceId)
    }
}

/**
 * Runs [plan] (`GymSchedule.kt`'s real scheduling engine) against real
 * persisted state -- the "gym" [RoutineEntity]'s target (falling back to
 * [GymRules]'s own default), completed-gym dates within the current 7-day
 * window ([HistoryEventDao]), and a real 14-day candidate window built via
 * [gymDaysRespectingBlocks] -- so a blocked day
 * (`ui/PanelActionsClient.blockDay`) actually changes what [plan] would
 * schedule/alert on, not just a Room row nothing reads. Called from
 * `blockDay`'s on-device action.
 */
internal suspend fun computeGymPlanRespectingBlocks(db: LifeOpsDatabase, now: LocalDateTime): GymPlan {
    val persistedGym = db.routineDao().getById("gym")
    val rules = GymRules(target = persistedGym?.timesPerWindow ?: GymRules().target)
    val today = now.toLocalDate()
    val completedDates = gymCompletedInTrailingWindow(db, today)
    val days = gymDaysRespectingBlocks(db.blockedDayDao(), today, days = 14)
    val input = GymInput(
        today = today,
        completedCount = completedDates.size,
        completedDates = completedDates.toList(),
        days = days,
        rules = rules,
    )
    return plan(input)
}

/** The trailing-7-day set of dates the "gym" [HistoryEventDao] domain
 * recorded a real session on -- the same rolling window
 * `gather.py`'s `gym_input` uses for `completed_count`/`completed_dates`
 * (see that function's own "ROLLING 7-day windows, not the calendar week"
 * comment). Shared by [computeGymPlanRespectingBlocks] and
 * [runGymPlanCycle] (`GymPlanCycle.kt`) so both read this real signal the
 * exact same way instead of two independently-drifting copies. */
internal suspend fun gymCompletedInTrailingWindow(db: LifeOpsDatabase, today: java.time.LocalDate): Set<java.time.LocalDate> {
    val windowStart = today.minusDays(6)
    return db.historyEventDao().getTimestampsIsoForDomain("gym")
        .mapNotNull { runCatching { LocalDateTime.parse(it).toLocalDate() }.getOrNull() }
        .filter { !it.isBefore(windowStart) }
        .toSet()
}

/** Surfaces one sentence (e.g. [GymPlan.alert]'s text from
 * [computeGymPlanRespectingBlocks]) via [BriefingState.nudges] on every
 * placed widget instance -- the same "a sentence the widget should show"
 * surface `WeeklyDigest.kt`'s own tick already appends to, since there's no
 * dedicated notification-delivery mechanism for this (see that file's own
 * kdoc for why). */
internal suspend fun applyGymPlanAlertToNudges(context: Context, alertText: String) {
    val manager = GlanceAppWidgetManager(context)
    for (glanceId in manager.getGlanceIds(BriefingWidget::class.java)) {
        val current = try {
            getAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId)[WidgetKeys.BRIEFING_JSON]
                ?.let { BriefingState.fromJson(it) }
        } catch (e: JSONException) {
            null
        } ?: BriefingState.empty()
        val updated = current.copy(nudges = current.nudges + alertText)
        updateAppWidgetState(context, glanceId) { prefs -> prefs[WidgetKeys.BRIEFING_JSON] = updated.toJson() }
        BriefingWidget().update(context, glanceId)
    }
}

private fun epochMillisToIsoLocalDateTime(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalDateTime().toString()

private fun daysSince(lastEpochMillis: Long?, now: LocalDateTime): Int? =
    lastEpochMillis?.let { Duration.between(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDateTime(), now).toDays().toInt() }

/** Social nudges: real days-since-last for "partner"/"friends" from
 * [HistoryEventDao], fed through `SocialSchedule.kt`'s `plan`. Uses
 * persisted [RoutineDao] rows for cadence when present, falling back to
 * `routine_store.py`'s own defaults (`times=1, per_days=7,
 * anchor=since_last` for both) otherwise. */
internal suspend fun computeSocialNudges(db: LifeOpsDatabase, now: LocalDateTime): List<String> {
    val partnerRoutine = db.routineDao().getById("partner")?.toRoutine()
        ?: Routine(id = "partner", times = 1, perDays = 7, anchor = Anchor.SINCE_LAST)
    val friendsRoutine = db.routineDao().getById("friends")?.toRoutine()
        ?: Routine(id = "friends", times = 1, perDays = 7, anchor = Anchor.SINCE_LAST)
    val partnerDays = daysSince(db.historyEventDao().getLastTimestampForDomain("partner"), now)
    val friendsDays = daysSince(db.historyEventDao().getLastTimestampForDomain("friends"), now)
    return plan(partnerDays, friendsDays, partnerRoutine, friendsRoutine).nudges
}

/** Meal-due nudge: the real last-"meal"-event timestamp from
 * [HistoryEventDao], fed through `MealSchedule.kt`'s `planMeal`. */
internal suspend fun computeMealNudge(db: LifeOpsDatabase, now: LocalDateTime): String? {
    val lastEpoch = db.historyEventDao().getLastTimestampForDomain("meal")
    val lastIso = lastEpoch?.let { epochMillisToIsoLocalDateTime(it) }
    return when (planMeal(lastIso, now)) {
        is MealSchedule.NotDue -> null
        is MealSchedule.Due -> "Meal prep is due -- plan groceries and cook."
    }
}

/** Full output of one [runComputeTick] run -- everything
 * [applyComputeTickResult] needs to persist into the widget-visible
 * [NextTasksState]/[BriefingState] shapes. */
internal data class ComputeTickResult(
    val nextTasks: NextTasksState,
    val attention: AttentionResult,
    val gymLast7d: Int,
    val gymTarget: Int,
    val discretionaryDollars: Int?,
    val courseworkHoursNext7d: Double,
    val nudges: List<String>,
)

/**
 * The actual on-device compute tick: fetch real FlowSavvy data, persist it
 * durably to Room, run every engine this file's top-level kdoc lists as
 * wired against that real data, and fold the results into one deterministic
 * [Attention.compute] call. This is the fully testable core -- no
 * Context/WorkManager/Glance dependency -- that [LifeOpsComputeWorker.doWork]
 * wires up with real dependencies and tests exercise directly against an
 * in-memory Room database and a fixture [FlowSavvyFetch].
 */
internal suspend fun runComputeTick(
    db: LifeOpsDatabase,
    fetch: FlowSavvyFetch,
    discretionaryDollars: Int?,
    now: LocalDateTime = LocalDateTime.now(),
    // Defaults to null (== the old, always-null behavior) so every existing
    // caller/test that doesn't care about system health is unaffected.
    // [LifeOpsComputeWorker.doComputeTick] is the one real caller that passes
    // a real value, computed via OnDeviceSystemHealth.kt's
    // computeOnDeviceSystemHealth BEFORE this tick's own FlowSavvy fetch runs
    // -- see that file's top-level kdoc point 6 for why.
    systemHealth: SystemHealth? = null,
): ComputeTickResult {
    val fetched = fetch.fetch(now)

    // Durable local task cache -- survives a transient FlowSavvy fetch
    // failure on a later tick (see TaskCacheEntity's kdoc: "the last known
    // state of THIS specific remote task").
    val taskEntities = fetched.incompleteTasks.mapNotNull { t ->
        val id = t.id ?: return@mapNotNull null
        val dueMillis = t.dueDateTime?.let {
            runCatching { LocalDateTime.parse(it).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrNull()
        }
        TaskCacheEntity(id = id, title = t.title ?: "", dueAtEpochMillis = dueMillis, completed = t.completed)
    }
    db.taskCacheDao().upsertAll(taskEntities)

    val assignments = assignmentsFromTasks(fetched.incompleteTasks, now)
    val overdue = overdueItemsFrom(assignments)
    val dueToday = dueTodayTitles(assignments)
    val courseworkAtRisk = courseworkAtRiskTitles(assignments)
    val hoursNext7d = courseworkHoursNext7d(assignments)

    val gymRing = computeGymRing(db, now)

    val nudges = buildList {
        addAll(computeSocialNudges(db, now))
        computeMealNudge(db, now)?.let { add(it) }
    }

    val facts = AttentionFacts(
        overdue = overdue,
        courseworkAtRisk = courseworkAtRisk,
        dueToday = dueToday,
        discretionaryDollars = discretionaryDollars?.toDouble(),
        gymLast7d = gymRing.gymLast7d,
        gymTarget = gymRing.gymTarget,
    )
    // See this file's top-level kdoc + OnDeviceSystemHealth.kt for the
    // real on-device SystemHealth design [systemHealth] now carries.
    val attention = compute(facts, system = systemHealth)

    val nextTasksState = NextTasksState(
        tasks = nextTasksFromSchedule(fetched.scheduleItems, now),
        events = todayEventsFromSchedule(fetched.scheduleItems, now),
        gymRing = gymRing,
        weather = null, // weather is written by PhoneWeather.kt directly onto widget state; not this tick's concern
        fetchedAtEpochMillis = now.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
    )

    return ComputeTickResult(
        nextTasks = nextTasksState,
        attention = attention,
        gymLast7d = gymRing.gymLast7d,
        gymTarget = gymRing.gymTarget,
        discretionaryDollars = discretionaryDollars,
        courseworkHoursNext7d = hoursNext7d,
        nudges = nudges,
    )
}

// ---------------------------------------------------------------------
// Widget-state persistence -- same shapes/keys the old server-fed path used,
// so BriefingWidget's read side needs no change (see this file's top-level
// kdoc's "what this tick actually wires" section).
// ---------------------------------------------------------------------

/** Shared by [LifeOpsComputeWorker]'s periodic tick, [NextTasksPersistWorker]'s
 * FCM path, and [CompleteTaskAction], so a fresh [NextTasksState] is
 * persisted identically (including [PendingRemovals] reconciliation) from
 * any path. Moved here from the now-deleted `NextTasksRefreshWorker.kt`
 * (see this file's bottom section) -- logic unchanged. */
internal suspend fun persistNextTasksForInstance(context: Context, glanceId: GlanceId, state: NextTasksState) {
    updateAppWidgetState(context, glanceId) { prefs ->
        val now = System.currentTimeMillis()
        val pending = PendingRemovals.readActive(prefs, now)
        if (pending.isEmpty()) {
            prefs[WidgetKeys.NEXT_TASKS_JSON] = state.toJson()
            return@updateAppWidgetState
        }

        val incomingIds = state.tasks.map { it.id }.toSet()
        val confirmedComplete = pending.keys.filterNot { it in incomingIds }.toSet()
        val stillPresent = pending.keys - confirmedComplete
        val confirmedFailed = stillPresent.filter { id -> pending.getValue(id).isPastGrace(now) }.toSet()

        val resolved = confirmedComplete + confirmedFailed
        if (resolved.isNotEmpty()) PendingRemovals.clearConfirmed(prefs, resolved)

        val toMask = stillPresent - confirmedFailed
        val filtered = if (toMask.isEmpty()) state else state.copy(tasks = state.tasks.filterNot { it.id in toMask })
        prefs[WidgetKeys.NEXT_TASKS_JSON] = filtered.toJson()
    }
    BriefingWidget().update(context, glanceId)
}

/** Applies one [ComputeTickResult] to every placed widget instance: the
 * next-tasks/gym-ring snapshot wholesale (via [persistNextTasksForInstance]),
 * and the attention/gym/money/coursework fields merged into whatever
 * [BriefingState] is already persisted (preserving fields this tick doesn't
 * compute, e.g. weather/sleep/notable events, the same "merge, don't
 * clobber" shape [refreshYnabCategoriesIfConfigured] already uses). */
internal suspend fun applyComputeTickResult(context: Context, result: ComputeTickResult) {
    val manager = GlanceAppWidgetManager(context)
    for (glanceId in manager.getGlanceIds(BriefingWidget::class.java)) {
        persistNextTasksForInstance(context, glanceId, result.nextTasks)

        val current = try {
            getAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId)[WidgetKeys.BRIEFING_JSON]
                ?.let { BriefingState.fromJson(it) }
        } catch (e: JSONException) {
            null
        } ?: BriefingState.empty()

        // NOTE: `text` is deliberately left untouched (== current.text) here.
        // A prior task's brief asked for an on-device LLM call to populate
        // it, but `text`'s real Python-side source (`daily_briefing()` in
        // `lifeops/llm.py`) was already retired 2026-07-15 -- see
        // AnthropicClient.kt's top-level kdoc for the full reasoning
        // ("not worth the cost, latency, or hallucination surface for
        // restating a plain string"). Reintroducing that call here would
        // silently reverse a documented architecture decision using an
        // invented prompt, not a faithful port -- flagged as this change's
        // primary judgment call rather than done silently.
        val updated = current.copy(
            gymLast7d = result.gymLast7d,
            gymTarget = result.gymTarget,
            discretionaryDollars = result.discretionaryDollars ?: current.discretionaryDollars,
            courseworkHoursNext7d = result.courseworkHoursNext7d,
            attentionState = result.attention.state.name.lowercase(),
            attentionSymbol = result.attention.symbol,
            attentionLabel = result.attention.label,
            attentionHeadline = result.attention.headline,
            reasons = result.attention.reasons.map {
                UiAttentionReason(
                    domain = it.domain.name.lowercase(),
                    severity = it.severity.name.lowercase(),
                    title = it.title,
                    recommendedAction = it.recommendedAction,
                    due = it.due,
                )
            },
            nudges = result.nudges,
            fetchedAtEpochMillis = System.currentTimeMillis(),
        )
        updateAppWidgetState(context, glanceId) { prefs ->
            prefs[WidgetKeys.BRIEFING_JSON] = updated.toJson()
            prefs[WidgetKeys.LAST_FETCHED_AT] = System.currentTimeMillis()
        }
        BriefingWidget().update(context, glanceId)
    }
}

// ---------------------------------------------------------------------
// The WorkManager periodic worker itself.
// ---------------------------------------------------------------------

/**
 * The sole periodic on-device tick going forward -- replaces
 * `NextTasksRefreshWorker` (deleted; see this file's top-level kdoc) as the
 * one WorkManager job [BaseBriefingWidgetReceiver] schedules on widget
 * placement and cancels once the last instance is removed. Keeps every
 * orthogonal responsibility the old worker piggybacked on its 15-min cadence
 * (reverting expired optimistic task completions, phone-side location/
 * weather reporting, the YNAB category-balance refresh) unchanged, and
 * replaces its home-server JSON fetch with the real engine-driven
 * [runComputeTick].
 */
class LifeOpsComputeWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        // A periodic tick (UNIQUE_PERIODIC_WORK_NAME) and any manually
        // enqueued one (UNIQUE_ONE_TIME_WORK_NAME, via enqueueOnce) are two
        // INDEPENDENT unique-work chains -- WorkManager's uniqueness
        // guarantee is scoped per name, so nothing stops the periodic tick
        // and a manual trigger from running doWork() concurrently on two
        // threads. A review caught that this is a real exposure for
        // runChoreCycle specifically: two concurrent runs can both read the
        // same not-yet-`processed` completed task before either persists,
        // and both create a duplicate next-occurrence in FlowSavvy. Rather
        // than trying to unify the two WorkManager unique-work chains
        // (mixing enqueueUniquePeriodicWork/enqueueUniqueWork under one name
        // has its own undefined-policy-interaction risk), this in-process
        // Mutex just serializes every doWork() call regardless of which
        // chain triggered it -- correct and sufficient because WorkManager
        // runs its workers within this app's own single process, so a
        // static Mutex genuinely covers every possible concurrent caller.
        tickMutex.withLock { doComputeTick() }
    }

    private suspend fun doComputeTick(): Result {
        // Unconditional, same as the old worker: a stuck optimistic
        // completion (PendingRemovals.kt) must revert even with zero
        // connectivity.
        revertExpiredPendingCompletions(applicationContext)

        // Best-effort, self-gated, and deliberately run before any
        // config/reachability check below -- same reasoning as the old
        // worker (weather/location have zero dependency on the panel or
        // FlowSavvy being configured at all).
        reportLocationIfDue(applicationContext)
        reportWeatherIfDue(applicationContext)
        refreshYnabCategoriesIfConfigured(
            applicationContext,
            force = inputData.getBoolean(INPUT_FORCE_YNAB_REFRESH, false),
        )

        val now = LocalDateTime.now()
        val db = LifeOpsDatabase.getInstance(applicationContext)

        // Weekly digest (llm.py's weekly_digest, the on-device port of
        // runner.py's Sunday-only run_digest) -- self-gated same as
        // reportLocationIfDue/reportWeatherIfDue/refreshYnabCategoriesIfConfigured
        // above, deliberately run before the FlowSavvy config check below:
        // the digest has zero dependency on FlowSavvy, only on local history
        // + an Anthropic API key, same reasoning weather/location already
        // established for their own zero-FlowSavvy-dependency pieces.
        runWeeklyDigestIfDue(applicationContext, db, now)

        // YNAB categorize/approve/hold/cover write tick (YnabWrite.kt's port
        // of runner.py's run_ynab) -- self-gated once/day same as the digest
        // above, deliberately run before the FlowSavvy config check below:
        // it has zero dependency on FlowSavvy, only on a configured YNAB
        // token (and optionally an Anthropic API key for novel payees).
        // Ungated (no BiometricGate call) -- see YnabWrite.kt's top-level
        // kdoc for why this matches run_ynab's own unattended, background
        // behavior in the Python source.
        runYnabWriteTickIfConfigured(applicationContext, now)

        WidgetConfigStore.importFlowSavvyConfigFileIfPresent(applicationContext)
        val flowSavvyBaseUrl = WidgetConfigStore.getFlowSavvyBaseUrl(applicationContext)
        val flowSavvyToken = WidgetConfigStore.getFlowSavvyToken(applicationContext)
        if (flowSavvyBaseUrl == null || flowSavvyToken == null) {
            // Not configured yet -- nothing left to do (the on-device
            // compute tick needs FlowSavvy data to run against).
            Log.i(TAG, "skipping on-device compute tick: FlowSavvy is not configured")
            return Result.success()
        }

        val client = FlowSavvyClient(flowSavvyBaseUrl, flowSavvyToken)
        val discretionaryDollars = readCurrentDiscretionaryDollars(applicationContext)

        // Chore next-occurrence write-back (runChoreCycle/ChoreCycle.kt) --
        // wrapped in its OWN try/catch, deliberately separate from the main
        // tick's below: a hiccup on this write-capable path shouldn't block
        // the read-only attention/next-tasks compute this tick is centered
        // on, and vice versa. Errors are logged, not rethrown -- same "one
        // domain's failure doesn't abort the others" posture the old Python
        // `_run()` sweep had per-domain.
        //
        // Catches `Exception` broadly, not just the two named FlowSavvy
        // types -- a review caught that the original narrower catch let a
        // malformed JSON response (JSONException from FlowSavvyClient's own
        // parsing) or any other unexpected failure inside runChoreCycle
        // propagate straight out of doWork(), aborting the ENTIRE tick
        // (including gym/social/attention below) rather than being isolated
        // to just the chore write-back, exactly the failure mode this
        // try/catch's own comment above says it exists to prevent.
        try {
            runChoreCycle(db, RealChoreCompletedFetch(client), RealChoreTaskCreator(client), now)
        } catch (e: Exception) {
            Log.e(TAG, "chore cycle failed, isolated from the rest of this tick", e)
        }

        // Read BEFORE this tick's own FlowSavvy fetch below -- see
        // OnDeviceSystemHealth.kt's top-level kdoc point 6 for why computing
        // this after would be circular (a fetch that just succeeded would
        // always look perfectly fresh, making staleness undetectable).
        val systemHealth = computeOnDeviceSystemHealth(applicationContext, now)

        // Fetched ONCE here (rather than inside runComputeTick, which used to
        // own this fetch) so gym-plan-cycle's day-context assembly
        // (buildGymCandidateDays' deadlineHeavy input) can reuse the exact
        // same already-fetched incomplete-tasks list runComputeTick's own
        // coursework-at-risk/overdue/due-today math is built from, instead of
        // issuing a second, redundant `list_items(itemType="task", ...)` call
        // every tick -- see GymPlanCycle.kt's top-level kdoc.
        val fetchResult = try {
            RealFlowSavvyFetch(client).fetch(now)
        } catch (e: FlowSavvyConnectionException) {
            Log.e(TAG, "FlowSavvy unreachable during on-device compute tick", e)
            return Result.retry()
        } catch (e: FlowSavvyHttpException) {
            // Mirrors the old worker's "malformed body shouldn't crash the
            // worker or hammer a response shape that won't change" posture.
            Log.e(TAG, "FlowSavvy returned an error during on-device compute tick", e)
            return Result.success()
        }
        // Only reached when the fetch above actually succeeded (both catch
        // blocks above return early) -- mark FlowSavvy's on-device sync
        // marker fresh for the NEXT tick's staleness computation. See
        // OnDeviceSystemHealth.kt's recordFlowSavvySyncSuccess kdoc.
        recordFlowSavvySyncSuccess(applicationContext, now.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())

        // Real gym scheduling write-back (runGymPlanCycle/GymPlanCycle.kt) --
        // wrapped in its OWN try/catch for the exact same reason the chore
        // cycle above is: a hiccup on this write-capable path (a malformed
        // event/task response, an unexpected FlowSavvy error creating a gym
        // block) must not abort the read-only attention/next-tasks compute
        // below, and vice versa. If FlowSavvy is otherwise reachable (this
        // point is only reached once the primary fetch above already
        // succeeded), gym-plan-cycle's own reads (scheduled gym tasks,
        // calendar events, existing wind-down blocks) are attempted fresh
        // every tick regardless of whether a PRIOR tick's gym cycle failed --
        // there is no persisted "gym cycle succeeded" state to get stuck on.
        try {
            runGymPlanCycle(
                db,
                RealGymPlanFetch(client),
                fetchResult.incompleteTasks,
                RealGymTaskCreator(client),
                RealGymTaskDeleter(client),
                now,
            )
        } catch (e: Exception) {
            Log.e(TAG, "gym plan cycle failed, isolated from the rest of this tick", e)
        }

        val result = runComputeTick(db, FlowSavvyFetch { fetchResult }, discretionaryDollars, now, systemHealth = systemHealth)

        applyComputeTickResult(applicationContext, result)
        return Result.success()
    }

    /** Reads back whatever `discretionaryCurrentDollars`
     * [refreshYnabCategoriesIfConfigured] just computed (from any placed
     * widget instance's persisted [BriefingState] -- they're all written the
     * same value in the same call) so this tick's [AttentionFacts] uses the
     * real, current YNAB figure rather than re-deriving it. `null` if no
     * widget instance exists yet or none has a YNAB figure -- the money
     * branch of [Attention.compute] already treats `null` as "not
     * applicable," not "zero." */
    private suspend fun readCurrentDiscretionaryDollars(context: Context): Int? {
        val manager = GlanceAppWidgetManager(context)
        for (glanceId in manager.getGlanceIds(BriefingWidget::class.java)) {
            val value = try {
                getAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId)[WidgetKeys.BRIEFING_JSON]
                    ?.let { BriefingState.fromJson(it) }
                    ?.discretionaryCurrentDollars
            } catch (e: JSONException) {
                null
            }
            if (value != null) return value
        }
        return null
    }

    /** Restores any task whose pending completion (checkbox tap) has passed
     * the ~10-min hard timeout without ever being confirmed complete or
     * confirmed failed -- see [PendingRemovals.takeExpired]. Moved here from
     * the now-deleted `NextTasksRefreshWorker.kt`, unchanged. */
    private suspend fun revertExpiredPendingCompletions(context: Context) {
        val manager = GlanceAppWidgetManager(context)
        val now = System.currentTimeMillis()
        for (glanceId in manager.getGlanceIds(BriefingWidget::class.java)) {
            var didRestore = false
            updateAppWidgetState(context, glanceId) { prefs ->
                val expired = PendingRemovals.takeExpired(prefs, now)
                if (expired.isEmpty()) return@updateAppWidgetState
                val currentJson = prefs[WidgetKeys.NEXT_TASKS_JSON] ?: return@updateAppWidgetState
                val current = try {
                    NextTasksState.fromJson(currentJson)
                } catch (e: JSONException) {
                    return@updateAppWidgetState
                }
                val existingIds = current.tasks.map { it.id }.toSet()
                val restored = expired.filterNot { it.id in existingIds }
                if (restored.isEmpty()) return@updateAppWidgetState
                prefs[WidgetKeys.NEXT_TASKS_JSON] = current.copy(tasks = current.tasks + restored).toJson()
                didRestore = true
            }
            if (didRestore) BriefingWidget().update(context, glanceId)
        }
    }

    companion object {
        private const val TAG = "LifeOpsComputeWorker"
        const val INPUT_FORCE_YNAB_REFRESH = "force_ynab_refresh"
        const val UNIQUE_PERIODIC_WORK_NAME = "lifeops_compute_tick_periodic"

        /** Shared unique name for every manually-triggered one-time tick
         * (widget placement, "run catchup"/"force refresh", Settings' Save
         * button, the delayed post-YNAB-tile-tap refresh). Every call site
         * used to call `WorkManager.enqueue()` directly with no unique name
         * at all, which meant e.g. two quick taps of "Run catchup", or a
         * manual tap landing while the periodic tick happened to be running,
         * could execute [LifeOpsComputeWorker.doWork] concurrently on two
         * threads -- both read-merge-write [applyComputeTickResult]'s
         * per-instance Glance state independently, so the loser's write
         * (its `nudges`/`attentionState`) could be silently clobbered by the
         * winner's stale `current` read. Routing every manual trigger through
         * [enqueueOnce] with `ExistingWorkPolicy.KEEP` collapses redundant
         * concurrent requests into whichever one is already
         * queued/running, closing that race for the common case (repeated
         * manual taps) without changing any call site's existing
         * fire-and-forget semantics. */
        private const val UNIQUE_ONE_TIME_WORK_NAME = "lifeops_compute_tick_once"
        private const val MIN_BACKOFF_MS = 30_000L

        /** Serializes every [doWork] call -- see [doWork]'s own comment for
         * why WorkManager's unique-work-name mechanism alone (the
         * [UNIQUE_ONE_TIME_WORK_NAME] fix above) doesn't cover a periodic
         * tick racing a manual one. A single companion-object [Mutex] is
         * correct here specifically because WorkManager executes workers
         * within this app's own process, not a separate one. */
        private val tickMutex = Mutex()

        /** Schedules the recurring 15-minute tick -- same cadence/backoff
         * policy the old `NextTasksRefreshWorker` used. Safe to call multiple
         * times: `ExistingPeriodicWorkPolicy.KEEP` means a repeat call (e.g.
         * `onEnabled` firing again) won't reset an already-scheduled
         * request's interval/backoff. */
        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<LifeOpsComputeWorker>(15, TimeUnit.MINUTES)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, MIN_BACKOFF_MS, TimeUnit.MILLISECONDS)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        /** Enqueues a single manually-triggered tick. Every caller that used
         * to build its own `OneTimeWorkRequestBuilder<LifeOpsComputeWorker>()`
         * and call the plain, non-unique `WorkManager.enqueue()` should call
         * this instead -- see [UNIQUE_ONE_TIME_WORK_NAME]'s kdoc for why.
         * [configure] lets a caller customize the request (e.g. input data,
         * expedited policy, initial delay) before it's enqueued. */
        fun enqueueOnce(
            context: Context,
            policy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP,
            configure: OneTimeWorkRequest.Builder.() -> OneTimeWorkRequest.Builder = { this },
        ) {
            val request = OneTimeWorkRequestBuilder<LifeOpsComputeWorker>().configure().build()
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_ONE_TIME_WORK_NAME, policy, request)
        }
    }
}
