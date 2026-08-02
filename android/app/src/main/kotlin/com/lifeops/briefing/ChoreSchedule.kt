package com.lifeops.briefing

import java.time.LocalDate

/**
 * Re-derivation pass for chore, per the same "does this dissolve into
 * Routine + LifeScript the way gym did" question docs/lifeops_capability_todo.md
 * left open after gym's migration ("whether chore/social get re-derived
 * through the same windows+LifeScript model next ... both are thin enough
 * that they plausibly should, per the same reasoning that dissolved gym").
 *
 * This file REPLACES the former `ChoreEngine.kt` (deleted) with the same
 * finding written down rather than silently applied: **chore does not need
 * [TimeSlot]/[LifeScript] at all, and forcing it in would be distortion, not
 * simplification.**
 *
 * ## Why gym decomposed and chore doesn't need to
 *
 * Gym's bespoke code was a real algorithm: for each of several CANDIDATE
 * DAYS, decide whether a boolean flag combination on that day makes a
 * clock-time slot available (`slot_for`), then greedily pick which subset of
 * viable days to book under a consecutive-day cap (`scheduleRoutine`). That
 * per-day boolean decision is exactly what [LifeScript] conditions plus
 * [TimeSlot]/[scheduleRoutine] were built to express and execute.
 *
 * Chore has no such decision to make. Given one already-completed task, the
 * next occurrence's due date is `nextDueDate` on a SINCE_LAST [Routine] --
 * already the generic primitive, already merged, already used here exactly
 * as gym's `plan()` now uses [scheduleRoutine]. There is no list of
 * candidate days to evaluate a condition against and no slot to pick among:
 * one input produces exactly one deterministic due date. Introducing a
 * `TimeSlot` with a condition string here would need a fake "always true"
 * condition and a fake single-candidate-day list just to route through
 * machinery designed for a problem (which of several days/slots is
 * available) chore doesn't have.
 *
 * ## What's left, and why it's genuinely bespoke rather than unmigrated
 *
 * Two pieces remain in this file, both intentionally NOT LifeScript, for the
 * same reason `GymSchedule.kt`'s kdoc gives for keeping its own alert-wording
 * and wind-down logic bespoke -- they are not per-day scheduling predicates:
 * - **`[cycle:Nd]` tag discovery** ([CYCLE_TAG], the parse loop in [plan]):
 *   this is free-text extraction out of a note string, not a boolean
 *   evaluated against a context map. LifeScript has no string-parsing/regex
 *   facility (and per its own kdoc's scope note, deliberately never will --
 *   it only ever reads already-supplied named variables). The `processed`
 *   dedup-by-id bookkeeping is the same kind of discovery-mechanism
 *   plumbing, not recurrence math.
 * - **Output field assembly** (lead-time cap arithmetic, `dueDateTime`/
 *   `canBeStartedAt` string building): LifeScript produces one boolean or
 *   number per evaluation for a caller to branch on -- it is not a
 *   templating engine and has no notion of "build this record." Once
 *   [nextDueDate] returns a date, formatting it into the two ISO strings
 *   `ChoreCreate` needs is plain Kotlin, same as gym's own `Create`/`Delete`
 *   action construction in `GymSchedule.kt`'s `plan()`.
 *
 * Both are structurally identical to gym's own retained bespoke pieces
 * (sick-week short-circuit, floor/alert wording): thin, domain-specific,
 * and outside what a read-only boolean expression grammar is for.
 */

/** One already-completed task record fed into [plan]. Unchanged from the
 * former `ChoreEngine.kt`'s `CompletedChore` -- field-for-field match of the
 * Python `completed` dict entries actually read by `chore_engine.py`'s
 * `plan()`. See that file's history for the full provenance notes this kdoc
 * used to carry. */
data class CompletedChore(
    val id: String?,
    val title: String?,
    val notes: String? = null,
    val completedDate: String? = null,
    val durationMinutes: Int? = null,
    val minLengthMinutes: Int? = null,
    val listId: String? = null,
    val priority: String? = "low",
    val schedulingHoursId: String? = null,
    val dueTime: String? = null,
)

/** A newly-created next-occurrence task, mirroring the Python `creates[]`
 * dict entries' actual field set field-for-field. */
data class ChoreCreate(
    val title: String,
    val listId: String?,
    val durationMinutes: Int?,
    val minLengthMinutes: Int?,
    val priority: String?,
    val schedulingHoursId: String?,
    val notes: String?,
    val dueDateTime: String,
    val canBeStartedAt: String,
)

/** Input to [plan]: mirrors the Python `{"completed": [...], "processed": [...]}` shape. */
data class ChorePlanInput(
    val completed: List<CompletedChore>,
    val processed: List<String> = emptyList(),
)

/** Output of [plan]: mirrors the Python `{"creates": [...], "processed": [...]}`
 * return shape. */
data class ChorePlanResult(
    val creates: List<ChoreCreate>,
    val processed: List<String>,
)

/** Ports `re.search(r"\[cycle:(\d+)d\]", ...)`. See this file's top-level kdoc
 * for why this stays plain Kotlin regex rather than a LifeScript concept. */
private val CYCLE_TAG = Regex("""\[cycle:(\d+)d\]""")

/**
 * Ports `chore_engine.plan()` verbatim: for each completed task not already
 * in `processed`, parse its `[cycle:Nd]` note tag, compute the next due date
 * (completion date + N days) through the shared [Routine]/[nextDueDate]
 * SINCE_LAST primitive -- the same generic due-date math `GymSchedule.kt`'s
 * `plan()` delegates its own scheduling to, just without any [TimeSlot]
 * layered on top, since there is no candidate-day/slot decision to make here
 * (see this file's top-level kdoc) -- then a start-lead capped at 3 days,
 * and emits a create. Skips (without erroring) anything that isn't a managed
 * chore or is malformed, per the case-by-case notes below -- mirrors the
 * Python function's own "one bad record shouldn't abort the batch" behavior.
 *
 * `processed` in the result is capped to the last 300 ids (`processed[-300:]`
 * in Python), same dedup-state-size bound.
 */
fun plan(input: ChorePlanInput): ChorePlanResult {
    val processed = input.processed.toMutableList()
    val seen = processed.toMutableSet()
    val creates = mutableListOf<ChoreCreate>()

    for (c in input.completed) {
        val cid = c.id
        if (cid == null || cid in seen) continue

        val match = CYCLE_TAG.find(c.notes ?: "")
            ?: continue // not a managed chore

        val n = match.groupValues[1].toInt()

        // Ports `datetime.date.fromisoformat(...)` inside a try/except:
        // malformed or missing completed_date skips just this item, not the
        // whole batch.
        val comp: LocalDate = try {
            LocalDate.parse(c.completedDate ?: throw IllegalArgumentException())
        } catch (e: Exception) {
            continue
        }

        if (c.title.isNullOrEmpty()) {
            // Malformed forever, not transiently -- mark processed so this
            // exact record isn't refetched and re-skipped on every future
            // run. Regression case ported verbatim from the Python test
            // suite (test_missing_title_marked_processed_not_retried_forever).
            processed.add(cid)
            seen.add(cid)
            continue
        }

        val routine = Routine(id = cid, times = 1, perDays = n, anchor = Anchor.SINCE_LAST)
        val nextd = nextDueDate(routine, comp)
        val t = c.dueTime ?: "20:00"
        val lead = minOf(3, maxOf(0, n - 1))

        creates.add(
            ChoreCreate(
                title = c.title,
                listId = c.listId,
                durationMinutes = c.durationMinutes,
                minLengthMinutes = c.minLengthMinutes,
                priority = c.priority ?: "low",
                schedulingHoursId = c.schedulingHoursId,
                notes = c.notes,
                dueDateTime = "${nextd}T$t:00",
                canBeStartedAt = "${nextd.minusDays(lead.toLong())}T$t:00",
            )
        )
        processed.add(cid)
        seen.add(cid)
    }

    val cappedProcessed = if (processed.size > 300) processed.takeLast(300) else processed
    return ChorePlanResult(creates = creates, processed = cappedProcessed)
}
