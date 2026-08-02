package com.lifeops.briefing

import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException
import java.nio.charset.StandardCharsets
import javax.net.ssl.SSLException
import kotlin.math.pow

/**
 * Kotlin port of `lifeops/flowsavvy.py`'s `FlowSavvy` REST client -- named in
 * docs/lifeops_capability_todo.md's "On-Device Migration" section as "clean,
 * portable, stateless bearer-token REST clients" directly portable to
 * OkHttp/Retrofit (this port instead reuses `SimpleHttp.kt`'s plain
 * `HttpURLConnection` style, since the project has no OkHttp dependency and
 * `YnabRefresh.kt`/`SimpleHttp.kt` already establish that convention for this
 * codebase).
 *
 * NOT wired into any widget/worker/persistence path yet -- this is a
 * standalone fetch/write client the WorkManager-wiring task will consume,
 * same scoping as the other engine ports in this package.
 *
 * ## The one behavior this port MUST preserve exactly: asymmetric retries
 *
 * `flowsavvy.py`'s own header comment: "A transient TLS/TCP blip (connection
 * refused, reset, SSL handshake EOF) means the request never reached the
 * server -- retrying is safe regardless of verb. A response that came back
 * (even a 5xx) means the server DID see the request, so we do NOT retry
 * those here: blindly retrying a non-idempotent POST/PUT after an ambiguous
 * server-side response risks creating a duplicate task." Per
 * docs/lifeops_capability_todo.md: "a naive Kotlin retry-everything port
 * would recreate the duplicate-task bug this was built to prevent."
 *
 * This port keeps that asymmetry through two distinct exception types
 * ([FlowSavvyConnectionException] vs [FlowSavvyHttpException]) so the retry
 * decision is made on which one was thrown, not on a single catch-all:
 * - [FlowSavvyConnectionException]: the request never reached the server
 *   (`ConnectException`/`UnknownHostException`/`NoRouteToHostException`/
 *   `SSLException` while opening the connection or writing the request) --
 *   mirrors `requests.exceptions.ConnectionError`. Always safe to retry,
 *   including for POST/PUT.
 * - [FlowSavvyHttpException]: a response WAS received with a non-2xx status
 *   -- mirrors `requests.raise_for_status()`'s `HTTPError`. Retried ONLY for
 *   HTTP 429 (the server explicitly rejected the request before doing
 *   anything, per the Python comment), using the `Retry-After` header when
 *   present, exactly like `_with_retry`'s 429 branch. Every other non-2xx
 *   status (4xx/5xx) is thrown immediately, unretried -- this is the
 *   asymmetry the whole module exists to preserve.
 *
 * Judgment call, documented rather than silently made: `java.net.SocketTimeoutException`
 * is deliberately treated as neither of the above -- it propagates as a plain
 * `IOException`, unretried. Python's `requests` library models a timeout as
 * `requests.exceptions.Timeout`, a SEPARATE exception class from
 * `ConnectionError` that `_with_retry` does NOT catch, so a timeout is not
 * retried in the original either. Folding `SocketTimeoutException` into
 * [FlowSavvyConnectionException] would have been an easy, plausible-looking
 * mistake that actually widens the retry surface beyond what the Python
 * source does.
 *
 * ## Endpoint paths are copied verbatim, not re-derived
 *
 * Per `flowsavvy.py`'s own header comment ("ENDPOINT PATHS ARE INFERRED...")
 * and docs/lifeops_capability_todo.md ("verify before hardcoding into
 * Kotlin"), every path string below is copied character-for-character from
 * the Python source's method bodies -- no new endpoint was invented for this
 * port.
 *
 * ## Calendar fetch
 *
 * FlowSavvy IS the calendar source on the Python side: `gather.py`'s
 * calendar-facing reads (`today_events_input`, `spend_input`'s multi-calendar
 * sweep) all go through this same client's `list_calendars`/`get_schedule`/
 * `list_items(itemType="event", ...)` -- there is no separate Google
 * Calendar/ICS/CalDAV integration anywhere in `lifeops/` (confirmed by
 * grepping every `.py` file for those terms; the only "Google Calendar"
 * mentions are docstring provenance notes about where a recurring event
 * originally came from, not a live integration). [listCalendars],
 * [getSchedule], and `listItems(mapOf("itemType" to "event", ...))` below are
 * therefore the complete on-device calendar-fetch port -- no additional
 * module or OAuth flow is needed or was skipped.
 */

/**
 * Thrown when a request never reached the server -- the request-never-sent
 * transport failure the Python client's `ConnectionError` catch covers.
 * Always safe to retry, per this file's top-level kdoc.
 */
class FlowSavvyConnectionException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Thrown when the server DID respond, with a non-2xx status. [retryAfterSeconds]
 * is populated only when [statusCode] is 429 and the response carried a
 * numeric `Retry-After` header (mirrors `e.response.headers.get("Retry-After")`
 * in `_with_retry`'s 429 branch) -- `null` for every other status, and `null`
 * for a 429 with a missing/non-numeric header (Python falls back to the
 * exponential backoff in both of those cases; see [withFlowSavvyRetry]).
 */
class FlowSavvyHttpException(
    val statusCode: Int,
    message: String,
    val retryAfterSeconds: Double? = null,
) : IOException(message)

/** `float(retry_after) if retry_after else None`, with a `ValueError` (a
 * non-numeric header value) treated as "no usable value" rather than a
 * thrown error -- ports the Python `try/except ValueError` fallback exactly.
 * Pure and directly unit-testable without any network I/O. */
internal fun parseRetryAfterHeader(value: String?): Double? =
    value?.toDoubleOrNull()

/** `{k: (str(v).lower() if isinstance(v, bool) else v) for k, v in params.items()
 * if v is not None and v != ""}` -- ports `_get`'s (and by extension every
 * other method's) parameter-filtering/bool-lowering exactly. Pure and
 * directly unit-testable without any network I/O. */
internal fun buildQueryParams(params: Map<String, Any?>): Map<String, String> =
    params.filterValues { it != null && it != "" }
        .mapValues { (_, v) -> if (v is Boolean) v.toString().lowercase() else v.toString() }

private const val RETRIES = 2
private const val BACKOFF_SECONDS = 0.5

/**
 * Generic retry wrapper -- ports `_with_retry(fn)` exactly, including its
 * asymmetric policy (see this file's top-level kdoc) and its exponential
 * backoff schedule (`_BACKOFF * (2 ** attempt)`, attempts 0 and 1 for
 * `_RETRIES = 2`: 0.5s then 1.0s).
 *
 * [sleep] is injected (real callers default to `Thread.sleep`) purely so
 * tests can verify retry/backoff behavior without actually sleeping --
 * mirrors nothing in the Python source, which is a pure testing seam.
 */
internal fun <T> withFlowSavvyRetry(sleep: (Long) -> Unit = { Thread.sleep(it) }, fn: () -> T): T {
    var last: IOException? = null
    for (attempt in 0..RETRIES) {
        try {
            return fn()
        } catch (e: FlowSavvyConnectionException) {
            last = e
            if (attempt < RETRIES) {
                sleep((BACKOFF_SECONDS * 2.0.pow(attempt) * 1000).toLong())
                continue
            }
            throw e
        } catch (e: FlowSavvyHttpException) {
            if (e.statusCode == 429 && attempt < RETRIES) {
                last = e
                val delaySeconds = e.retryAfterSeconds ?: (BACKOFF_SECONDS * 2.0.pow(attempt))
                sleep((delaySeconds * 1000).toLong())
                continue
            }
            throw e
        }
    }
    throw last ?: IllegalStateException("withFlowSavvyRetry: no attempt ran")
}

/** Builds `$base$path` with a `?`-prefixed, URL-encoded query string appended
 * when [params] (after [buildQueryParams]'s filtering) is non-empty. */
private fun buildUrl(base: String, path: String, params: Map<String, Any?> = emptyMap()): String {
    val filtered = buildQueryParams(params)
    if (filtered.isEmpty()) return "$base$path"
    val query = filtered.entries.joinToString("&") { (k, v) ->
        "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
    }
    return "$base$path?$query"
}

/** Low-level request/response cycle. Throws [FlowSavvyConnectionException]
 * for a transport-level failure (never reached the server) and
 * [FlowSavvyHttpException] for a received non-2xx response -- see this
 * file's top-level kdoc for why the distinction matters. A plain
 * [SocketTimeoutException] is deliberately let through unwrapped (see kdoc). */
private fun rawRequest(
    url: String,
    method: String,
    body: String?,
    headers: Map<String, String>,
): String {
    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = method
        if (body != null) doOutput = true
        connectTimeout = 30_000
        readTimeout = 30_000
        headers.forEach { (k, v) -> setRequestProperty(k, v) }
    }
    try {
        if (body != null) {
            connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
        }
        val code = try {
            connection.responseCode
        } catch (e: SocketTimeoutException) {
            throw e
        } catch (e: ConnectException) {
            throw FlowSavvyConnectionException("connection failed calling $method $url: ${e.message}", e)
        } catch (e: UnknownHostException) {
            throw FlowSavvyConnectionException("connection failed calling $method $url: ${e.message}", e)
        } catch (e: NoRouteToHostException) {
            throw FlowSavvyConnectionException("connection failed calling $method $url: ${e.message}", e)
        } catch (e: SSLException) {
            throw FlowSavvyConnectionException("connection failed calling $method $url: ${e.message}", e)
        } catch (e: IOException) {
            // Any other transport-level IOException (e.g. "Connection
            // reset", "unexpected end of stream") before a response was
            // obtained -- still "never reached/finished with the server"
            // in spirit, so it gets the same safe-to-retry treatment as
            // requests.exceptions.ConnectionError.
            throw FlowSavvyConnectionException("connection failed calling $method $url: ${e.message}", e)
        }
        if (code !in 200..299) {
            val retryAfter = if (code == 429) parseRetryAfterHeader(connection.getHeaderField("Retry-After")) else null
            val errorBody = try {
                (connection.errorStream ?: connection.inputStream)?.bufferedReader()?.use { it.readText() }
            } catch (e: IOException) {
                null
            }
            val suffix = if (!errorBody.isNullOrBlank()) ": $errorBody" else ""
            throw FlowSavvyHttpException(code, "HTTP $code from $method $url$suffix", retryAfter)
        }
        return connection.inputStream.bufferedReader().use { it.readText() }
    } finally {
        connection.disconnect()
    }
}

/**
 * Kotlin port of `flowsavvy.py`'s `FlowSavvy` class. `baseUrl`/`token` are
 * plain constructor parameters (not read from any Android storage here) --
 * wiring them to `WidgetConfigStore`'s `EncryptedSharedPreferences` (the same
 * pattern `YNAB_TOKEN` already uses) is explicitly the WorkManager-wiring
 * task's job, not this port's.
 */
class FlowSavvyClient(
    baseUrl: String,
    private val token: String,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) {
    private val base = baseUrl.trimEnd('/')

    private val headers: Map<String, String>
        get() = mapOf(
            "Authorization" to "Bearer $token",
            "Content-Type" to "application/json",
        )

    private fun get(path: String, params: Map<String, Any?> = emptyMap()): JSONObject =
        withFlowSavvyRetry(sleep) {
            val text = rawRequest(buildUrl(base, path, params), "GET", null, headers)
            JSONObject(text)
        }

    private fun post(path: String, body: Map<String, Any?>? = null): JSONObject =
        withFlowSavvyRetry(sleep) {
            val payload = JSONObject((body ?: emptyMap<String, Any?>()) as Map<*, *>).toString()
            val text = rawRequest("$base$path", "POST", payload, headers)
            if (text.isBlank()) JSONObject() else JSONObject(text)
        }

    private fun put(path: String, body: Map<String, Any?>): JSONObject =
        withFlowSavvyRetry(sleep) {
            val payload = JSONObject(body as Map<*, *>).toString()
            val text = rawRequest("$base$path", "PUT", payload, headers)
            if (text.isBlank()) JSONObject() else JSONObject(text)
        }

    private fun delete(path: String, params: Map<String, Any?> = emptyMap()): JSONObject =
        withFlowSavvyRetry(sleep) {
            rawRequest(buildUrl(base, path, params), "DELETE", null, headers)
            JSONObject()
        }

    // --- reads (mirror connector: list_calendars / list_items / get_schedule) ---

    /** Ports `list_calendars()`. See this file's top-level kdoc "Calendar
     * fetch" section -- this IS the on-device calendar-source port. */
    fun listCalendars(): JSONObject = get("/calendars")

    /** Ports `list_lists()`. */
    fun listLists(): JSONObject = get("/lists")

    /** Ports `list_scheduling_hours()`. */
    fun listSchedulingHours(): JSONObject = get("/scheduling-hours")

    /** Ports `list_items(**params)`. */
    fun listItems(params: Map<String, Any?> = emptyMap()): JSONObject = get("/items", params)

    /** Ports `get_schedule(start, end)`. See this file's top-level kdoc
     * "Calendar fetch" section. */
    fun getSchedule(start: String, end: String): JSONObject =
        get("/schedule", mapOf("startDate" to start, "endDate" to end))

    /** Ports `get_month(month="current")`. */
    fun getMonth(month: String = "current"): JSONObject = get("/months/$month")

    // --- writes ---

    /** Ports `create_task(**body)`. */
    fun createTask(body: Map<String, Any?>): JSONObject = post("/tasks", body)

    /** Ports `update_task(task_id, **body)`. */
    fun updateTask(taskId: String, body: Map<String, Any?>): JSONObject = put("/tasks/$taskId", body)

    /** Ports `complete_task(task_id)`. */
    fun completeTask(taskId: String): JSONObject = post("/tasks/$taskId/complete")

    /** Ports `create_event(**body)`. */
    fun createEvent(body: Map<String, Any?>): JSONObject = post("/events", body)

    /** Ports `update_event(event_id, **body)`. */
    fun updateEvent(eventId: String, body: Map<String, Any?>): JSONObject = put("/events/$eventId", body)

    /** Ports `delete_item(item_id, **p)`. */
    fun deleteItem(itemId: String, params: Map<String, Any?> = emptyMap()): JSONObject =
        delete("/items/$itemId", params)

    /** Ports `recalculate(reschedule_past=False)`. */
    fun recalculate(reschedulePast: Boolean = false): JSONObject =
        post("/recalculate", mapOf("reschedulePastTasks" to reschedulePast))
}
