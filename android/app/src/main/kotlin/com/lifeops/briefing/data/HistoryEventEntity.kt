package com.lifeops.briefing.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.Instant
import java.time.ZoneId

/**
 * Room persistence for one completion-history record -- the on-device
 * counterpart of `lifeops/history.py`'s append-only `history_events` SQLite
 * table (see that module's docstring: "the durable record of WHEN things
 * happened... every engine reads cadence... from here"). Every
 * gym/chore/social adherence computation depends on this log, so its shape
 * here matches what `history.py` actually stores per row (`action`, `ts`,
 * `source`, `meta`) rather than inventing a new one.
 *
 * [domain] mirrors `history.py`'s `action` column -- deliberately named
 * `domain` here (not `routineId`) because it is NOT always a strict foreign
 * key into [RoutineEntity.id]: `history.py`'s callers pass free-form keys
 * ("gym", "partner", "friends", or a chore's own FlowSavvy task id via
 * `ChoreSchedule.kt`'s `[cycle:Nd]` note-tag mechanism, which has no
 * corresponding [RoutineEntity] row at all -- see `ChoreSchedule.kt`'s kdoc:
 * "there's no separate 'chore routine record' to persist"). Modeling this
 * as a Room `@ForeignKey` would be wrong for that case, so it's a plain
 * indexed string column instead, matching `history.py`'s own untyped
 * `action` column exactly.
 *
 * [timestampEpochMillis], not a raw ISO string: an unambiguous, directly
 * sortable/queryable instant is the right shape for a Room column (SQLite
 * `ORDER BY`/range queries on an INTEGER column, no string-comparison
 * pitfalls -- see `Routine.kt`'s own kdoc on why `status()` compares
 * *parsed* datetimes rather than raw strings for exactly this reason).
 * [com.lifeops.briefing.status]/[com.lifeops.briefing.statusSinceLast] (see
 * `Routine.kt`) take `List<String>` ISO-8601 LOCAL date-time strings
 * instead (matching `history.py`'s own `ts` column, a naive
 * `datetime.now().isoformat()` string with no timezone) -- [toIsoLocalDateTime]
 * below is the one-line bridge from this column's epoch-millis shape into
 * that exact input shape, centralized here (and in
 * [HistoryEventDao.getTimestampsIsoForDomain]) rather than reimplemented at
 * every call site.
 *
 * [eventType] ("completed"/"skipped") is new relative to `history.py`,
 * which only ever logs completions today (no explicit skip concept) --
 * added because a future data-gather layer distinguishing "explicitly
 * skipped" from "no signal yet" needs a place to record that, and a
 * fixed-vocabulary string column is cheaper than a schema migration later.
 * [metadataJson] mirrors `history.py`'s own `meta` column (`json.dumps(meta)
 * if meta else None` -- already a nullable JSON-text blob on the Python
 * side, e.g. gym's slot/kind info), ported as the same shape rather than a
 * relational structure for the same "open-ended per-domain dict" reason
 * [RoutineEntity.constraintsJson] documents.
 */
@Entity(tableName = "history_events")
data class HistoryEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val domain: String,
    val timestampEpochMillis: Long,
    /** One of "completed" | "skipped". */
    val eventType: String,
    val metadataJson: String? = null,
) {
    companion object {
        const val EVENT_COMPLETED = "completed"
        const val EVENT_SKIPPED = "skipped"
    }
}

/** Bridges [HistoryEventEntity.timestampEpochMillis] into the naive
 * ISO-8601 local-date-time string shape [com.lifeops.briefing.status]
 * parses via `LocalDateTime.parse(...)` -- see this class's kdoc. Uses the
 * system default zone, matching `history.py`'s own `datetime.now()` (also
 * naive/local, not UTC). */
fun HistoryEventEntity.toIsoLocalDateTime(): String =
    Instant.ofEpochMilli(timestampEpochMillis).atZone(ZoneId.systemDefault()).toLocalDateTime().toString()
