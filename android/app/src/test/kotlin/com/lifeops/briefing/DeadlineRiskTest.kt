package com.lifeops.briefing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mirrors, one-to-one, the two Python test files covering
 * `lifeops/engines/load_engine.py`:
 *  - `tests/test_load_engine.py` (the `plan()`/`at_risk_assignments` family)
 *  - `tests/test_deadline_cashflow.py`'s `deadline_risk` section only (its
 *    `run_cashflow` section belongs to `runner.py`, not `load_engine.py`,
 *    and is out of scope for this port).
 *
 * Each test is annotated with which Python test it corresponds to, same
 * convention as `RoutineTest.kt`. No Robolectric needed: like `Routine.kt`,
 * `DeadlineRisk.kt` has zero Android-framework dependency, so plain JUnit
 * is enough. */
class DeadlineRiskTest {

    /** Mirrors Python's `_assignment(title, due_in_h, remaining_min, progress=0)` test helper. */
    private fun assignment(
        title: String,
        dueInHours: Double,
        remainingMin: Double,
        progress: Double = 0.0,
    ): Assignment = Assignment(
        title = title,
        dueInHours = dueInHours,
        dueInDays = dueInHours / 24.0,
        remainingMin = remainingMin,
        progress = progress,
    )

    // ── plan() / at_risk_assignments -- tests/test_load_engine.py ──────────

    // Corresponds to test_no_assignments_no_alerts
    @Test
    fun plan_noAssignments_noAlerts() {
        val out = plan(emptyList())
        assertEquals(emptyList<Alert>(), out.alerts)
    }

    // Corresponds to test_heavy_soon_triggers_alert
    @Test
    fun plan_heavySoon_triggersAlert() {
        val a = assignment("Problem Set 4", dueInHours = 24.0, remainingMin = 180.0)
        val out = plan(listOf(a))
        assertEquals(1, out.alerts.size)
        assertEquals("high", out.alerts[0].severity)
    }

    // Corresponds to test_in_progress_not_flagged (has progress -- not a cold start anymore)
    @Test
    fun plan_inProgress_notFlagged() {
        val a = assignment("Problem Set 4", dueInHours = 24.0, remainingMin = 180.0, progress = 60.0)
        val out = plan(listOf(a))
        assertEquals(emptyList<Alert>(), out.alerts)
    }

    // Corresponds to test_short_assignment_not_flagged (< 120 min remaining -- not "heavy")
    @Test
    fun plan_shortAssignment_notFlagged() {
        val a = assignment("Reading quiz", dueInHours = 24.0, remainingMin = 60.0)
        val out = plan(listOf(a))
        assertEquals(emptyList<Alert>(), out.alerts)
    }

    // Corresponds to test_far_deadline_not_flagged (due in 72h -- not imminent)
    @Test
    fun plan_farDeadline_notFlagged() {
        val a = assignment("Final project", dueInHours = 72.0, remainingMin = 300.0)
        val out = plan(listOf(a))
        val heavyAlerts = out.alerts.filter { it.message.lowercase().contains("soon") }
        assertEquals(emptyList<Alert>(), heavyAlerts)
    }

    // Corresponds to test_overbooked_week_triggers_alert (> 25h of work due in 7 days)
    @Test
    fun plan_overbookedWeek_triggersAlert() {
        val assignments = (0 until 5).map { assignment("HW $it", dueInHours = 100.0, remainingMin = 360.0) }
        val out = plan(assignments)
        val overbooked = out.alerts.filter { it.message.contains("Overbooked") }
        assertTrue(overbooked.isNotEmpty())
    }

    // Corresponds to test_within_capacity_no_overbooked_alert
    @Test
    fun plan_withinCapacity_noOverbookedAlert() {
        val assignments = listOf(assignment("HW 1", dueInHours = 100.0, remainingMin = 300.0)) // 5h
        val out = plan(assignments)
        val overbooked = out.alerts.filter { it.message.contains("Overbooked") }
        assertEquals(emptyList<Alert>(), overbooked)
    }

    // Corresponds to test_both_alerts_can_fire
    @Test
    fun plan_bothAlertsCanFire() {
        val heavy = assignment("Exam prep", dueInHours = 24.0, remainingMin = 240.0)
        val bulk = (0 until 5).map { assignment("HW $it", dueInHours = 100.0, remainingMin = 360.0) }
        val out = plan(listOf(heavy) + bulk)
        assertTrue(out.alerts.size >= 2)
    }

    // Corresponds to test_malformed_assignment_does_not_crash
    @Test
    fun plan_malformedAssignment_doesNotCrash() {
        val out = plan(listOf(Assignment(), Assignment(title = null, dueInHours = null, remainingMin = null, dueInDays = null)))
        assertEquals(emptyList<Alert>(), out.alerts)
    }

    // Corresponds to test_partial_fields_still_evaluated (a valid heavy+soon one among garbage still alerts)
    @Test
    fun plan_partialFieldsStillEvaluated() {
        val good = assignment("Real HW", dueInHours = 24.0, remainingMin = 180.0)
        val out = plan(listOf(Assignment(), good))
        assertEquals(1, out.alerts.size)
    }

    // ── deadline_risk() -- tests/test_deadline_cashflow.py ──────────────────

    // Corresponds to test_no_items_no_alert
    @Test
    fun deadlineRisk_noItems_noAlert() {
        val out = deadlineRisk(emptyList())
        assertEquals(AlertsResult(emptyList()), out)
    }

    // Corresponds to test_comfortable_load_no_alert (1h of work due in 10 days: available ~35h -- fits easily)
    @Test
    fun deadlineRisk_comfortableLoad_noAlert() {
        val items = listOf(Assignment(title = "Reading", dueInDays = 10.0, remainingMin = 60.0))
        assertEquals(emptyList<Alert>(), deadlineRisk(items).alerts)
    }

    // Corresponds to test_crunch_flags_risk (10h of work due in 2 days: available ~7h -- does not fit)
    @Test
    fun deadlineRisk_crunchFlagsRisk() {
        val items = listOf(Assignment(title = "Big Paper", dueInDays = 2.0, remainingMin = 600.0))
        val out = deadlineRisk(items)
        assertEquals(1, out.alerts.size)
        assertEquals("high", out.alerts[0].severity)
        assertTrue(out.alerts[0].message.contains("Big Paper"))
    }

    // Corresponds to test_reports_only_earliest_binding_deadline (sorted by due: 1d/5h binds first; 5d item is downstream)
    @Test
    fun deadlineRisk_reportsOnlyEarliestBindingDeadline() {
        val items = listOf(
            Assignment(title = "Later", dueInDays = 5.0, remainingMin = 180.0),
            Assignment(title = "Soonest", dueInDays = 1.0, remainingMin = 300.0),
        )
        val out = deadlineRisk(items)
        assertEquals(1, out.alerts.size)
        assertTrue(out.alerts[0].message.contains("Soonest")) // earliest binding, not "Later"
    }

    // Corresponds to test_zero_remaining_items_ignored
    @Test
    fun deadlineRisk_zeroRemainingItemsIgnored() {
        val items = listOf(Assignment(title = "Done", dueInDays = 1.0, remainingMin = 0.0))
        assertEquals(emptyList<Alert>(), deadlineRisk(items).alerts)
    }

    // ── Supplemental (not a direct Python test-file mirror): exercise the
    // raw-accessor entry points at_risk_assignments/deadlineCrunchItem
    // directly, since both are public API ported from named Python
    // functions but only exercised indirectly (via plan/deadline_risk) in
    // the Python test suite. ────────────────────────────────────────────

    // Supplemental: deadlineCrunchItem returns the raw earliest-binding item, not a formatted alert.
    @Test
    fun deadlineCrunchItem_returnsRawEarliestBindingItem() {
        val items = listOf(
            Assignment(title = "Later", dueInDays = 5.0, remainingMin = 180.0),
            Assignment(title = "Soonest", dueInDays = 1.0, remainingMin = 300.0),
        )
        val item = deadlineCrunchItem(items)
        assertEquals("Soonest", item?.title)
    }

    // Supplemental: deadlineCrunchItem returns null when nothing's at risk.
    @Test
    fun deadlineCrunchItem_returnsNullWhenNothingAtRisk() {
        val items = listOf(Assignment(title = "Reading", dueInDays = 10.0, remainingMin = 60.0))
        assertNull(deadlineCrunchItem(items))
    }

    // Supplemental: atRiskAssignments returns the raw dicts behind plan()'s "Heavy + soon" alerts.
    @Test
    fun atRiskAssignments_returnsRawHeavyPlusSoonItems() {
        val heavy = assignment("Exam prep", dueInHours = 24.0, remainingMin = 240.0)
        val light = assignment("Reading quiz", dueInHours = 24.0, remainingMin = 60.0)
        val result = atRiskAssignments(listOf(heavy, light))
        assertEquals(listOf(heavy), result)
    }
}
