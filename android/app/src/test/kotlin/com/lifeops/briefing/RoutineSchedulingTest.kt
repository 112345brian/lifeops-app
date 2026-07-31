package com.lifeops.briefing

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the generic scheduling machinery added to `Routine.kt`
 * ([TimeSlot], [CandidateDay], [slotFor], [runLength], [scheduleRoutine]) --
 * see that file's kdoc for the design (extracted from `gym_engine.py`'s
 * `plan()` but with every gym-specific concept removed).
 *
 * Deliberately uses a synthetic "chore" routine with slots that have
 * NOTHING to do with gym (a made-up "cleaning" routine with "quick"/
 * "deep" slots gated on made-up flags like `houseGuestsComing`) to prove
 * this machinery is actually generic, not secretly gym-shaped. `GymSchedule.kt`
 * has its own tests reproducing the original 19 gym-specific scenarios
 * through this same machinery.
 */
class RoutineSchedulingTest {

    private val mon: LocalDate = LocalDate.of(2026, 7, 6)

    private fun dates(n: Int, start: LocalDate = mon): List<LocalDate> =
        (0 until n).map { start.plusDays(it.toLong()) }

    // A synthetic two-slot routine, nothing gym-shaped: a "quick tidy" slot
    // available whenever the house isn't already clean and no guests are
    // coming (in which case a longer "deep clean" is needed instead), and a
    // "deep clean" slot available specifically when guests are coming.
    private val cleaningRoutine = Routine(
        id = "cleaning",
        times = 3,
        perDays = 7,
        anchor = Anchor.WINDOW,
        slots = listOf(
            TimeSlot(start = "10:00", end = "10:30", kind = "quick", condition = "!houseGuestsComing && !alreadyClean"),
            TimeSlot(start = "09:00", end = "11:00", kind = "deep", condition = "houseGuestsComing && !alreadyClean"),
        ),
    )

    private fun ctx(houseGuestsComing: Boolean = false, alreadyClean: Boolean = false): Map<String, Any> =
        mapOf("houseGuestsComing" to houseGuestsComing, "alreadyClean" to alreadyClean)

    // -----------------------------------------------------------------
    // slotFor: precedence / first-match semantics
    // -----------------------------------------------------------------

    @Test
    fun slotFor_returnsFirstDeclaredSlotWhoseConditionIsTrue() {
        // Both "quick" and "deep" could theoretically both be considered,
        // but only "quick" is declared first and its condition is true here
        // (no guests, not already clean) -- "deep"'s condition is false
        // anyway in this case, so this alone doesn't prove precedence yet.
        val slot = slotFor(cleaningRoutine, ctx(houseGuestsComing = false, alreadyClean = false))
        assertEquals("quick", slot?.kind)
    }

    @Test
    fun slotFor_fallsThroughToSecondSlotWhenFirstConditionFalse() {
        val slot = slotFor(cleaningRoutine, ctx(houseGuestsComing = true, alreadyClean = false))
        assertEquals("deep", slot?.kind)
    }

    @Test
    fun slotFor_returnsNullWhenNoSlotConditionIsTrue() {
        val slot = slotFor(cleaningRoutine, ctx(houseGuestsComing = false, alreadyClean = true))
        assertNull(slot)
    }

    @Test
    fun slotFor_respectsDeclaredOrderWhenTwoSlotsAreMutuallyPossible() {
        // Construct a routine where BOTH slots' conditions are simultaneously
        // true, to prove the first-declared slot wins -- not "any" match.
        val bothPossible = Routine(
            id = "synthetic",
            times = 1,
            perDays = 1,
            anchor = Anchor.WINDOW,
            slots = listOf(
                TimeSlot(start = "08:00", end = "09:00", kind = "first", condition = "flagA"),
                TimeSlot(start = "12:00", end = "13:00", kind = "second", condition = "flagA || flagB"),
            ),
        )
        val slot = slotFor(bothPossible, mapOf("flagA" to true, "flagB" to true))
        assertEquals("first", slot?.kind)
    }

    @Test(expected = LifeScriptEvaluateException::class)
    fun slotFor_throwsWhenConditionEvaluatesToNonBoolean() {
        val badRoutine = Routine(
            id = "bad",
            times = 1,
            perDays = 1,
            anchor = Anchor.WINDOW,
            slots = listOf(TimeSlot(start = "08:00", end = "09:00", kind = "x", condition = "1 + 1")),
        )
        slotFor(badRoutine, emptyMap())
    }

    // -----------------------------------------------------------------
    // runLength
    // -----------------------------------------------------------------

    @Test
    fun runLength_isOneWithNoAdjacentBusyDays() {
        assertEquals(1, runLength(mon, emptySet()))
    }

    @Test
    fun runLength_countsConsecutiveDaysOnBothSides() {
        val busy = setOf(mon.minusDays(1), mon, mon.plusDays(1), mon.plusDays(2))
        assertEquals(4, runLength(mon, busy))
    }

    @Test
    fun runLength_stopsAtFirstGap() {
        val busy = setOf(mon.minusDays(1), mon, mon.plusDays(2)) // gap at mon+1
        assertEquals(2, runLength(mon, busy))
    }

    // -----------------------------------------------------------------
    // scheduleRoutine: consecutive-day cap / greedy pick
    // -----------------------------------------------------------------

    @Test
    fun scheduleRoutine_picksUpToNeededRespectingCap() {
        val days = dates(7).map { CandidateDay(it, ctx()) }
        val result = scheduleRoutine(cleaningRoutine, days, needed = 3, maxConsecutive = 2)
        assertEquals(3, result.chosen.size)
        assertTrue(result.chosen.all { it.slot.kind == "quick" })
    }

    @Test
    fun scheduleRoutine_neverPicksMoreThanMaxConsecutiveInARow() {
        val days = dates(7).map { CandidateDay(it, ctx()) }
        val result = scheduleRoutine(cleaningRoutine, days, needed = 7, maxConsecutive = 2)
        val chosen = result.chosen.map { it.date }.sorted()
        for (i in 0..chosen.size - 3) {
            val threeInARow = chosen[i].plusDays(1) == chosen[i + 1] && chosen[i + 1].plusDays(1) == chosen[i + 2]
            assertFalse("3 consecutive days chosen", threeInARow)
        }
    }

    @Test
    fun scheduleRoutine_excludesDatesInExcludeSet() {
        val days = dates(3).map { CandidateDay(it, ctx()) }
        val result = scheduleRoutine(
            cleaningRoutine,
            days,
            needed = 3,
            maxConsecutive = 3,
            excludeDates = setOf(dates(3)[0]),
        )
        assertFalse(result.chosen.any { it.date == dates(3)[0] })
        assertEquals(2, result.chosen.size)
    }

    @Test
    fun scheduleRoutine_skipsDaysWithNoViableSlot() {
        val days = listOf(
            CandidateDay(mon, ctx(alreadyClean = true)), // no viable slot
            CandidateDay(mon.plusDays(1), ctx()),
        )
        val result = scheduleRoutine(cleaningRoutine, days, needed = 5, maxConsecutive = 5)
        assertEquals(1, result.chosen.size)
        assertEquals(mon.plusDays(1), result.chosen[0].date)
    }

    @Test
    fun scheduleRoutine_busyDatesCountTowardCapEvenIfNotChosen() {
        // mon and mon+1 are pre-busy (e.g. already completed); with cap=2,
        // mon+2 would make a run of 3 and must be rejected.
        val days = listOf(mon.plusDays(2)).map { CandidateDay(it, ctx()) }
        val result = scheduleRoutine(
            cleaningRoutine,
            days,
            needed = 1,
            maxConsecutive = 2,
            busyDates = setOf(mon, mon.plusDays(1)),
        )
        assertTrue("day that would make a 3-run must be rejected", result.chosen.isEmpty())
    }

    // -----------------------------------------------------------------
    // scheduleRoutine: viableLeft simulation
    // -----------------------------------------------------------------

    @Test
    fun viableLeft_countsChosenPlusRemainingUnderCapWhenNeededIsZero() {
        // Mirrors GymEngineTest's
        // viableLeftChecksCandidatesAgainstEachOtherNotJustBusy regression,
        // reproduced generically: three mutually-adjacent-to-EACH-OTHER
        // candidate days (not adjacent to any pre-existing busy set) with
        // maxConsecutive=1 can fit at most 2 of them together, not all 3,
        // even though each one looks individually viable in isolation.
        val days = listOf("2026-07-06", "2026-07-09", "2026-07-10", "2026-07-11")
            .map { CandidateDay(LocalDate.parse(it), ctx()) }
        val result = scheduleRoutine(cleaningRoutine, days, needed = 0, maxConsecutive = 1)
        assertEquals(0, result.chosen.size) // needed=0 -> pick loop never runs
        assertEquals(3, result.viableLeft) // 07-06 alone, plus one of {07-09, 07-10, 07-11}... actually 07-09/10/11 mutually adjacent
    }

    @Test
    fun viableLeft_excludesCapRejectedDays() {
        // Three open days but only 1 fits under maxConsecutive=1 once the
        // greedy pick loop has already claimed one of them.
        val days = dates(3).map { CandidateDay(it, ctx()) }
        val result = scheduleRoutine(cleaningRoutine, days, needed = 1, maxConsecutive = 1)
        assertEquals(1, result.chosen.size)
        // day0 chosen; day1 rejected (adjacent to day0); day2 free (not
        // adjacent to day0 once day1 is skipped in the simulation... but
        // day2 IS adjacent to day1 conceptually only if day1 were busy,
        // which it isn't) -- day2 is viable.
        assertEquals(2, result.viableLeft)
    }

    @Test
    fun viableLeft_isAtLeastChosenSize() {
        val days = dates(1).map { CandidateDay(it, ctx()) }
        val result = scheduleRoutine(cleaningRoutine, days, needed = 1, maxConsecutive = 1)
        assertTrue(result.viableLeft >= result.chosen.size)
    }

    // -----------------------------------------------------------------
    // scheduleRoutine: excludeDates and busyDates as genuinely separate
    // parameters (gym passes the same set for both; these lock down what
    // happens for a caller that doesn't)
    // -----------------------------------------------------------------

    @Test
    fun scheduleRoutine_neverBooksADateThatIsBusyButNotExcluded() {
        // Regression: the greedy pick loop used to check only the
        // consecutive-day cap, never "is this day already occupied", while
        // the viable-left simulation DID check `date in simBusy`. With
        // excludeDates == busyDates (gym's only usage) the asymmetry was
        // invisible, since a busy date was never a candidate in the first
        // place. A caller passing a busy set that isn't its exclude set got
        // that day double-booked.
        val d = dates(2)
        val result = scheduleRoutine(
            cleaningRoutine,
            d.map { CandidateDay(it, ctx()) },
            needed = 2,
            maxConsecutive = 2,
            excludeDates = emptySet(),
            busyDates = setOf(d[0]),
        )
        assertFalse("already-busy day must not be re-booked", result.chosen.any { it.date == d[0] })
        assertEquals(listOf(d[1]), result.chosen.map { it.date })
        // d[0] is busy and d[1] is now chosen, so nothing further is viable.
        assertEquals(1, result.viableLeft)
    }

    @Test
    fun scheduleRoutine_duplicateCandidateDatesProduceASingleBooking() {
        // Two CandidateDay entries for the SAME date must not both be
        // booked -- that would emit two overlapping bookings for one day.
        val days = listOf(CandidateDay(mon, ctx()), CandidateDay(mon, ctx()))
        val result = scheduleRoutine(cleaningRoutine, days, needed = 2, maxConsecutive = 2)
        assertEquals(listOf(mon), result.chosen.map { it.date })
        assertEquals(1, result.viableLeft)
    }

    // -----------------------------------------------------------------
    // Slot-list shapes the two-slot gym routine never exercises
    // -----------------------------------------------------------------

    @Test
    fun scheduleRoutine_routineWithNoSlotsSchedulesNothing() {
        val noSlots = Routine(id = "empty", times = 3, perDays = 7, anchor = Anchor.WINDOW)
        val days = dates(5).map { CandidateDay(it, ctx()) }
        val result = scheduleRoutine(noSlots, days, needed = 3, maxConsecutive = 3)
        assertNull(slotFor(noSlots, ctx()))
        assertTrue(result.chosen.isEmpty())
        assertEquals(0, result.viableLeft)
    }

    @Test
    fun slotFor_firstMatchSemanticsHoldBeyondTwoSlots() {
        val threeSlots = Routine(
            id = "three",
            times = 1,
            perDays = 1,
            anchor = Anchor.WINDOW,
            slots = listOf(
                TimeSlot(start = "08:00", end = "09:00", kind = "a", condition = "flagA"),
                TimeSlot(start = "12:00", end = "13:00", kind = "b", condition = "flagB"),
                TimeSlot(start = "18:00", end = "19:00", kind = "c", condition = "true"),
            ),
        )
        val flags = { a: Boolean, b: Boolean -> mapOf<String, Any>("flagA" to a, "flagB" to b) }
        assertEquals("a", slotFor(threeSlots, flags(true, true))?.kind)
        assertEquals("b", slotFor(threeSlots, flags(false, true))?.kind)
        assertEquals("c", slotFor(threeSlots, flags(false, false))?.kind)
    }

    @Test(expected = LifeScriptParseException::class)
    fun slotFor_reportsAMalformedLaterConditionEvenWhenAnEarlierSlotMatches() {
        // The first slot's condition is true, so a lazily-parsed
        // implementation would never notice the second slot's syntax error
        // until the one day that finally reaches it. Conditions are parsed
        // up front instead, so this fails immediately and deterministically.
        val badSecondSlot = Routine(
            id = "bad",
            times = 1,
            perDays = 1,
            anchor = Anchor.WINDOW,
            slots = listOf(
                TimeSlot(start = "08:00", end = "09:00", kind = "ok", condition = "true"),
                TimeSlot(start = "12:00", end = "13:00", kind = "broken", condition = "flagA &&"),
            ),
        )
        slotFor(badSecondSlot, mapOf("flagA" to true))
    }
}
