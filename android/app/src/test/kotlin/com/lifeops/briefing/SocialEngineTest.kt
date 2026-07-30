package com.lifeops.briefing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Kotlin port of `tests/test_social_engine.py` -- see `SocialEngine.kt`'s
 * kdoc for why this exists (a sibling of the `Routine.kt` on-device
 * migration port). One test per case from the Python suite, each annotated
 * with which Python test it corresponds to. No Robolectric needed:
 * `SocialEngine.kt`, like `Routine.kt`, has zero Android-framework
 * dependency, so plain JUnit is enough. */
class SocialEngineTest {

    private val partner = Routine(id = "partner", times = 1, perDays = 7, anchor = Anchor.SINCE_LAST)
    private val friends = Routine(id = "friends", times = 1, perDays = 7, anchor = Anchor.SINCE_LAST)

    // Corresponds to test_overdue_partner_nudge
    @Test
    fun overduePartnerNudge() {
        val out = plan(8, 3, partner, friends, partnerName = "Reina")

        assertTrue(out.nudges.any { it.contains("Reina") })
    }

    // Corresponds to test_overdue_friend_nudge
    @Test
    fun overdueFriendNudge() {
        val out = plan(3, 10, partner, friends)

        assertTrue(out.nudges.any { it.lowercase().contains("friend") })
    }

    // Corresponds to test_no_nudge_when_recent
    @Test
    fun noNudgeWhenRecent() {
        val out = plan(2, 2, partner, friends)

        assertEquals(emptyList<String>(), out.nudges)
    }

    // Corresponds to test_no_nudge_when_days_unknown
    @Test
    fun noNudgeWhenDaysUnknown() {
        val out = plan(null, null, partner, friends)

        assertEquals(emptyList<String>(), out.nudges)
    }

    // Corresponds to test_exactly_seven_days_is_due_for_both
    @Test
    fun exactlySevenDaysIsDueForBoth() {
        val out = plan(7, 7, partner, friends)

        assertEquals(2, out.nudges.size)
    }

    // Corresponds to test_plan_no_longer_creates_placeholder_tasks. Locked
    // down 2026-07-29 on the Python side: a hangout requires another
    // person's agreement, unlike a solo commitment (gym), so this engine
    // must never fabricate a "X (proposed)"/"Plan X" FlowSavvy task that
    // reads as an already-arranged plan when nothing has actually been
    // arranged. The Python test asserts `"creates" not in out` on the
    // returned dict; [SocialPlan] has no such field to begin with, so the
    // equivalent Kotlin guarantee is compile-time rather than a runtime
    // assertion -- this test still exists to document that intent and to
    // pin down that `plan()`'s only observable output is the nudge list.
    @Test
    fun planNoLongerCreatesPlaceholderTasks() {
        val out = plan(30, 30, partner, friends, partnerName = "Reina")

        assertEquals(2, out.nudges.size)
    }

    // Corresponds to test_custom_routine_threshold_is_honored
    @Test
    fun customRoutineThresholdIsHonored() {
        val shortFuse = Routine(id = "partner", times = 1, perDays = 3, anchor = Anchor.SINCE_LAST)
        val out = plan(4, 100, shortFuse, friends)

        assertTrue(out.nudges.any { it.contains("4 days") })
    }
}
