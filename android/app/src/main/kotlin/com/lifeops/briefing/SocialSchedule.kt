package com.lifeops.briefing

/**
 * Re-derivation pass for social, per the same open question
 * docs/lifeops_capability_todo.md left after gym's migration ("whether
 * chore/social get re-derived through the same windows+LifeScript model
 * next ... both are thin enough that they plausibly should, per the same
 * reasoning that dissolved gym"). See `ChoreSchedule.kt`'s top-level kdoc
 * for the parallel finding on chore; this file's finding is the same shape.
 *
 * This file REPLACES the former `SocialEngine.kt` (deleted).
 *
 * ## Why gym decomposed and social doesn't need to
 *
 * Gym's bespoke code decomposed into [TimeSlot]/[LifeScript] because it had
 * a genuine per-day decision problem: given several CANDIDATE DAYS, which
 * ones have a clock-time slot available under a boolean flag combination,
 * and which subset of those should actually be booked under a
 * consecutive-day cap. Social has neither half of that problem. It asks
 * exactly one question per routine ("is this SINCE_LAST routine due, given
 * how many days it's been") and that question is ALREADY answered by
 * [statusSinceLast] -- the same shared primitive `ChoreSchedule.kt` calls
 * for its own due-date math, and the one gym's own `plan()` deliberately
 * does NOT need (gym's due concept is inseparable from slot-picking, per
 * `GymSchedule.kt`'s kdoc). There are no candidate days to filter, no slot
 * to pick, and nothing for [scheduleRoutine] to do here at all -- social's
 * "engine" was already fully reduced to a direct call into `Routine.kt`
 * before this pass, not a bespoke reimplementation of due-checking that
 * needed unwinding.
 *
 * ## What's left, and why it's genuinely bespoke rather than unmigrated
 *
 * Two small pieces remain, neither a LifeScript concept:
 * - **The `null`-days guard before calling [statusSinceLast]**: Python's
 *   `status_since_last`/its Kotlin port both treat `daysSinceLast == null`
 *   as "never done, so due" -- correct for a fresh routine with no history,
 *   wrong for social's specific "we don't know yet" case (unknown last-seen
 *   date must never itself trigger a nudge). This is a call-site policy
 *   choice about how to use the shared primitive, not new due-check math,
 *   so it stays exactly where it always was: a plain `if` guarding the call.
 * - **Nudge text construction**: LifeScript evaluates to one boolean or
 *   number per call -- it has no string-templating facility and isn't
 *   meant to gain one (see `LifeScript.kt`'s own scope note: it only ever
 *   *reads* named variables to produce a value, never renders output). Once
 *   [statusSinceLast] says a routine is due, building its user-facing nudge
 *   sentence is plain Kotlin, the same way `GymSchedule.kt`'s `plan()`
 *   builds its own alert text after `scheduleRoutine` returns a result.
 *
 * Both are the same shape as gym's own retained bespoke remainder (alert
 * wording layered on top of generic scheduling output) -- output
 * presentation, not recurrence logic.
 */

/** Mirrors the Python `plan()`'s dict return shape: just `nudges` -- a
 * FlowSavvy-placeholder-task-creation feature that used to live alongside
 * it was dropped 2026-07-29 (see `SocialEngineTest.kt`'s former
 * `planNoLongerCreatesPlaceholderTasks` test, reproduced unchanged below). */
data class SocialPlan(val nudges: List<String>)

/**
 * Ports `social_engine.plan()`: for each of partner/friend, if its
 * days-since-last is known AND [statusSinceLast] (the shared SINCE_LAST due
 * primitive `Routine.kt` already provides -- see this file's top-level kdoc
 * for why nothing else is needed here) says the routine is due, emit its
 * nudge sentence. `partnerDays`/`friendDays` being `null` ("unknown") is
 * guarded before the call rather than inside it, since [statusSinceLast]
 * treats a `null` days-since-last as "due" by default -- correct for a fresh
 * routine, wrong for social's "we don't actually know" case.
 */
fun plan(
    partnerDays: Int?,
    friendDays: Int?,
    partnerRoutine: Routine,
    friendsRoutine: Routine,
    partnerName: String = "Partner",
): SocialPlan {
    val nudges = mutableListOf<String>()
    if (partnerDays != null && statusSinceLast(partnerRoutine, partnerDays).due) {
        nudges.add(
            "It's been $partnerDays days since $partnerName — put a slot in for you two."
        )
    }
    if (friendDays != null && statusSinceLast(friendsRoutine, friendDays).due) {
        nudges.add("$friendDays days since you saw a friend — reach out.")
    }
    return SocialPlan(nudges)
}
