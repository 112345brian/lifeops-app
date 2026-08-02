package com.lifeops.briefing.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room persistence for one blocked calendar day -- the on-device counterpart
 * of `lifeops/web.py`'s `_block_day`/`_sched_blocks()`/`_gym_blocks()` JSON
 * lists (see that module: block-day writes an entry to BOTH `sched_blocks`
 * (the "all domains" block) and `gym_blocks` (gym's own narrower block list)
 * plus, if `config.BLOCK_CAL` is configured, a real FlowSavvy busy calendar
 * event). This entity intentionally only ports the "day is blocked" FACT --
 * one flat, domain-agnostic table, matching `_sched_blocks()`'s own broader
 * "all domains" list rather than gym's narrower one, since the Android
 * block-day quick action (docs/lifeops_capability_todo.md's "Full App Home"
 * section: "block today/tomorrow") is the "all domains" action, not gym's
 * own separate `/gym/block-date`. It deliberately does NOT port:
 * - The FlowSavvy busy-calendar-event creation/deletion (`config.BLOCK_CAL`)
 *   -- a genuinely separate write-capable feature (creating/deleting a real
 *   remote calendar event) than "record that this day is blocked," the same
 *   "bigger, riskier write path, out of scope here" reasoning
 *   `LifeOpsComputeWorker.kt`'s own kdoc gives for not wiring chore-task-
 *   creation or YNAB writes yet.
 * - `event_id` tracking for that FlowSavvy event, for the same reason.
 *
 * [date] is the ISO-8601 calendar date string ("2026-08-02") and the primary
 * key -- one row per blocked day, matching `_sched_blocks()`'s own
 * one-entry-per-date list (re-blocking an already-blocked date is a no-op on
 * the Python side; [BlockedDayDao.upsert] gives the same idempotent
 * behavior here). [reason]/[source] are both nullable free-text fields --
 * [source] mirrors this app's existing "ui" vs. background-tick provenance
 * convention (see e.g. `history.py`'s own `source="ui"` calls), [reason] is
 * an optional free-text note a future UI could let the user attach.
 */
@Entity(tableName = "blocked_days")
data class BlockedDayEntity(
    @PrimaryKey val date: String,
    val reason: String? = null,
    val source: String? = null,
)
