package com.lifeops.briefing.data

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** org.json.JSONObject is the real Android stub jar on the unit-test
 * classpath (throws "not mocked" on every call) unless run under
 * Robolectric, same as BriefingStateTest. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NextTasksStateTest {

    @Test
    fun parsesTasksAndEventsFromApiResponseShape() {
        // Same {tasks, events} shape /api/next-tasks returns AND what
        // fcm.send_next_tasks's FCM payload carries -- NextTasksPersistWorker
        // (the FCM push path) and NextTasksRefreshWorker (the periodic pull
        // path) both parse through this one function.
        val raw = """{
            "tasks": [{"id": "t1", "title": "Finish reading", "start": "2026-07-13T09:00:00"}],
            "events": [{"title": "Papa's BBQ", "start": "2026-07-13T18:00:00"}]
        }"""

        val state = NextTasksState.fromApiResponse(raw, 123L)

        assertEquals(listOf(NextTask("t1", "Finish reading", "2026-07-13T09:00:00")), state.tasks)
        assertEquals(listOf(TodayEvent("Papa's BBQ", "2026-07-13T18:00:00")), state.events)
        assertEquals(123L, state.fetchedAtEpochMillis)
        assertEquals(state, NextTasksState.fromJson(state.toJson()))
    }

    @Test
    fun parsesEmptyTasksAndEvents() {
        val state = NextTasksState.fromApiResponse("""{"tasks": [], "events": []}""", 1L)

        assertEquals(emptyList<NextTask>(), state.tasks)
        assertEquals(emptyList<TodayEvent>(), state.events)
    }

    @Test
    fun parsesGymRingFromApiResponseAndRoundTripsThroughJson() {
        // Matches gather.gym_ring_now's {fill, color, gym_last_7d, gym_target,
        // today_done} shape, carried under the "gym_ring" key.
        val raw = """{
            "tasks": [], "events": [],
            "gym_ring": {"fill": 0.75, "color": "yellow", "gym_last_7d": 3, "gym_target": 4, "today_done": false}
        }"""

        val state = NextTasksState.fromApiResponse(raw, 1L)

        assertEquals(GymRing(fill = 0.75f, color = "yellow", gymLast7d = 3, gymTarget = 4, todayDone = false), state.gymRing)
        assertEquals(state, NextTasksState.fromJson(state.toJson()))
    }

    @Test
    fun gymRingIsNullWhenAbsentFromResponse() {
        val state = NextTasksState.fromApiResponse("""{"tasks": [], "events": []}""", 1L)

        assertEquals(null, state.gymRing)
    }
}
