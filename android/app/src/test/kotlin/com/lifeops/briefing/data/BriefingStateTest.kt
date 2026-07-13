package com.lifeops.briefing.data

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** org.json.JSONObject is the real Android stub jar on the unit-test
 * classpath (throws "not mocked" on every call) unless run under
 * Robolectric, which shadows it with a working implementation -- plain
 * JUnit alone isn't enough here, unlike a test with no org.json.* in its
 * call path. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BriefingStateTest {
    @Test
    fun parsesDeterministicAttentionFromBriefingResponse() {
        val raw = """{
            "date":"2026-07-12",
            "text":"Do the paper.",
            "facts":{"attention":{
                "state":"fucked",
                "symbol":"■",
                "label":"FUCKED",
                "headline":"Do it now or deliberately reschedule it."
            }}
        }"""

        val state = BriefingState.fromApiResponse(raw, 123L)

        assertEquals("fucked", state.attentionState)
        assertEquals("■", state.attentionSymbol)
        assertEquals("FUCKED", state.attentionLabel)
        assertEquals("Do it now or deliberately reschedule it.", state.attentionHeadline)
        assertEquals(123L, state.fetchedAtEpochMillis)
        assertEquals(state, BriefingState.fromJson(state.toJson()))
    }
}
