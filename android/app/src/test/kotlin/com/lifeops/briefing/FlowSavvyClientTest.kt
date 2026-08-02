package com.lifeops.briefing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Exercises `FlowSavvyClient.kt`'s retry policy and pure param-building
 * helpers directly, rather than the network methods themselves: this
 * project has no mock-HTTP-server dependency (no OkHttp/MockWebServer in
 * `android/app/build.gradle.kts`), and `YnabRefresh.kt` -- the existing
 * direct-bearer-token REST client this codebase already ships -- has no
 * dedicated test file either, for the same reason. [withFlowSavvyRetry] is
 * deliberately factored out as a generic function over an injected `fn`
 * (mirroring `flowsavvy.py`'s own `_with_retry(fn)`), which makes the one
 * behavior actually named as a hazard in
 * docs/lifeops_capability_todo.md -- "a naive Kotlin retry-everything port
 * would recreate the duplicate-task bug this was built to prevent" --
 * directly unit-testable without any real I/O.
 */
class FlowSavvyClientTest {

    private class RecordingSleep {
        val delaysMs = mutableListOf<Long>()
        val fn: (Long) -> Unit = { delaysMs.add(it) }
    }

    // --- connection-error retries (mirrors requests.exceptions.ConnectionError) ---

    @Test
    fun connectionError_retriedThenSucceeds() {
        var attempts = 0
        val recorder = RecordingSleep()

        val result = withFlowSavvyRetry(recorder.fn) {
            attempts++
            if (attempts < 3) throw FlowSavvyConnectionException("blip")
            "ok"
        }

        assertEquals("ok", result)
        assertEquals(3, attempts)
        // _BACKOFF * (2 ** attempt) for attempt=0,1 -> 0.5s, 1.0s.
        assertEquals(listOf(500L, 1000L), recorder.delaysMs)
    }

    @Test
    fun connectionError_exhaustsRetriesThenRethrows() {
        var attempts = 0
        val recorder = RecordingSleep()

        try {
            withFlowSavvyRetry(recorder.fn) {
                attempts++
                throw FlowSavvyConnectionException("still broken")
            }
            fail("expected FlowSavvyConnectionException to propagate")
        } catch (e: FlowSavvyConnectionException) {
            assertEquals("still broken", e.message)
        }

        // _RETRIES = 2 -> 3 total attempts (initial + 2 retries).
        assertEquals(3, attempts)
        assertEquals(listOf(500L, 1000L), recorder.delaysMs)
    }

    // --- HTTP 429 retries (mirrors the Python client's explicit safe-to-retry carve-out) ---

    @Test
    fun http429_retriedWithDefaultBackoff_whenNoRetryAfterHeader() {
        var attempts = 0
        val recorder = RecordingSleep()

        val result = withFlowSavvyRetry(recorder.fn) {
            attempts++
            if (attempts < 2) throw FlowSavvyHttpException(429, "rate limited", retryAfterSeconds = null)
            "ok"
        }

        assertEquals("ok", result)
        assertEquals(2, attempts)
        assertEquals(listOf(500L), recorder.delaysMs)
    }

    @Test
    fun http429_usesRetryAfterHeaderValue_whenPresent() {
        var attempts = 0
        val recorder = RecordingSleep()

        withFlowSavvyRetry(recorder.fn) {
            attempts++
            if (attempts < 2) throw FlowSavvyHttpException(429, "rate limited", retryAfterSeconds = 2.5)
            "ok"
        }

        assertEquals(listOf(2500L), recorder.delaysMs)
    }

    @Test
    fun http429_exhaustsRetriesThenRethrows() {
        var attempts = 0
        val recorder = RecordingSleep()

        try {
            withFlowSavvyRetry(recorder.fn) {
                attempts++
                throw FlowSavvyHttpException(429, "still limited")
            }
            fail("expected FlowSavvyHttpException to propagate")
        } catch (e: FlowSavvyHttpException) {
            assertEquals(429, e.statusCode)
        }

        assertEquals(3, attempts)
    }

    // --- the asymmetry itself: every OTHER non-2xx status is never retried ---

    @Test
    fun nonRetryableHttpError_isThrownImmediately_notRetried() {
        var attempts = 0
        val recorder = RecordingSleep()

        try {
            withFlowSavvyRetry(recorder.fn) {
                attempts++
                throw FlowSavvyHttpException(500, "server error")
            }
            fail("expected FlowSavvyHttpException to propagate")
        } catch (e: FlowSavvyHttpException) {
            assertEquals(500, e.statusCode)
        }

        // The whole point of the asymmetric policy: a response that came
        // back (even a 5xx) means the server saw the request, so retrying a
        // non-idempotent POST/PUT here would risk a duplicate task -- this
        // must NOT be retried, unlike a bare connection failure or a 429.
        assertEquals(1, attempts)
        assertTrue("must not sleep/backoff for a non-retryable status", recorder.delaysMs.isEmpty())
    }

    @Test
    fun nonRetryableHttpError_alsoNotRetriedFor4xxClientErrors() {
        var attempts = 0

        try {
            withFlowSavvyRetry({ }) {
                attempts++
                throw FlowSavvyHttpException(404, "not found")
            }
            fail("expected FlowSavvyHttpException to propagate")
        } catch (e: FlowSavvyHttpException) {
            assertEquals(404, e.statusCode)
        }

        assertEquals(1, attempts)
    }

    // --- Retry-After header parsing ---

    @Test
    fun parseRetryAfterHeader_parsesNumericValue() {
        assertEquals(120.0, parseRetryAfterHeader("120")!!, 0.0001)
        assertEquals(2.5, parseRetryAfterHeader("2.5")!!, 0.0001)
    }

    @Test
    fun parseRetryAfterHeader_returnsNullForMissingOrNonNumericValue() {
        // Python: `float(retry_after) if retry_after else None`, with a
        // ValueError (non-numeric header, e.g. an HTTP-date form) caught and
        // treated the same as "no header" -- both fall back to the
        // exponential backoff in withFlowSavvyRetry, not a thrown error.
        assertNull(parseRetryAfterHeader(null))
        assertNull(parseRetryAfterHeader(""))
        assertNull(parseRetryAfterHeader("Wed, 21 Oct 2026 07:28:00 GMT"))
    }

    // --- query-param building (mirrors `_get`'s filtering/bool-lowering) ---

    @Test
    fun buildQueryParams_dropsNullAndEmptyStringValues() {
        val params = buildQueryParams(mapOf("itemType" to "task", "query" to null, "notes" to ""))

        assertEquals(mapOf("itemType" to "task"), params)
    }

    @Test
    fun buildQueryParams_lowercasesBooleanValues() {
        val params = buildQueryParams(mapOf("completed" to false, "isAutoIgnored" to true))

        assertEquals(mapOf("completed" to "false", "isAutoIgnored" to "true"), params)
    }

    @Test
    fun buildQueryParams_passesThroughOtherTypesViaToString() {
        val params = buildQueryParams(mapOf("limit" to 5, "calendarId" to "cal-1"))

        assertEquals(mapOf("limit" to "5", "calendarId" to "cal-1"), params)
    }
}
