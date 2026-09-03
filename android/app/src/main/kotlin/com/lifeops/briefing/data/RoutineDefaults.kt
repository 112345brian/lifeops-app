package com.lifeops.briefing.data

/**
 * The four routines the old Python backend (`lifeops/routine_store.py`'s
 * `_DEFAULTS`) has always run on, ported byte-for-byte from that module's
 * comment:
 * ```
 * gym:     {"target": 4, "floor": 3, "max_consecutive": 2}
 * partner/friends: times=1, per_days=7, anchor=since_last
 * meal:    runner.py's old _MEAL_ROUTINE, times=1, per_days=6, since_last
 * ```
 * `routine_store.py` itself never persisted these anywhere -- its own
 * docstring says "nothing calls save_routine() yet" -- so there is no old
 * `routines.json`/SQLite row to migrate. This is the closest thing to that
 * migration: seeding the on-device [RoutineEntity] table with the same
 * hardcoded values the Python side has always silently computed with, so
 * the "Recurring" tab isn't empty on a fresh install and [RoutineDao]
 * callers (`GymPlanCycle`, `computeSocialNudges`) have a real persisted row
 * to read instead of relying on their own separate inline fallback.
 */
object RoutineDefaults {
    val ALL: List<RoutineEntity> = listOf(
        RoutineEntity(
            id = "gym", title = "Gym",
            timesPerWindow = 4, perDays = 7, anchor = RoutineEntity.ANCHOR_WINDOW,
            onDue = RoutineEntity.ON_DUE_NOTIFY,
            constraintsJson = """{"floor":3,"max_consecutive":2}""",
        ),
        RoutineEntity(
            id = "partner", title = "Partner",
            timesPerWindow = 1, perDays = 7, anchor = RoutineEntity.ANCHOR_SINCE_LAST,
            onDue = RoutineEntity.ON_DUE_NOTIFY,
        ),
        RoutineEntity(
            id = "friends", title = "Friends",
            timesPerWindow = 1, perDays = 7, anchor = RoutineEntity.ANCHOR_SINCE_LAST,
            onDue = RoutineEntity.ON_DUE_NOTIFY,
        ),
        RoutineEntity(
            id = "meal", title = "Meal prep",
            timesPerWindow = 1, perDays = 6, anchor = RoutineEntity.ANCHOR_SINCE_LAST,
            onDue = RoutineEntity.ON_DUE_NOTIFY,
        ),
    )
}
