package com.lifeops.briefing

/**
 * Kotlin port of `lifeops/engines/social_engine.py` -- nudges when partner/
 * friend time is overdue, per the on-device migration sequence (see
 * docs/lifeops_capability_todo.md's "On-Device Migration" section). Pure
 * logic, no names baked in beyond the caller-supplied [partnerName] -- the
 * runner maps the configured partner name into the nudge text, matching the
 * Python module's own "Pure logic" contract.
 *
 * The due-check itself goes through this package's already-ported
 * `Routine`/[statusSinceLast] shared primitive (see `Routine.kt`), not a
 * reinvented threshold comparison -- this port depends on that merged file
 * (same package, direct reference, no import needed) rather than
 * duplicating its due-check math, mirroring commit 2a319e4's Python-side
 * "Consolidate recurring-item due-checks behind a shared Routine primitive."
 *
 * NOT wired into any widget/worker/persistence path yet -- like `Routine.kt`
 * before it, this is a standalone, unused-so-far port.
 *
 * Translation notes:
 * - Python's `plan()` returns a `dict` with a single `"nudges"` key (a
 *   FlowSavvy-placeholder-task-creation feature that used to live alongside
 *   it was dropped 2026-07-29 -- see the Python docstring). [SocialPlan]
 *   mirrors that dict's *actual* field usage: just `nudges`. There is no
 *   Kotlin equivalent of the Python test's `assert "creates" not in out`
 *   check (`test_plan_no_longer_creates_placeholder_tasks`) -- a
 *   [SocialPlan] data class simply has no such field to begin with, which
 *   is a strictly stronger, compile-time version of the same guarantee than
 *   the Python dict-key runtime check. See `SocialEngineTest` for how that
 *   case is still ported (as a doc-comment-backed assertion on the type
 *   itself, not a runtime string check).
 * - `partner_days`/`friend_days` are `Optional[int]` in Python (`None`
 *   means "unknown," deliberately guarded *before* calling into
 *   `status_since_last`, since that primitive treats `None` as "never
 *   done, so due" -- the right default for a fresh routine, but wrong for
 *   social's "we don't know" case). Ported as nullable `Int?` params with
 *   the same null-guard-first order.
 * - Nudge text is built with Kotlin string templates in place of Python
 *   f-strings; wording matches verbatim.
 */
data class SocialPlan(val nudges: List<String>)

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
