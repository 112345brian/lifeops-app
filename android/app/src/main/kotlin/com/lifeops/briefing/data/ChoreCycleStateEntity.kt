package com.lifeops.briefing.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room persistence for chore-cycle dedup bookkeeping -- the on-device
 * counterpart of `lifeops/runner.py`'s `run_chore`'s `chore_state.json`
 * (`{"processed": [...], "lastRunUtc": "..."}`), which is what
 * `chore_engine.py`'s `plan()` needs to avoid re-creating a chore's next
 * occurrence twice if the same FlowSavvy completion is seen again before its
 * "processed" marker lands (see `ChoreSchedule.kt`'s already-ported `plan()`,
 * which does the actual capped-dedup-list math; this entity is only the
 * durable home for that list plus the "recently completed" fetch cursor).
 *
 * Deliberately ONE row (singleton, [SINGLETON_ID]), not one row per
 * processed id: `chore_engine.plan()`'s `processed` list is a single opaque,
 * ordered, capped-at-300 blob that gets read whole and written back whole
 * every run (`st["processed"] = out["processed"]` in the Python source) --
 * there is no per-id query this table ever needs to serve (unlike
 * [HistoryEventEntity], which real gym/social cadence queries filter and
 * range-scan by domain/timestamp). A one-row-per-id table would need extra
 * insertion-order/trim bookkeeping to reproduce the same "last 300, oldest
 * dropped first" semantics `ChoreSchedule.kt`'s `plan()` already implements
 * in Kotlin for free -- pure overhead for a value this table only ever reads
 * and writes atomically as a whole.
 *
 * [processedIdsJoined] is [result.processed][com.lifeops.briefing.ChorePlanResult.processed]
 * serialized as a JSON array of strings (`org.json.JSONArray(ids).toString()`)
 * -- plain TEXT, not a real relational list, for the same "opaque blob,
 * never queried by id" reason above.
 *
 * [lastRunEpochMillis] mirrors `chore_state.json`'s `lastRunUtc`: the
 * timestamp `run_chore`'s next invocation passes as FlowSavvy's
 * `modifiedAfter` filter, so only tasks completed since the last successful
 * cycle are ever fetched/considered (`fs.list_items(... modifiedAfter=st["lastRunUtc"])`
 * in the Python source) -- an efficiency filter, NOT the dedup mechanism
 * itself (that's [processedIdsJoined]/`plan()`'s own by-id check), same
 * distinction the Python source draws between the two fields.
 */
@Entity(tableName = "chore_cycle_state")
data class ChoreCycleStateEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val processedIdsJoined: String,
    val lastRunEpochMillis: Long,
) {
    companion object {
        const val SINGLETON_ID = 0
    }
}
