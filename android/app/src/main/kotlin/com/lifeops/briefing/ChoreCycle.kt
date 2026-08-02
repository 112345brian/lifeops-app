package com.lifeops.briefing

import com.lifeops.briefing.data.ChoreCycleStateEntity
import com.lifeops.briefing.data.LifeOpsDatabase
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Wires `ChoreSchedule.kt`'s already-ported, already-tested `plan()` to a
 * real FlowSavvy read (recently-completed `[cycle:Nd]`-tagged tasks) and a
 * real FlowSavvy WRITE (the next occurrence's `createTask` call) -- the one
 * piece `LifeOpsComputeWorker.kt`'s own kdoc flagged as deliberately NOT
 * wired yet ("Chore's actual next-occurrence task creation... a genuinely
 * riskier, separate feature... than the read-only attention/next-tasks
 * compute this tick is centered on").
 *
 * Ports `lifeops/runner.py`'s `run_chore` exactly:
 * - The FlowSavvy read: `fs.list_items(itemType="task", completed=True,
 *   modifiedAfter=st["lastRunUtc"])`, pre-filtered to notes containing the
 *   literal substring `"[cycle:"` (the SAME cheap pre-filter `run_chore`
 *   itself applies before ever calling `chore_engine.plan()` -- the full
 *   `\[cycle:(\d+)d\]` regex validation still happens inside `plan()`
 *   itself, unchanged).
 * - The dedup bookkeeping: `chore_state.json`'s `{"processed": [...],
 *   "lastRunUtc": "..."}`, persisted here as the singleton
 *   [ChoreCycleStateEntity] row instead of a JSON file (see that entity's
 *   kdoc for why one row, not one row per id).
 * - The write: `_logged_create(fs, "chore", op="cycled chore", **c)` ->
 *   [ChoreTaskCreator.create], which real callers wire to
 *   [FlowSavvyClient.createTask] -- FlowSavvyClient's OWN retry policy (see
 *   that file's kdoc) is what makes this write safe, not anything added
 *   here. `_logged_create`'s OTHER two side effects -- `actions.log(...)`
 *   (an audit-log entry enabling later undo) and `_touch()`
 *   (schedule-dirty -> a batched `fs.recalculate()` at the end of the whole
 *   `_run()` sweep across every domain) -- are deliberately NOT ported here:
 *   both are orchestrator-level, whole-run concerns spanning every domain
 *   (gym/meal/chore/etc.) in the Python source, not something a single
 *   domain's own write path re-derives, and neither has an Android
 *   equivalent to plug into yet (no on-device audit log, and this tick has
 *   no multi-domain "batch recalculate at the end" step). Flagged here
 *   rather than silently skipped.
 *
 * ## The dedup guarantee this provides, and its one known gap
 *
 * Per-tick: [runChoreCycle] reads the full persisted `processed` id list,
 * feeds it into `plan()` alongside every completed-and-cycle-tagged task
 * fetched this tick, and `plan()` (unchanged, already tested) skips any id
 * already in that list -- so a completion FlowSavvy still reports as
 * "completed" (well within its own completed-tasks list) on a SECOND tick
 * does NOT get a second next-occurrence task created. This is the actual
 * guarantee the task brief asked for, and it holds regardless of whether
 * `modifiedAfter` narrows what gets fetched at all (that's purely a fetch-
 * size optimization -- correctness comes entirely from the by-id check).
 *
 * **Known gap, mirrored faithfully from the Python source, not introduced by
 * this port**: [result.processed]/`lastRunEpochMillis` are only persisted
 * ONCE, after every create in this run's `result.creates` has been
 * attempted (same order of operations as `run_chore`: the `for c in
 * out["creates"]:` loop runs to completion BEFORE `st["processed"] =
 * out["processed"]` is ever assigned). If [creator]'s `create` call throws
 * partway through a multi-create batch (e.g. a real, non-429 FlowSavvy HTTP
 * error on the second of three), NONE of this run's newly-processed ids get
 * persisted -- not even the ones whose `createTask` call already succeeded
 * moments earlier -- so a retried next tick can call `createTask` AGAIN for
 * those already-created occurrences, producing a duplicate FlowSavvy task.
 * This is a real, live risk, but it is the Python source's own behavior
 * (same all-or-nothing ordering), not a regression this port introduces; a
 * per-create incremental persist would close it, but `plan()`'s output
 * shape (`ChoreCreate` carries no originating chore id to key an incremental
 * write off of) would need to change to do that safely, which is out of
 * scope for reusing the already-tested `plan()` verbatim. Flagged here per
 * this task's own "document rather than silently pick" instruction, not
 * silently fixed or silently ignored.
 */

/** One completed FlowSavvy task's raw fields this cycle needs -- mirrors
 * `run_chore`'s own `completed.append({...})` dict shape exactly (same
 * field set `CompletedChore` in `ChoreSchedule.kt` already expects). */
internal data class RawCompletedChoreTask(
    val id: String?,
    val title: String?,
    val notes: String?,
    val completedDate: String?,
    val durationMinutes: Int?,
    val minLengthMinutes: Int?,
    val listId: String?,
    val priority: String?,
    val schedulingHoursId: String?,
    val dueTime: String?,
)

/** Testability seam, same shape as [FlowSavvyFetch]: a real implementation
 * ([RealChoreCompletedFetch]) wraps [FlowSavvyClient.listItems]; tests
 * supply a fake returning fixture data with no network dependency. */
internal fun interface ChoreCompletedFetch {
    fun fetch(now: LocalDateTime, modifiedAfterIso: String): List<RawCompletedChoreTask>
}

/** Testability seam for the WRITE side: a real implementation
 * ([RealChoreTaskCreator]) wraps [FlowSavvyClient.createTask] (going through
 * that client's existing retry-safe path -- see this file's top-level kdoc);
 * tests supply a fake that just records calls, so the dedup logic below is
 * verifiable without a real network call. */
internal fun interface ChoreTaskCreator {
    fun create(body: Map<String, Any?>)
}

/** has()-and-not-null()-safe String read -- same hazard/guard
 * `LifeOpsComputeWorker.kt`'s own `optStringOrNull` documents (FlowSavvy
 * leaves fields explicitly `null` for plenty of real items, and `optString`
 * on an explicit JSON `null` returns the literal string `"null"`, not Kotlin
 * `null`). Duplicated here rather than shared because Kotlin top-level
 * `private` declarations are file-private, not package-private. */
private fun JSONObject.optStringOrNull(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

private fun JSONObject.optIntOrNull(key: String): Int? =
    if (has(key) && !isNull(key)) optInt(key) else null

/** The literal substring `run_chore` pre-filters on before ever calling
 * `chore_engine.plan()` (`if "[cycle:" not in (t.get("notes") or ""):
 * continue`) -- a cheap skip for the common case of a completed task with no
 * chore tag at all. The full `\[cycle:(\d+)d\]` regex validation still
 * happens inside `plan()` itself (`ChoreSchedule.kt`'s `CYCLE_TAG`),
 * unchanged -- this is only the same pre-filter optimization, not a
 * duplicate/looser validation. */
private const val CYCLE_TAG_MARKER = "[cycle:"

/** Ports the completed-task field extraction `run_chore`'s own
 * `completed.append({...})` performs, field for field:
 * - `completed_date`: `(t.get("lastModified") or now.isoformat())[:10]`.
 * - `dueTime`: `(t.get("dueDateTime") or "")[11:16] or "20:00"`.
 */
internal fun parseCompletedChoreTask(o: JSONObject, now: LocalDateTime): RawCompletedChoreTask {
    val dueDateTime = o.optStringOrNull("dueDateTime")
    val lastModified = o.optStringOrNull("lastModified")
    val completedDate = (lastModified ?: now.toLocalDate().toString()).take(10)
    val dueTime = dueDateTime?.takeIf { it.length >= 16 }?.substring(11, 16) ?: "20:00"
    return RawCompletedChoreTask(
        id = o.optStringOrNull("id"),
        title = o.optStringOrNull("title"),
        notes = o.optStringOrNull("notes"),
        completedDate = completedDate,
        durationMinutes = o.optIntOrNull("durationMinutes"),
        minLengthMinutes = o.optIntOrNull("minLengthMinutes"),
        listId = o.optStringOrNull("listId"),
        priority = o.optStringOrNull("priority") ?: "low",
        schedulingHoursId = o.optStringOrNull("schedulingHoursId"),
        dueTime = dueTime,
    )
}

/** Real network-backed [ChoreCompletedFetch]. Mirrors `run_chore`'s own
 * `fs.list_items(itemType="task", completed=True, modifiedAfter=...)` fetch
 * plus its `"[cycle:" not in notes` pre-filter exactly. */
internal class RealChoreCompletedFetch(private val client: FlowSavvyClient) : ChoreCompletedFetch {
    override fun fetch(now: LocalDateTime, modifiedAfterIso: String): List<RawCompletedChoreTask> {
        val arr = client.listItems(
            mapOf("itemType" to "task", "completed" to true, "modifiedAfter" to modifiedAfterIso),
        ).optJSONArray("items") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val notes = o.optStringOrNull("notes")
            if (notes == null || CYCLE_TAG_MARKER !in notes) return@mapNotNull null
            parseCompletedChoreTask(o, now)
        }
    }
}

/** Real network-backed [ChoreTaskCreator]. Ports `_logged_create`'s
 * `fs.create_task(**kwargs)` call -- see this file's top-level kdoc for what
 * `_logged_create`'s other two side effects were and why they aren't ported
 * here. `isAutoIgnored=False` is carried over from `run_chore`'s own
 * `_logged_create` call (an extra kwarg `chore_engine.plan()`'s output
 * itself doesn't produce, added at this exact call site in the Python
 * source too). */
internal class RealChoreTaskCreator(private val client: FlowSavvyClient) : ChoreTaskCreator {
    override fun create(body: Map<String, Any?>) {
        client.createTask(body)
    }
}

/** `Instant` (epoch millis) -> `_utc_iso()`'s exact string shape
 * (`"%Y-%m-%dT%H:%M:%SZ"`, whole seconds, UTC) -- the `modifiedAfter` cursor
 * value `run_chore` persists and re-sends every cycle. */
internal fun utcIsoSeconds(epochMillis: Long): String {
    val zdt = Instant.ofEpochMilli(epochMillis).atZone(ZoneOffset.UTC)
    return "%04d-%02d-%02dT%02d:%02d:%02dZ".format(zdt.year, zdt.monthValue, zdt.dayOfMonth, zdt.hour, zdt.minute, zdt.second)
}

private fun encodeProcessedIds(ids: List<String>): String = JSONArray(ids).toString()

private fun decodeProcessedIds(json: String): List<String> =
    try {
        val arr = JSONArray(json)
        (0 until arr.length()).map { arr.getString(it) }
    } catch (e: JSONException) {
        emptyList()
    }

/**
 * The actual on-device chore-cycle tick: read the persisted dedup state,
 * fetch recently-completed `[cycle:Nd]`-tagged FlowSavvy tasks, run
 * `ChoreSchedule.kt`'s `plan()` against them, create every resulting next
 * occurrence through [creator], and persist the updated dedup state --
 * ports `run_chore` end to end. Returns the number of next-occurrence tasks
 * created this run (0 is the overwhelmingly common case: most ticks see no
 * newly-completed managed chore at all).
 *
 * No Context/WorkManager/Glance dependency -- callers ([LifeOpsComputeWorker.doWork])
 * wire real [ChoreCompletedFetch]/[ChoreTaskCreator] implementations backed
 * by a real [FlowSavvyClient]; tests exercise this directly against an
 * in-memory Room database and fake fetch/creator seams, same pattern
 * [runComputeTick] already establishes.
 */
internal suspend fun runChoreCycle(
    db: LifeOpsDatabase,
    fetch: ChoreCompletedFetch,
    creator: ChoreTaskCreator,
    now: LocalDateTime = LocalDateTime.now(),
): Int {
    val state = db.choreCycleStateDao().get()
    val processed = state?.processedIdsJoined?.let(::decodeProcessedIds) ?: emptyList()
    val modifiedAfterIso = state?.lastRunEpochMillis?.let(::utcIsoSeconds) ?: "1970-01-01T00:00:00Z"

    val completedRaw = fetch.fetch(now, modifiedAfterIso)
    val completed = completedRaw.map {
        CompletedChore(
            id = it.id,
            title = it.title,
            notes = it.notes,
            completedDate = it.completedDate,
            durationMinutes = it.durationMinutes,
            minLengthMinutes = it.minLengthMinutes,
            listId = it.listId,
            priority = it.priority,
            schedulingHoursId = it.schedulingHoursId,
            dueTime = it.dueTime,
        )
    }

    val result = plan(ChorePlanInput(completed = completed, processed = processed))

    // Batch commit -- see this file's top-level kdoc for the exact ordering
    // this mirrors from `run_chore` (and the known partial-failure gap that
    // ordering carries, unchanged from the Python source).
    for (c in result.creates) {
        creator.create(
            mapOf(
                "title" to c.title,
                "listId" to c.listId,
                "durationMinutes" to c.durationMinutes,
                "minLengthMinutes" to c.minLengthMinutes,
                "priority" to c.priority,
                "schedulingHoursId" to c.schedulingHoursId,
                "notes" to c.notes,
                "dueDateTime" to c.dueDateTime,
                "canBeStartedAt" to c.canBeStartedAt,
                "isAutoIgnored" to false,
            ),
        )
    }

    db.choreCycleStateDao().upsert(
        ChoreCycleStateEntity(
            processedIdsJoined = encodeProcessedIds(result.processed),
            lastRunEpochMillis = now.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
        ),
    )

    return result.creates.size
}
