package com.lifeops.briefing

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** android.net.Uri is the real Android stub jar on the unit-test classpath
 * (throws "not mocked" on every call) unless run under Robolectric, same
 * reasoning as BriefingStateTest/NextTasksStateTest with org.json. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SimpleHttpTest {

    @Test
    fun authenticatedUrl_encodesPunctuationInToken() {
        // WEB_TOKEN is free-text (editable via the panel's own Settings
        // page), so & # + % and spaces are all realistically reachable, not
        // hypothetical -- an unencoded token here would corrupt or
        // truncate the query string.
        val url = authenticatedUrl("https://panel.example", "/api/next-tasks", "a&b#c+d%e f")

        assertEquals("https://panel.example/api/next-tasks?token=a%26b%23c%2Bd%25e%20f", url)
    }

    @Test
    fun authenticatedUrl_leavesSimpleTokenUnchanged() {
        val url = authenticatedUrl("https://panel.example", "/api/briefing", "simpletoken123")

        assertEquals("https://panel.example/api/briefing?token=simpletoken123", url)
    }

    @Test
    fun taskId_withPunctuation_isPathEncoded() {
        // CompleteTaskAction builds its URL as
        // authenticatedUrl(baseUrl, "/api/tasks/${Uri.encode(taskId)}/complete", token) --
        // confirms Uri.encode alone (the piece this test can isolate) escapes
        // a path-breaking character in a task id that isn't guaranteed to be
        // safely path-embeddable as-is.
        val encoded = Uri.encode("abc/def?g=h")

        assertEquals("abc%2Fdef%3Fg%3Dh", encoded)
    }
}
