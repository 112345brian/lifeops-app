package com.lifeops.briefing.data

import org.junit.Assert.assertEquals
import org.junit.Test

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
