package com.lifeops.briefing

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Exercises AnthropicClient.kt's request construction, response parsing, and
 * retry policy -- no real network call, same reasoning FlowSavvyClientTest.kt's
 * own kdoc documents (this project has no OkHttp/MockWebServer dependency).
 * Robolectric is required because org.json.JSONObject is the real Android
 * stub jar otherwise (throws "not mocked") -- same as BriefingStateTest.kt.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AnthropicClientTest {

    // ---- request construction ----

    @Test
    fun buildMessagesRequestBody_matchesFlatMessagesApiShape() {
        val body = JSONObject(buildMessagesRequestBody("claude-haiku-4-5-20251001", 220, "hello"))

        assertEquals("claude-haiku-4-5-20251001", body.getString("model"))
        assertEquals(220, body.getInt("max_tokens"))
        val messages = body.getJSONArray("messages")
        assertEquals(1, messages.length())
        val msg = messages.getJSONObject(0)
        assertEquals("user", msg.getString("role"))
        assertEquals("hello", msg.getString("content"))
    }

    // ---- response parsing ----

    @Test
    fun extractResponseText_returnsFirstTextBlock() {
        val response = JSONObject(
            """{"content":[{"type":"text","text":"the actual reply"}],"usage":{"input_tokens":1,"output_tokens":2}}""",
        )

        assertEquals("the actual reply", extractResponseText(response))
    }

    @Test
    fun extractResponseText_skipsNonTextBlocksAndReturnsFirstTextOne() {
        val response = JSONObject(
            """{"content":[{"type":"thinking","thinking":""},{"type":"text","text":"final answer"}]}""",
        )

        assertEquals("final answer", extractResponseText(response))
    }

    @Test
    fun extractResponseText_returnsNull_whenNoContentArray() {
        assertNull(extractResponseText(JSONObject("""{"stop_reason":"refusal"}""")))
    }

    @Test
    fun extractResponseText_returnsNull_whenNoTextBlock() {
        val response = JSONObject("""{"content":[{"type":"tool_use","name":"x"}]}""")

        assertNull(extractResponseText(response))
    }

    // ---- categorize_unknown response parsing (ports test_llm.py's own cases) ----

    @Test
    fun parseCategorizeResponse_returnsValidCategory() {
        val result = parseCategorizeResponse("""{"category": "Groceries"}""", listOf("Groceries", "Dining"))

        assertEquals("Groceries", result)
    }

    @Test
    fun parseCategorizeResponse_rejectsCategoryNotInAllowedList() {
        val result = parseCategorizeResponse("""{"category": "Bogus"}""", listOf("Groceries", "Dining"))

        assertNull(result)
    }

    @Test
    fun parseCategorizeResponse_returnsNullForExplicitNullCategory() {
        val result = parseCategorizeResponse("""{"category": null}""", listOf("Groceries"))

        assertNull(result)
    }

    @Test
    fun parseCategorizeResponse_returnsNullOnMalformedJson() {
        assertNull(parseCategorizeResponse("not json at all", listOf("Groceries")))
    }

    @Test
    fun parseCategorizeResponse_tolerantOfProseAroundTheJson() {
        val text = "Sure, here you go:\n{\"category\": \"Dining\"}\nHope that helps!"

        assertEquals("Dining", parseCategorizeResponse(text, listOf("Groceries", "Dining")))
    }

    // ---- retry policy (mirrors FlowSavvyClientTest.kt's structure) ----

    private class RecordingSleep {
        val delaysMs = mutableListOf<Long>()
        val fn: (Long) -> Unit = { delaysMs.add(it) }
    }

    @Test
    fun connectionError_retriedThenSucceeds() {
        var attempts = 0
        val recorder = RecordingSleep()

        val result = withAnthropicRetry(recorder.fn) {
            attempts++
            if (attempts < 3) throw AnthropicConnectionException("blip")
            "ok"
        }

        assertEquals("ok", result)
        assertEquals(3, attempts)
        assertEquals(listOf(500L, 1000L), recorder.delaysMs)
    }

    @Test
    fun connectionError_exhaustsRetriesThenRethrows() {
        var attempts = 0
        val recorder = RecordingSleep()

        try {
            withAnthropicRetry(recorder.fn) {
                attempts++
                throw AnthropicConnectionException("still broken")
            }
            fail("expected AnthropicConnectionException to propagate")
        } catch (e: AnthropicConnectionException) {
            assertEquals("still broken", e.message)
        }

        assertEquals(3, attempts)
    }

    @Test
    fun http429_isRetried() {
        var attempts = 0
        val recorder = RecordingSleep()

        val result = withAnthropicRetry(recorder.fn) {
            attempts++
            if (attempts < 2) throw AnthropicHttpException(429, "rate limited")
            "ok"
        }

        assertEquals("ok", result)
        assertEquals(2, attempts)
    }

    @Test
    fun http500_isRetried_unlikeFlowSavvysAsymmetricPolicy() {
        // Deliberately different from FlowSavvyClient.kt's policy -- see
        // AnthropicHttpException's kdoc for why a bare Messages API call has
        // no "might double-submit a non-idempotent write" hazard.
        var attempts = 0
        val recorder = RecordingSleep()

        val result = withAnthropicRetry(recorder.fn) {
            attempts++
            if (attempts < 2) throw AnthropicHttpException(500, "server error")
            "ok"
        }

        assertEquals("ok", result)
        assertEquals(2, attempts)
    }

    @Test
    fun http400_isNeverRetried() {
        var attempts = 0
        val recorder = RecordingSleep()

        try {
            withAnthropicRetry(recorder.fn) {
                attempts++
                throw AnthropicHttpException(400, "bad request")
            }
            fail("expected AnthropicHttpException to propagate immediately")
        } catch (e: AnthropicHttpException) {
            assertEquals(400, e.statusCode)
        }

        assertEquals(1, attempts)
        assertEquals(emptyList<Long>(), recorder.delaysMs)
    }

    @Test
    fun retryAfterHeader_usedForHttp429Backoff() {
        var attempts = 0
        val recorder = RecordingSleep()

        withAnthropicRetry(recorder.fn) {
            attempts++
            if (attempts < 2) throw AnthropicHttpException(429, "rate limited", retryAfterSeconds = 3.0)
            "ok"
        }

        assertEquals(listOf(3000L), recorder.delaysMs)
    }

    @Test
    fun parseAnthropicRetryAfterHeader_parsesNumericValue() {
        assertEquals(2.5, parseAnthropicRetryAfterHeader("2.5"))
    }

    @Test
    fun parseAnthropicRetryAfterHeader_returnsNullForMissingOrNonNumeric() {
        assertNull(parseAnthropicRetryAfterHeader(null))
        assertNull(parseAnthropicRetryAfterHeader("soon"))
    }
}
