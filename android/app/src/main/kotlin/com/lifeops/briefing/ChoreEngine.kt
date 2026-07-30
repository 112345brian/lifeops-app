package com.lifeops.briefing

import java.time.LocalDate

/**
 * Kotlin port of `lifeops/engines/chore_engine.py` -- a deterministic
 * chore-recurrence engine. Given chores Brian just completed (each tagged
 * `[cycle:Nd]` in its notes), computes the next occurrence: due = completion
 * date + N days. Pure date math -- no judgment, no API. The caller (LLM or
 * otherwise) only gathers the completed tasks and creates whatever [plan]
 * returns.
 *
 * A `[cycle:Nd]` tag IS a routine definition (frequency = 1 per N days,
 * anchored to last completion) -- same object type as gym/social/meal's
 * cadence (see `Routine.kt`'s kdoc), just currently discovered from a
 * FlowSavvy task's note text instead of being a stored record. The due-DATE
 * math goes through that shared primitive ([Routine]/[nextDueDate], already
 * ported and merged); the per-task discovery loop below (parsing the tag,
 * tracking `processed`) stays chore-specific -- that's the "discovery
 * mechanism," not the recurrence math. Mirrors `chore_engine.py`'s own
 * module docstring on this point.
 *
 * Only `plan()` is ported. Python's `main()` does file I/O (reading an input
 * JSON, writing an output JSON) and is deliberately dropped, matching the
 * migration plan for `routine.py` -- this port is pure, no I/O.
 */

/**
 * One already-completed task record fed into [plan]. Field-for-field match
 * of the Python `completed` dict entries actually read by `plan()` (see
 * `chore_engine.py`'s module docstring input shape) -- `id`/`title`/`notes`/
 * `completed_date` are required for the due-check itself, the rest
 * (duration/scheduling metadata) just pass through unchanged into the
 * created task.
 *
 * Kotlin translation note: the Python input dict allows any of these keys to
 * be absent (`.get(...)`) or explicitly `null` (e.g. `dueTime`), and `plan()`
 * treats "absent" and "present-but-null" identically throughout. Modeling
 * every field as a nullable Kotlin property (rather than, say, an `Optional`
 * or a `Map`) reproduces that exactly: a caller either omits the property at
 * a default or passes `null` explicitly, and both look the same to [plan].
 */
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

/**
 * A newly-created next-occurrence task, mirroring the Python `creates[]`
 * dict entries' actual field set field-for-field.
 */
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

/**
 * Output of [plan]: mirrors the Python `{"creates": [...], "processed": [...]}`
 * return shape.
 */
data class ChorePlanResult(
    val creates: List<ChoreCreate>,
    val processed: List<String>,
)

/** Ports `re.search(r"\[cycle:(\d+)d\]", ...)`. */
private val CYCLE_TAG = Regex("""\[cycle:(\d+)d\]""")

/**
 * Ports `chore_engine.plan()` verbatim: for each completed task not already
 * in `processed`, parse its `[cycle:Nd]` note tag, compute the next due date
 * (completion date + N days, via the shared [Routine]/[nextDueDate]
 * primitive) and a start-lead capped at 3 days, and emit a create. Skips
 * (without erroring) anything that isn't a managed chore or is malformed,
 * per the case-by-case notes below -- mirrors the Python function's own "one
 * bad record shouldn't abort the batch" behavior.
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
