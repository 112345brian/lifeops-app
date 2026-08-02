package com.lifeops.briefing

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.nio.charset.StandardCharsets
import javax.net.ssl.SSLException
import kotlin.math.pow

/**
 * On-device port of `lifeops/llm.py` -- direct HTTPS calls to the Anthropic
 * Messages API (`api.anthropic.com`), plain `HttpURLConnection` matching this
 * project's existing house style for hand-rolled REST clients (see
 * `FlowSavvyClient.kt`/`SimpleHttp.kt`/`YnabRefresh.kt`) rather than adding an
 * OkHttp or `anthropic-sdk-kotlin` dependency neither of which this project
 * has today.
 *
 * ## What actually got ported, and what did NOT
 *
 * `lifeops/llm.py`'s own docstring: "The ONLY place the LLM is used: bounded
 * judgment calls." Reading that file (and its call sites in `runner.py`)
 * directly, rather than assuming the 3-call premise this porting task started
 * from, turned up only **2** real LLM calls still wired server-side today:
 *
 * - [weeklyDigest] -- ports `llm.py`'s `weekly_digest(facts)` exactly (prompt,
 *   model, `max_tokens`). Called from `runner.py`'s `run_digest`, Sundays
 *   only. See `WeeklyDigest.kt` for the on-device Sunday-gated trigger.
 * - [categorizeUnknownPayee] -- ports `llm.py`'s `categorize_unknown(payee,
 *   amount, category_names)` exactly. Called from `runner.py:539-549`'s
 *   `run_ynab`, for novel/ambiguous transaction payees. **Not yet wired into
 *   any on-device call path** -- see this function's own kdoc for why.
 *
 * A **third** LLM call this porting task's brief described --  "daily
 * briefing text generation" -- does **NOT** exist in the current Python
 * source. `llm.py`'s own trailing comment says so explicitly: a
 * `daily_briefing()` function used to live there, synthesizing the morning
 * briefing text via the LLM, and was **deliberately retired 2026-07-15**
 * because "its only remaining job was rephrasing attention.compute()'s
 * already-plain-English headline in a slightly different voice... not worth
 * the cost, latency, or hallucination surface." `briefing_service.py`'s
 * `build()`/`compose_text()` -- the deterministic function that replaced it
 * -- confirms this: it is pure string formatting, no LLM call anywhere.
 * `BriefingState.kt`'s own `nudges` field kdoc independently documents the
 * same intent on the Android side: "so the widget can render them
 * deterministically without depending on an LLM call this compute tick
 * deliberately does not make."
 *
 * Given that, this port does NOT add a new on-device daily-briefing
 * narrative call -- doing so would silently reintroduce exactly the cost/
 * latency/hallucination surface a prior, documented decision removed, using
 * a prompt this port would have to invent from scratch (there is no current
 * source to port faithfully). [BriefingState.text] is therefore left
 * unpopulated by [LifeOpsComputeWorker] exactly as it already was before this
 * change -- see the comment at that exact call site in
 * `LifeOpsComputeWorker.kt`'s `applyComputeTickResult`. This is flagged
 * prominently in this change's own report as the primary judgment call for
 * the user to revisit, not a silent scope cut.
 */

/** Thrown when a request never reached the server -- mirrors
 * [FlowSavvyConnectionException]'s reasoning exactly (see that class's kdoc):
 * always safe to retry. */
class AnthropicConnectionException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Thrown when the server DID respond with a non-2xx status. Unlike
 * [FlowSavvyHttpException]'s asymmetric "only retry 429" policy (which exists
 * specifically to avoid double-submitting a non-idempotent FlowSavvy write),
 * the Anthropic Python SDK's own default retry behavior (`max_retries`, per
 * `lifeops/config.py`'s `LLM_MAX_RETRIES`) retries 429 AND >=500 -- a single
 * Messages API call has no equivalent "might have created a duplicate
 * resource" hazard, so this port matches the SDK's real default rather than
 * FlowSavvy's narrower carve-out. */
class AnthropicHttpException(
    val statusCode: Int,
    message: String,
    val retryAfterSeconds: Double? = null,
) : IOException(message)

/** `float(retry_after) if retry_after else None` -- same parsing as
 * `parseRetryAfterHeader` in FlowSavvyClient.kt (a distinct name here since
 * both top-level functions live in the same `com.lifeops.briefing` package). */
internal fun parseAnthropicRetryAfterHeader(value: String?): Double? = value?.toDoubleOrNull()

private const val ANTHROPIC_API_URL = "https://api.anthropic.com/v1/messages"
private const val ANTHROPIC_API_VERSION = "2023-06-01"
private const val RETRIES = 2 // mirrors lifeops/config.py's LLM_MAX_RETRIES default
private const val BACKOFF_SECONDS = 0.5
private const val CONNECT_TIMEOUT_MS = 30_000 // mirrors lifeops/config.py's LLM_TIMEOUT_SECONDS default (30s)
private const val READ_TIMEOUT_MS = 30_000

/** Retry wrapper mirroring `withFlowSavvyRetry`'s shape/backoff schedule
 * (0.5s, then 1.0s for `RETRIES = 2`), but retrying 429 AND >=500 -- see
 * [AnthropicHttpException]'s kdoc for why the policy differs from
 * FlowSavvy's. [sleep] is injected purely for testability, same as
 * `withFlowSavvyRetry`. */
internal fun <T> withAnthropicRetry(sleep: (Long) -> Unit = { Thread.sleep(it) }, fn: () -> T): T {
    var last: IOException? = null
    for (attempt in 0..RETRIES) {
        try {
            return fn()
        } catch (e: AnthropicConnectionException) {
            last = e
            if (attempt < RETRIES) {
                sleep((BACKOFF_SECONDS * 2.0.pow(attempt) * 1000).toLong())
                continue
            }
            throw e
        } catch (e: AnthropicHttpException) {
            val retryable = e.statusCode == 429 || e.statusCode >= 500
            if (retryable && attempt < RETRIES) {
                last = e
                val delaySeconds = e.retryAfterSeconds ?: (BACKOFF_SECONDS * 2.0.pow(attempt))
                sleep((delaySeconds * 1000).toLong())
                continue
            }
            throw e
        }
    }
    throw last ?: IllegalStateException("withAnthropicRetry: no attempt ran")
}

/** Builds the flat Messages API request body: `{model, max_tokens,
 * messages: [{role: "user", content: prompt}]}` -- the exact shape every
 * `lifeops/llm.py` call site sends (a single user-turn prompt, no system
 * prompt, no tools). Pure and directly unit-testable without any network I/O. */
internal fun buildMessagesRequestBody(model: String, maxTokens: Int, prompt: String): String =
    JSONObject().apply {
        put("model", model)
        put("max_tokens", maxTokens)
        put(
            "messages",
            JSONArray().apply {
                put(JSONObject().apply { put("role", "user"); put("content", prompt) })
            },
        )
    }.toString()

/** Extracts the first `type: "text"` content block's `text` field from a
 * parsed Messages API response, or null if there is none (e.g. an
 * unexpected response shape, or every block is a non-text type). Pure and
 * directly unit-testable without any network I/O. */
internal fun extractResponseText(response: JSONObject): String? {
    val content = response.optJSONArray("content") ?: return null
    for (i in 0 until content.length()) {
        val block = content.optJSONObject(i) ?: continue
        if (block.optString("type") == "text") return block.optString("text")
    }
    return null
}

/**
 * Kotlin port of `lifeops/llm.py`'s `_c()`/`Anthropic(...)` client -- direct
 * HTTPS calls to `api.anthropic.com`'s Messages API, no SDK dependency. See
 * this file's top-level kdoc for what did/didn't get ported.
 */
class AnthropicClient(
    private val apiKey: String,
    /** Mirrors `lifeops/config.py`'s `JUDGE_MODEL` default -- a dated
     * snapshot, not a rolling alias, per that config's own comment: "eventually
     * deprecated... check the Anthropic model deprecation page periodically." */
    private val model: String = "claude-haiku-4-5-20251001",
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) {
    /** One raw Messages API call. Retries per [withAnthropicRetry]. Throws
     * [AnthropicConnectionException]/[AnthropicHttpException] on failure --
     * callers that want `llm.py`'s "never worth failing the actual call
     * over" posture (e.g. [categorizeUnknownPayee]) catch around this. */
    fun createMessage(prompt: String, maxTokens: Int): JSONObject = withAnthropicRetry(sleep) {
        val payload = buildMessagesRequestBody(model, maxTokens, prompt)
        JSONObject(rawRequest(payload))
    }

    private fun rawRequest(payload: String): String {
        val connection = (URL(ANTHROPIC_API_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("x-api-key", apiKey)
            setRequestProperty("anthropic-version", ANTHROPIC_API_VERSION)
            setRequestProperty("content-type", "application/json")
        }
        try {
            connection.outputStream.use { it.write(payload.toByteArray(StandardCharsets.UTF_8)) }
            val code = try {
                connection.responseCode
            } catch (e: SocketTimeoutException) {
                // Deliberately unwrapped, same judgment call as FlowSavvyClient.kt's
                // rawRequest: Python's requests.exceptions.Timeout is a distinct,
                // unretried exception class, not folded into ConnectionError.
                throw e
            } catch (e: ConnectException) {
                throw AnthropicConnectionException("connection failed calling Anthropic Messages API: ${e.message}", e)
            } catch (e: UnknownHostException) {
                throw AnthropicConnectionException("connection failed calling Anthropic Messages API: ${e.message}", e)
            } catch (e: NoRouteToHostException) {
                throw AnthropicConnectionException("connection failed calling Anthropic Messages API: ${e.message}", e)
            } catch (e: SSLException) {
                throw AnthropicConnectionException("connection failed calling Anthropic Messages API: ${e.message}", e)
            } catch (e: IOException) {
                throw AnthropicConnectionException("connection failed calling Anthropic Messages API: ${e.message}", e)
            }
            if (code !in 200..299) {
                val retryAfter = if (code == 429) parseAnthropicRetryAfterHeader(connection.getHeaderField("Retry-After")) else null
                val errorBody = try {
                    (connection.errorStream ?: connection.inputStream)?.bufferedReader()?.use { it.readText() }
                } catch (e: IOException) {
                    null
                }
                val suffix = if (!errorBody.isNullOrBlank()) ": $errorBody" else ""
                throw AnthropicHttpException(code, "HTTP $code from Anthropic Messages API$suffix", retryAfter)
            }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Ports `llm.py`'s `weekly_digest(facts)` exactly: same prompt template,
     * same `max_tokens` (220), returns the raw trimmed response text. Called
     * from `WeeklyDigest.kt`'s Sunday-gated tick. Throws on failure (unlike
     * [categorizeUnknownPayee]) -- the Python source has no
     * `except Exception` guard around this call either (`run_digest` wraps
     * the whole call in its own `try/except`, logging and skipping this
     * week rather than silently returning a blank digest).
     */
    fun weeklyDigest(facts: JSONObject): String {
        val prompt = "You are Brian's blunt but supportive accountability coach. From the " +
            "stats, write 2-3 sentences: what he actually did this week, the ONE " +
            "pattern worth fixing, and a real push for next week. Be direct and a " +
            "little funny, never corny or preachy.\nStats: " + facts.toString()
        val response = createMessage(prompt, maxTokens = 220)
        return extractResponseText(response)?.trim() ?: ""
    }

    /**
     * Ports `llm.py`'s `categorize_unknown(payee, amount, category_names)`
     * exactly: same prompt template, same "find the first `{`/last `}` in
     * the response and parse that as JSON" tolerance for prose wrapping the
     * JSON object, same "category not in the allowed list -> null" guard,
     * same bare "any failure -> null" posture (`except Exception: return None`
     * in the Python source) -- a categorization the model can't confidently
     * make should silently fall through to a human review queue, not throw
     * and break the run.
     *
     * ## Judgment call: this is the genuinely borderline one
     *
     * `runner.py:539-559`'s `run_ynab` feeds this function's return value
     * straight into `out["categorize"]`/`out["approve"]`, which then get
     * written to YNAB via `yn.update_transactions(updates)` -- an automatic
     * category write (and, for small amounts, an automatic approval) with NO
     * human step in between. That makes this the one call among the three
     * this task considered that is plausibly closer to a "financial write"
     * than "background automation" -- more than the weekly digest (pure
     * narration, no side effect) or the retired daily-briefing idea (same).
     *
     * Per the locked-in product decision (biometric gating applies to
     * user-INITIATED writes/spends, never background/unattended automation),
     * this call is implemented UNGATED by default -- it only ever runs from
     * the same kind of background tick the rest of `run_ynab`'s categorize/
     * approve/hold/cover logic already runs from unattended, server-side,
     * today. But this is flagged here explicitly as a real judgment call the
     * user may want to revisit (e.g. requiring a one-time manual review queue
     * for LLM-categorized transactions above a threshold, rather than
     * auto-approving them) -- not a case where "background automation" is as
     * clear-cut a justification as it is for the digest/briefing calls.
     *
     * Wired into `YnabWrite.kt`'s on-device read-decide-write pipeline
     * (`resolveNovelPayees`/`runYnabWriteTickIfConfigured`), called from
     * `LifeOpsComputeWorker`'s self-gated once/day YNAB write tick -- see
     * that file's top-level kdoc for the full fetch/decide/write flow and
     * its "Retry-safety" section for why no additional retry guard was
     * needed around the resulting PATCH calls.
     */
    fun categorizeUnknownPayee(payee: String, amount: Double, categoryNames: List<String>): String? {
        val allowedJson = JSONArray(categoryNames).toString()
        val prompt = "You categorize one bank transaction for a personal budget. " +
            "Payee: ${JSONObject.quote(payee)}. Amount: ${"%.2f".format(amount)}. " +
            "Allowed categories: $allowedJson. " +
            "Reply with ONLY a JSON object {\"category\": \"<one of the allowed names, " +
            "or null if genuinely unclear>\"}. Do not invent categories."
        return try {
            val response = createMessage(prompt, maxTokens = 100)
            val text = extractResponseText(response)?.trim() ?: return null
            parseCategorizeResponse(text, categoryNames)
        } catch (e: Exception) {
            null
        }
    }
}

/** Pulled out of [AnthropicClient.categorizeUnknownPayee] so the "tolerate
 * prose around the JSON" / "reject a category not in the allowed list"
 * parsing logic is directly unit-testable without a network call -- ports
 * `categorize_unknown`'s `txt[txt.find("{"): txt.rfind("}") + 1]` +
 * `json.loads(txt).get("category")` + `cat if cat in category_names else None`
 * exactly. */
internal fun parseCategorizeResponse(text: String, categoryNames: List<String>): String? {
    val start = text.indexOf('{')
    val end = text.lastIndexOf('}')
    if (start == -1 || end == -1 || end < start) return null
    return try {
        val json = JSONObject(text.substring(start, end + 1))
        if (json.isNull("category")) return null
        val category = json.optString("category", "")
        category.takeIf { it in categoryNames }
    } catch (e: Exception) {
        null
    }
}
