package com.lifeops.briefing

import com.lifeops.briefing.data.LifeOpsDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Wires `GymSchedule.kt`'s real scheduling engine (`plan()`) -- the ONE
 * piece `LifeOpsComputeWorker.kt`'s own kdoc has, since its original
 * version, explicitly flagged as deliberately NOT wired ("GymSchedule.kt's
 * full `plan()` (slot-booking/calendar-action creation/wind-down blocks) --
 * needs a next-N-days calendar-blocked/deadline-heavy/sleep-quality context
 * this tick doesn't assemble") -- into the real periodic compute tick,
 * following exactly the same read-assemble-write shape `ChoreCycle.kt`
 * already established for chore next-occurrence creation.
 *
 * Ports `lifeops/runner.py`'s `run_gym` write path (verified against that
 * source directly, not guessed): `_logged_create(fs, "gym", ...)` for every
 * `"create"` action, which is `fs.create_task(**kwargs)` -- **a task, not a
 * calendar event** (`gather.py`'s `gym_input` builds `scheduled` off
 * `fs.list_items(itemType="task", query="Gym", ...)`, and `config.LIST_PERSONAL`
 * is a task list id). [FlowSavvyClient.createEvent] is therefore NOT the
 * right call here despite a "calendar-blocking action" framing suggesting it
 * -- [FlowSavvyClient.createTask] is, exactly matching [ChoreTaskCreator]'s
 * own `createTask` wiring for chore next-occurrences. Wind-down blocks are
 * the same: `_logged_create(fs, "gym", ..., title="Wind down -- early gym", ...)`
 * -- also `create_task`, not `create_event`.
 *
 * ## Context this assembles beyond what [computeGymPlanRespectingBlocks] does
 *
 * [computeGymPlanRespectingBlocks] (the existing block-day quick-action path)
 * only sets [GymDay.gymBlocked] -- every other flag defaults to "nothing else
 * known." This cycle assembles the REST of what gym's real [TimeSlot]
 * condition strings (`EVENING_CONDITION`/`MORNING_CONDITION` in
 * `GymSchedule.kt`) actually read, verified against each flag's real source
 * in `gather.py`'s `gym_input`, not guessed:
 * - [GymDay.eveningBlocked] / [GymDay.dayAfterShow] / [GymDay.priorNightBlocked]:
 *   real FlowSavvy events (`list_items(itemType="event")`) overlapping the
 *   18:00-21:00 evening window, or existing on a day at all -- ports
 *   `gym_input`'s own `_consider(start, end)` overlap test exactly. Narrower
 *   than the Python source in one documented way: `gym_input` scopes this to
 *   `config.EVENT_CALS` (a specific calendar-id allowlist) plus
 *   partner/friends-titled tasks; Android has no equivalent calendar-id or
 *   partner/friends-task config wired anywhere yet (same
 *   "no on-device equivalent of that config value" narrowing this file's own
 *   kdoc already documents for `config.LIST_COURSE`), so every fetched event
 *   is considered instead of a calendar-scoped subset.
 * - [GymDay.deadlineHeavy]: real per-day coursework load built directly from
 *   this tick's already-fetched incomplete FlowSavvy tasks' `dueDateTime`/
 *   remaining minutes -- ports `gym_input`'s own `_heavy(ds)` (`>=180min due
 *   that day or the next`) exactly. Built from the raw fetched tasks
 *   directly (same data [DeadlineRisk.kt]'s `Assignment`s are built from in
 *   [runComputeTick]), not by reconstructing a date from `Assignment.dueInHours`
 *   -- `Assignment` only carries an hours-from-now offset, not an absolute
 *   due date, so re-deriving a calendar date from it would be lossy at day
 *   boundaries in a way going back to the raw `dueDateTime` string isn't.
 * - [GymDay.sleepOk] / [GymRules.allowMorning]: left at their
 *   [GymDay]/[GymRules] defaults (`true`) -- there is no on-device wearable
 *   sleep-quality signal (`gather.py`'s `_sleep_ok` prefers real Health
 *   Connect/watch data) or adherence-learning model (`gather.py`'s
 *   `adherence.gym(now)`) anywhere in this codebase (confirmed by grepping
 *   for `HealthConnect`/`sleep_dur`/`adherence` across every `.kt` file --
 *   the only hits are unrelated docstring mentions). Faking either would be
 *   inventing data this port has no real source for, not a faithful port --
 *   flagged here as a real, documented gap rather than silently defaulted
 *   without comment.
 *
 * ## Dedup: no new Room entity needed
 *
 * Unlike chore next-occurrence creation (which needs [ChoreCycleStateEntity]
 * because a completed task disappears from FlowSavvy's "incomplete" set the
 * moment [creator] processes it, so there is nothing left to re-check against
 * on a later tick), gym's dedup has a real, live, already-authoritative
 * source: **FlowSavvy's own current schedule**. Every tick, [fetch] re-reads
 * which "Gym"-titled tasks are currently open in the plan window (mirroring
 * `run_gym`'s own `gym_open` fetch) and feeds their dates into [GymInput.scheduled]
 * -- `plan()` (unchanged, already tested) excludes any date already in
 * `scheduled` from its candidate pool via [GymInput]'s `busyDates`/`excludeDates`
 * plumbing (see `GymSchedule.kt`'s `plan()` body), so a date this cycle
 * already booked physically cannot be re-chosen on a later tick, the same
 * way `run_gym`'s own `a["date"] not in have` check (itself just a defensive
 * mirror of the same exclusion `plan()`'s candidate-building already
 * guarantees) works. Wind-down blocks get the identical live-read dedup
 * `run_gym` itself uses: a `query="Wind down"` fetch checked before each
 * create, not a persisted id list. A new Room entity would duplicate a fact
 * FlowSavvy already answers authoritatively and risk drifting out of sync
 * with it (e.g. a block deleted directly in FlowSavvy by the user) --
 * exactly the kind of ungrounded write-path state this task's own brief
 * warns against, so it was deliberately not added.
 *
 * ## What is NOT wired (documented, not silently dropped)
 *
 * The Python source's sick-week short-circuit (`plan()`'s own
 * `sick_until`-gated delete-and-bail branch) has no Android equivalent --
 * there is no `sickUntil` persistence or Settings UI anywhere on-device
 * (confirmed: [computeGymPlanRespectingBlocks] already passes no `sickUntil`
 * either, same as this cycle). [GymInput.sickUntil] is therefore always
 * `null` here, so `plan()` can never return a [GymAction.Delete] in
 * practice; the delete branch below still exists (mirrors `run_gym`'s own
 * delete handling faithfully) so that a future sick-week feature only needs
 * to wire `sickUntil`, not this write path.
 */

/** One `"Gym"`-titled open FlowSavvy task within the plan window -- mirrors
 * the fields `gather.py`'s `gym_input` reads off its own `gym_open` fetch
 * (`id`/`startDateTime`/`endDateTime`) to build `scheduled`. */
internal data class RawGymScheduledTask(
    val id: String?,
    val startDateTime: String?,
    val endDateTime: String?,
)

/** One real FlowSavvy calendar event's start/end -- mirrors the two fields
 * `gym_input`'s own `_consider(e.get("startDateTime"), e.get("endDateTime"))`
 * reads off each fetched event. */
internal data class RawCalendarEvent(
    val startDateTime: String?,
    val endDateTime: String?,
)

internal data class GymPlanFetchResult(
    val scheduledGymTasks: List<RawGymScheduledTask>,
    val calendarEvents: List<RawCalendarEvent>,
    /** ISO calendar-date strings ("2026-08-02") a "Wind down" task already
     * exists on -- mirrors `run_gym`'s own `existing` set, read live from
     * FlowSavvy rather than persisted, per this file's top-level kdoc. */
    val existingWindDownDates: Set<String>,
)

/** Testability seam, same shape as [FlowSavvyFetch]/[ChoreCompletedFetch]: a
 * real implementation ([RealGymPlanFetch]) wraps [FlowSavvyClient]; tests
 * supply fixture data with no network dependency. */
internal fun interface GymPlanFetch {
    fun fetch(): GymPlanFetchResult
}

/** Testability seam for the create-task write side -- real callers wire
 * [FlowSavvyClient.createTask] (its own retry policy is what makes this
 * write safe, same as [ChoreTaskCreator]). */
internal fun interface GymTaskCreator {
    fun create(body: Map<String, Any?>)
}

/** Testability seam for the delete side (sick-week bail path; see this
 * file's top-level kdoc for why it is currently unreachable but kept). */
internal fun interface GymTaskDeleter {
    fun delete(id: String)
}

private fun JSONObject.optStringOrNull(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

/** Real network-backed [GymPlanFetch]. Mirrors `run_gym`'s own `gym_open`
 * fetch (`list_items(itemType="task", query="Gym", completed=False)`,
 * title-prefix-filtered to `"Gym"`), `gym_input`'s event fetch
 * (`list_items(itemType="event", ...)`, narrower here -- see this file's
 * top-level kdoc), and its own `existing` wind-down check
 * (`list_items(query="Wind down")`). */
internal class RealGymPlanFetch(private val client: FlowSavvyClient) : GymPlanFetch {
    override fun fetch(): GymPlanFetchResult {
        val gymTasksArr = client.listItems(
            mapOf("itemType" to "task", "query" to "Gym", "completed" to false),
        ).optJSONArray("items") ?: JSONArray()
        val scheduledGymTasks = (0 until gymTasksArr.length()).mapNotNull { i ->
            val o = gymTasksArr.getJSONObject(i)
            val title = o.optStringOrNull("title") ?: return@mapNotNull null
            if (!title.startsWith("Gym")) return@mapNotNull null
            RawGymScheduledTask(
                id = o.optStringOrNull("id"),
                startDateTime = o.optStringOrNull("startDateTime"),
                endDateTime = o.optStringOrNull("endDateTime"),
            )
        }

        val eventsArr = client.listItems(mapOf("itemType" to "event")).optJSONArray("items") ?: JSONArray()
        val calendarEvents = (0 until eventsArr.length()).map { i ->
            val o = eventsArr.getJSONObject(i)
            RawCalendarEvent(
                startDateTime = o.optStringOrNull("startDateTime"),
                endDateTime = o.optStringOrNull("endDateTime"),
            )
        }

        val windDownArr = client.listItems(mapOf("query" to "Wind down")).optJSONArray("items") ?: JSONArray()
        val existingWindDownDates = (0 until windDownArr.length()).mapNotNull { i ->
            windDownArr.getJSONObject(i).optStringOrNull("startDateTime")?.take(10)
        }.toSet()

        return GymPlanFetchResult(scheduledGymTasks, calendarEvents, existingWindDownDates)
    }
}

/** Real network-backed [GymTaskCreator]/[GymTaskDeleter]. Port
 * `_logged_create`'s `fs.create_task(**kwargs)` / `fs.delete_item(id)`. See
 * `ChoreCycle.kt`'s own kdoc on `RealChoreTaskCreator` for why
 * `_logged_create`'s other two side effects (`actions.log`/`_touch`) aren't
 * ported -- same reasoning applies here. */
internal class RealGymTaskCreator(private val client: FlowSavvyClient) : GymTaskCreator {
    override fun create(body: Map<String, Any?>) {
        client.createTask(body)
    }
}

internal class RealGymTaskDeleter(private val client: FlowSavvyClient) : GymTaskDeleter {
    override fun delete(id: String) {
        client.deleteItem(id)
    }
}

/** `"2026-08-02T19:30:00"` -> `19` (the hour field), or `null` if too short
 * to contain one -- same raw-substring style `ChoreCycle.kt`'s own `dueTime`
 * extraction and `gather.py`'s `_h()` use, not a full `LocalDateTime.parse`
 * (which would throw on a value FlowSavvy sometimes truncates/omits). */
private fun hourOf(isoDateTime: String?): Int? =
    isoDateTime?.takeIf { it.length >= 13 }?.substring(11, 13)?.toIntOrNull()

private fun dateOf(isoDateTime: String?): LocalDate? =
    isoDateTime?.takeIf { it.length >= 10 }?.let { runCatching { LocalDate.parse(it.substring(0, 10)) }.getOrNull() }

/**
 * Builds the real 14-day [GymDay] candidate window this cycle needs -- ports
 * `gym_input`'s `_consider`/`_heavy`/day-context assembly loop exactly (see
 * this file's top-level kdoc for the two documented narrowings versus the
 * Python source). [blockedDates] are the already-fetched
 * [com.lifeops.briefing.data.BlockedDayDao] rows (same source
 * [gymDaysRespectingBlocks] uses), passed in rather than re-queried so this
 * function stays a pure, directly-testable helper.
 */
internal fun buildGymCandidateDays(
    today: LocalDate,
    days: Int,
    blockedDates: Set<String>,
    calendarEvents: List<RawCalendarEvent>,
    deadlineLoadMinutesByDate: Map<LocalDate, Int>,
): List<GymDay> {
    val horizonEnd = today.plusDays((days - 1).toLong())
    val lookbackStart = today.minusDays(1) // need yesterday too, for dayAfterShow/priorNightBlocked
    val shows = mutableSetOf<LocalDate>()
    val eveningBlocked = mutableSetOf<LocalDate>()
    for (e in calendarEvents) {
        val date = dateOf(e.startDateTime) ?: continue
        if (date < lookbackStart || date > horizonEnd) continue
        val startHour = hourOf(e.startDateTime) ?: continue
        val endHour = hourOf(e.endDateTime) ?: (startHour + 2)
        shows.add(date)
        // Ports `_consider`'s overlap test exactly: `sh < 21 and eh > 18`.
        if (startHour < 21 && endHour > 18) eveningBlocked.add(date)
    }

    fun heavy(date: LocalDate): Boolean {
        val next = date.plusDays(1)
        val load = (deadlineLoadMinutesByDate[date] ?: 0) + (deadlineLoadMinutesByDate[next] ?: 0)
        return load >= 180
    }

    return (0 until days).map { offset ->
        val date = today.plusDays(offset.toLong())
        val prev = date.minusDays(1)
        GymDay(
            date = date,
            gymBlocked = date.toString() in blockedDates,
            eveningBlocked = date in eveningBlocked,
            dayAfterShow = prev in shows,
            priorNightBlocked = prev in eveningBlocked,
            deadlineHeavy = heavy(date),
            sleepOk = true, // no on-device wearable sleep signal -- see top-level kdoc
        )
    }
}

/** Ports `gather.py`'s own per-day coursework `load` map (the input
 * [buildGymCandidateDays]'s `heavy()` sums): `load[due_date] += max(0,
 * duration - progress)` for every fetched incomplete task carrying a real
 * `dueDateTime`. Takes the raw fetched tasks directly (not [Assignment]) --
 * see this file's top-level kdoc for why. */
internal fun deadlineLoadMinutesByDate(tasks: List<RawTaskItem>): Map<LocalDate, Int> {
    val load = mutableMapOf<LocalDate, Int>()
    for (t in tasks) {
        val date = dateOf(t.dueDateTime) ?: continue
        val remaining = maxOf(0, (t.durationMinutes ?: 0) - (t.progressMinutes ?: 0))
        load[date] = (load[date] ?: 0) + remaining
    }
    return load
}

/** `"2026-08-02T05:10:00"` -> the [ScheduledGym] this task represents,
 * mirroring `gym_input`'s own `scheduled.append({...})` field-for-field
 * (`manual` is always `false` here -- see this file's top-level kdoc: by the
 * time `gym_open`/this fetch runs, any genuinely manual "Gym"-titled entry
 * has already been backfilled into history and dropped, matching
 * `_gym_backfill`'s ordering guarantee -- Android has no equivalent backfill
 * pass yet, so this is a documented, honest simplification, not a faithful
 * `manual` distinction). `started`: `date == today && hour <= now.hour + 2`,
 * ports `_d(st) == today.isoformat() and _h(st) <= now.hour + 2` exactly. */
internal fun toScheduledGym(t: RawGymScheduledTask, today: LocalDate, nowHour: Int): ScheduledGym? {
    val date = dateOf(t.startDateTime) ?: return null
    val hour = hourOf(t.startDateTime) ?: 12
    return ScheduledGym(date = date, manual = false, started = date == today && hour <= nowHour + 2)
}

/**
 * The actual on-device gym-plan write tick: assemble the real 14-day
 * candidate window ([buildGymCandidateDays]), the real already-scheduled gym
 * blocks and trailing-week completions, run `GymSchedule.kt`'s `plan()`
 * against them, and apply every resulting action/wind-down block through
 * [creator]/[deleter] -- ports `run_gym`'s scheduling half end to end (the
 * stale-block cleanup / `gym_missed` logging half of `run_gym` has no
 * on-device equivalent anywhere yet -- see `WeeklyDigest.kt`'s own kdoc,
 * which already documents "no `gym_missed` equivalent" -- and is out of
 * scope here, since it is a read-and-log concern, not part of wiring
 * `plan()`'s write actions).
 *
 * No Context/WorkManager/Glance dependency -- [LifeOpsComputeWorker.doWork]
 * wires real [GymPlanFetch]/[GymTaskCreator]/[GymTaskDeleter] implementations
 * backed by a real [FlowSavvyClient]; tests exercise this directly against an
 * in-memory Room database and fake fetch/creator/deleter seams, same pattern
 * [runChoreCycle] already establishes.
 */
internal suspend fun runGymPlanCycle(
    db: LifeOpsDatabase,
    fetch: GymPlanFetch,
    incompleteTasks: List<RawTaskItem>,
    creator: GymTaskCreator,
    deleter: GymTaskDeleter,
    now: LocalDateTime = LocalDateTime.now(),
): GymPlan {
    val today = now.toLocalDate()
    val windowDays = 14
    val horizon = (0 until 7).map { today.plusDays(it.toLong()) }.toSet() // matches gym_input's own 7-day scheduling horizon for `scheduled`

    val persistedGym = db.routineDao().getById("gym")
    val rules = GymRules(target = persistedGym?.timesPerWindow ?: GymRules().target)

    val fetched = fetch.fetch()
    val scheduled = fetched.scheduledGymTasks.mapNotNull { toScheduledGym(it, today, now.hour) }
        .filter { it.date in horizon }
    val scheduledById = fetched.scheduledGymTasks.filter { t -> dateOf(t.startDateTime)?.let { it in horizon } == true }

    val completedDates = gymCompletedInTrailingWindow(db, today)
    val blockedDates = db.blockedDayDao().getDatesBetween(today.toString(), today.plusDays((windowDays - 1).toLong()).toString()).toSet()
    // Reuses the SAME incomplete-tasks fetch runComputeTick already performed
    // this tick (via FlowSavvyFetch) instead of a second, independent
    // FlowSavvy read -- the "reuse already-gathered context, don't re-fetch
    // it a different way" instruction this port follows throughout.
    val load = deadlineLoadMinutesByDate(incompleteTasks)
    val days = buildGymCandidateDays(today, windowDays, blockedDates, fetched.calendarEvents, load)

    val input = GymInput(
        today = today,
        completedCount = completedDates.size,
        completedDates = completedDates.toList(),
        scheduled = scheduled,
        days = days,
        rules = rules,
    )
    val result = plan(input)

    val haveDates = scheduled.map { it.date }.toSet()
    for (action in result.actions) {
        when (action) {
            is GymAction.Create -> {
                if (action.date in haveDates) continue // defensive mirror of run_gym's own `a["date"] not in have` check
                creator.create(
                    mapOf(
                        "title" to "Gym",
                        "isAutoScheduled" to false,
                        "startDateTime" to "${action.date}T${action.start}:00",
                        "endDateTime" to "${action.date}T${action.end}:00",
                        "bufferBeforeMinutes" to action.bufferBefore,
                        "bufferAfterMinutes" to action.bufferAfter,
                        "notes" to "Auto-scheduled by LifeOps.",
                    ),
                )
            }
            is GymAction.Delete -> {
                val id = scheduledById.firstOrNull { dateOf(it.startDateTime) == action.date }?.id
                if (id != null) deleter.delete(id)
            }
        }
    }

    for (w in result.windDown) {
        if (w.date.toString() in fetched.existingWindDownDates) continue
        creator.create(
            mapOf(
                "title" to "Wind down — early gym",
                "isAutoScheduled" to false,
                "startDateTime" to "${w.date}T${w.start}:00",
                "endDateTime" to "${w.date}T${w.end}:00",
            ),
        )
    }

    return result
}
