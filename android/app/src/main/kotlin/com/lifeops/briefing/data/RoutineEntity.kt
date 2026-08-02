package com.lifeops.briefing.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.lifeops.briefing.Anchor
import com.lifeops.briefing.Routine

/**
 * Room persistence for a user-editable "Routine" record -- the on-device
 * counterpart of `lifeops/routine_store.py`'s `load_routine`/`save_routine`
 * (backed today by a flat `routines.json` override file merged over
 * hardcoded defaults; see that module's docstring). This entity is the
 * durable, queryable replacement that a future settings UI/data-gather
 * layer will read and write instead of a JSON blob.
 *
 * Field-for-field mapping to the pure Kotlin [com.lifeops.briefing.Routine]
 * (see `Routine.kt`) plus the two extra fields the Python side already
 * tracks outside the core dataclass:
 * - [id]/[timesPerWindow]/[perDays]/[anchor] mirror `Routine.id`/`.times`/
 *   `.perDays`/`.anchor` exactly ([timesPerWindow] is named for the
 *   anchor=WINDOW reading of the field -- see [com.lifeops.briefing.Anchor]'s
 *   own kdoc on why "window" and "since_last" are NOT the same shape --
 *   but the same int also IS `times` for a since_last routine, i.e.
 *   "how many completions per per_days-day period," so it isn't
 *   window-only despite the name suggesting that reading first).
 * - [title] is new: `routine_store.py`'s ids ("gym"/"partner"/"friends"/
 *   "meal") are also used as their own display label today (no separate
 *   title field exists yet), but a settings UI needs an editable label
 *   distinct from the stable id a foreign key (like
 *   [HistoryEventEntity.routineId]) actually points at.
 * - [onDue] captures `routine_store.py`'s currently-implicit "what happens
 *   when this fires" -- today that's hardcoded per call site (gym pushes a
 *   ring/notification, chore creates a FlowSavvy task via `ChoreEngine`,
 *   social emits a nudge string via `SocialEngine`); persisting it
 *   explicitly is prep for a generic on_due dispatcher, not a port of an
 *   existing persisted field.
 * - [constraintsJson] is a raw JSON text blob for `routine_store.py`'s
 *   loose "extra" dict (gym's `floor`/`max_consecutive`, any future
 *   per-routine knob) plus `Routine.kt`'s own [TimeSlot]-based
 *   LifeScript scheduling constraints (`condition` strings, see
 *   `LifeScript.kt`). Deliberately NOT modeled as relational columns --
 *   `routine_store.py`'s own docstring calls this shape "whatever
 *   persisted/default fields aren't part of the core Routine shape," an
 *   open-ended per-routine dict by design, so a JSON text column matches
 *   that shape instead of guessing at a fixed relational schema for
 *   fields the Python side doesn't fix either. Nullable: a routine with no
 *   extra fields (e.g. a fresh chore-derived routine) has nothing to store
 *   here.
 *
 * Anchor/onDue are stored as plain strings, not enums directly, so that an
 * unrecognized/future value read from the database round-trips through
 * Room without a hard crash -- the same "no ValueError-equivalent branch"
 * posture `Routine.kt`'s own kdoc describes choosing an enum FOR at the
 * pure-logic layer; the persistence layer is deliberately looser than the
 * pure-logic layer sitting on top of it. See [toAnchor] for the mapping
 * into [com.lifeops.briefing.Anchor] a caller performs at the boundary.
 */
@Entity(tableName = "routines")
data class RoutineEntity(
    @PrimaryKey val id: String,
    val title: String,
    val timesPerWindow: Int,
    val perDays: Int,
    /** One of "window" | "since_last" -- matches
     * `lifeops/routine_store.py`'s `_VALID_ANCHORS` exactly (NOT
     * "since_last_completion"; confirmed against the actual Python source
     * rather than guessed). */
    val anchor: String,
    /** One of "notify" | "schedule_task" (or any future action kind); see
     * this class's kdoc for why this isn't ported from an existing
     * persisted Python field. */
    val onDue: String,
    val constraintsJson: String? = null,
) {
    companion object {
        const val ANCHOR_WINDOW = "window"
        const val ANCHOR_SINCE_LAST = "since_last"

        const val ON_DUE_NOTIFY = "notify"
        const val ON_DUE_SCHEDULE_TASK = "schedule_task"
    }
}

/** Maps [RoutineEntity.anchor]'s raw string into the pure-logic
 * [com.lifeops.briefing.Anchor] enum [Routine] itself requires, throwing
 * for anything else -- the same fail-loud posture
 * `routine_store.py`'s own `_validate` uses for an unrecognized anchor,
 * performed at the persistence/pure-logic boundary rather than inside the
 * entity itself. */
fun RoutineEntity.toAnchor(): Anchor = when (anchor) {
    RoutineEntity.ANCHOR_WINDOW -> Anchor.WINDOW
    RoutineEntity.ANCHOR_SINCE_LAST -> Anchor.SINCE_LAST
    else -> throw IllegalArgumentException("Unknown anchor '$anchor' for routine '$id'")
}

/** Maps this entity into the pure [Routine] value [com.lifeops.briefing.status]/
 * [com.lifeops.briefing.scheduleRoutine] actually consume -- [RoutineEntity]
 * carries persistence-only fields ([title], [onDue], [constraintsJson])
 * that [Routine] has no use for. */
fun RoutineEntity.toRoutine(): Routine = Routine(
    id = id,
    times = timesPerWindow,
    perDays = perDays,
    anchor = toAnchor(),
)
