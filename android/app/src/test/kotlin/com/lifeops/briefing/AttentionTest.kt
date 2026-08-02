package com.lifeops.briefing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reproduces `tests/test_attention.py`'s 7 test cases 1:1 against
 * `Attention.kt`'s [compute] -- same scenarios, same assertions, ported to
 * this file's [AttentionFacts]/[SystemHealth]/[Domain]/[Severity] shapes.
 *
 * No Robolectric needed: `Attention.kt` has zero Android-framework
 * dependency (pure data classes + enums), same as `RoutineTest.kt`.
 */
class AttentionTest {

    // test_ok_has_a_clear_next_move
    @Test
    fun okHasAClearNextMove() {
        val result = compute(
            AttentionFacts(gymLast7d = 4, gymTarget = 4, discretionaryDollars = 200.0),
        )

        assertEquals(Severity.OK, result.state)
        assertEquals("OK", result.label)
        assertEquals(emptyList<AttentionReason>(), result.reasons)
    }

    // test_watch_collects_due_money_and_gym_reasons
    @Test
    fun watchCollectsDueMoneyAndGymReasons() {
        val result = compute(
            AttentionFacts(
                dueToday = listOf("Submit paper"),
                discretionaryDollars = 50.0,
                gymLast7d = 2,
                gymTarget = 4,
            ),
        )

        assertEquals(Severity.WATCH, result.state)
        assertEquals(setOf(Domain.COURSEWORK, Domain.MONEY, Domain.GYM), result.reasons.map { it.domain }.toSet())
    }

    // test_recent_overdue_is_risk_and_beats_watch
    @Test
    fun recentOverdueIsRiskAndBeatsWatch() {
        val result = compute(
            AttentionFacts(
                overdue = listOf(OverdueItem(title = "Reading", dueInHours = -3.0)),
                dueToday = listOf("Quiz"),
            ),
        )

        assertEquals(Severity.RISK, result.state)
        assertEquals("Overdue: Reading", result.reasons[0].title)
    }

    // test_day_overdue_or_system_errors_is_fucked
    @Test
    fun dayOverdueOrSystemErrorsIsFucked() {
        val overdue = compute(
            AttentionFacts(overdue = listOf(OverdueItem(title = "Paper", dueInHours = -25.0))),
        )
        val broken = compute(
            AttentionFacts(),
            SystemHealth(errors = mapOf("canvas" to "expired"), ageMins = 2.0),
        )

        assertEquals(Severity.FUCKED, overdue.state)
        assertEquals(Severity.FUCKED, broken.state)
        assertEquals(Domain.SYSTEM, broken.reasons[0].domain)
    }

    // test_stale_system_state_is_deterministic
    @Test
    fun staleSystemStateIsDeterministic() {
        assertEquals(Severity.WATCH, compute(AttentionFacts(), SystemHealth(ageMins = 31.0)).state)
        assertEquals(Severity.RISK, compute(AttentionFacts(), SystemHealth(ageMins = 121.0)).state)
    }

    // test_deadline_wins_cross_domain_tie_break
    @Test
    fun deadlineWinsCrossDomainTieBreak() {
        val result = compute(
            AttentionFacts(overdue = listOf(OverdueItem(title = "Paper", dueInHours = -25.0))),
            SystemHealth(errors = mapOf("canvas" to "expired"), ageMins = 2.0),
        )

        assertEquals(listOf(Domain.COURSEWORK, Domain.SYSTEM), result.reasons.take(2).map { it.domain })
    }

    // test_coursework_flood_does_not_crowd_out_other_domains
    @Test
    fun courseworkFloodDoesNotCrowdOutOtherDomains() {
        val overdue = (0 until 8).map { i -> OverdueItem(title = "Reading $i", dueInHours = -25.0) }
        val result = compute(
            AttentionFacts(
                overdue = overdue,
                discretionaryDollars = -125.0,
                gymLast7d = 0,
                gymTarget = 4,
            ),
        )

        val domains = result.reasons.map { it.domain }.toSet()
        assertTrue(Domain.MONEY in domains)
        assertTrue(Domain.GYM in domains)
        val moneyReason = result.reasons.first { it.domain == Domain.MONEY }
        assertEquals(Severity.RISK, moneyReason.severity)
        assertTrue(result.reasons.size <= 6)
    }
}
