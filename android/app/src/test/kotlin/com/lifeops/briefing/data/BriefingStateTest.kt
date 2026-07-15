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

    @Test
    fun parsesReasonsForSeverityDotsAndRoundTripsThroughJson() {
        // Matches attention.compute()'s {domain, severity, title,
        // recommended_action[, due]} reason shape -- the widget only needs
        // domain+severity for its per-domain dots, the rest is panel-only.
        val raw = """{
            "date":"2026-07-12", "text":"Do the paper.",
            "facts":{"attention":{
                "state":"risk", "symbol":"◆", "label":"RISK", "headline":"Finish the reading.",
                "reasons":[
                    {"domain":"coursework","severity":"risk","title":"Deadline risk: Paper","recommended_action":"..."},
                    {"domain":"money","severity":"watch","title":"Discretionary buffer is low","recommended_action":"..."}
                ]
            }}
        }"""

        val state = BriefingState.fromApiResponse(raw, 1L)

        assertEquals(
            listOf(AttentionReason("coursework", "risk"), AttentionReason("money", "watch")),
            state.reasons,
        )
        assertEquals(state, BriefingState.fromJson(state.toJson()))
    }

    @Test
    fun reasonsIsEmptyWhenAbsentFromResponse() {
        val raw = """{"date":"2026-07-12", "text":"All clear.", "facts":{"attention":{"state":"ok"}}}"""

        val state = BriefingState.fromApiResponse(raw, 1L)

        assertEquals(emptyList<AttentionReason>(), state.reasons)
    }

    @Test
    fun parsesWeatherFactsAndRoundTripsThroughJson() {
        val raw = """{
            "date":"2026-07-12",
            "text":"Nice day.",
            "facts":{
                "temperature_f":73, "weather_high_f":85, "weather_low_f":67,
                "weather_condition":"Cloudy"
            }
        }"""

        val state = BriefingState.fromApiResponse(raw, 1L)

        assertEquals(73, state.temperatureF)
        assertEquals(85, state.weatherHighF)
        assertEquals(67, state.weatherLowF)
        assertEquals("Cloudy", state.weatherCondition)
        assertEquals(state, BriefingState.fromJson(state.toJson()))
    }

    @Test
    fun parsesNotableEventsAndRoundTripsThroughJson() {
        // Matches notable_events.this_weeks_notable_events's own
        // {title, date, weekday} shape (see the Python module) --
        // "weekday" is spelled out server-side so the widget never needs
        // its own date math.
        val raw = """{
            "date":"2026-07-15",
            "text":"All clear.",
            "facts":{"notable_events":[
                {"title":"Haircut","date":"2026-07-18","weekday":"Saturday"},
                {"title":"Dentist","date":"2026-07-20","weekday":"Monday"}
            ]}
        }"""

        val state = BriefingState.fromApiResponse(raw, 1L)

        assertEquals(
            listOf(
                NotableEvent("Haircut", "2026-07-18", "Saturday"),
                NotableEvent("Dentist", "2026-07-20", "Monday"),
            ),
            state.notableEvents,
        )
        assertEquals(state, BriefingState.fromJson(state.toJson()))
    }

    @Test
    fun notableEventsIsEmptyWhenAbsentFromResponse() {
        val raw = """{"date":"2026-07-15", "text":"All clear.", "facts":{}}"""

        val state = BriefingState.fromApiResponse(raw, 1L)

        assertEquals(emptyList<NotableEvent>(), state.notableEvents)
    }

    @Test
    fun weatherFieldsAreNullWhenExplicitJsonNull() {
        // Python sends explicit JSON null for every weather_* fact when the
        // NWS API is unconfigured/unreachable -- must read back as null,
        // not optInt's 0-default (which would render as a misleading "0°F").
        val raw = """{
            "date":"2026-07-12",
            "text":"No weather today.",
            "facts":{
                "temperature_f":null, "weather_high_f":null, "weather_low_f":null,
                "weather_condition":null
            }
        }"""

        val state = BriefingState.fromApiResponse(raw, 1L)

        assertEquals(null, state.temperatureF)
        assertEquals(null, state.weatherHighF)
        assertEquals(null, state.weatherLowF)
        assertEquals(null, state.weatherCondition)
    }

    @Test
    fun parsesSleepAndSocialFactsAndRoundTripsThroughJson() {
        val raw = """{
            "date":"2026-07-12",
            "text":"Slept well.",
            "facts":{
                "sleep_minutes":402, "partner_days_since":4, "friend_days_since":2
            }
        }"""

        val state = BriefingState.fromApiResponse(raw, 1L)

        assertEquals(402, state.sleepMinutes)
        assertEquals(4, state.partnerDaysSince)
        assertEquals(2, state.friendDaysSince)
        assertEquals(state, BriefingState.fromJson(state.toJson()))
    }

    @Test
    fun sleepAndSocialFieldsAreNullWhenExplicitJsonNull() {
        val raw = """{
            "date":"2026-07-12",
            "text":"No data today.",
            "facts":{
                "sleep_minutes":null, "partner_days_since":null, "friend_days_since":null
            }
        }"""

        val state = BriefingState.fromApiResponse(raw, 1L)

        assertEquals(null, state.sleepMinutes)
        assertEquals(null, state.partnerDaysSince)
        assertEquals(null, state.friendDaysSince)
    }
}
