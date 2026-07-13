package com.lifeops.briefing

import android.util.Log
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Minimal shared HttpURLConnection request/response cycle for the widget's
 * one-off GET/POST calls -- not worth pulling in a full HTTP client for. */
internal fun httpRequest(
    url: String,
    method: String,
    body: String? = null,
    connectTimeoutMs: Int = 10_000,
    readTimeoutMs: Int = 15_000,
    requireExactCode: Int? = null,
): String {
    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = method
        if (body != null) doOutput = true
        connectTimeout = connectTimeoutMs
        readTimeout = readTimeoutMs
    }
    try {
        if (body != null) {
            connection.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = connection.responseCode
        val statusOk = if (requireExactCode != null) code == requireExactCode else code in 200..299
        if (!statusOk) {
            // Redact the query string (carries ?token=...) -- this message
            // ends up in the exception's own toString(), which Log.e(tag,
            // msg, throwable) logs in full even when the caller's own msg
            // string is careful not to include the token itself.
            throw IOException("Unexpected HTTP status $code from ${url.substringBefore('?')}")
        }
        return connection.inputStream.bufferedReader().use { it.readText() }
    } finally {
        connection.disconnect()
    }
}

/** Posts a signal to the public ntfy topic lifeops's runner.py polls for
 * phone->server signals (see ntfy.py/runner.py's ingest()) -- the shared
 * plumbing behind every ntfy-relay use (task completion, token
 * registration, push-received acks), so the "topic not configured" guard
 * and the actual POST live in exactly one place instead of being
 * copy-pasted per signal type. */
internal fun postNtfySignal(body: String) {
    if (BuildConfig.NTFY_SIGNAL_TOPIC.isBlank()) {
        throw IOException("ntfy signal topic is not configured in local.properties")
    }
    httpRequest(
        url = "https://ntfy.sh/${BuildConfig.NTFY_SIGNAL_TOPIC}",
        method = "POST",
        body = body,
    )
}

/** Best-effort receipt confirmation for a persisted FCM push -- see
 * runner.py's _push_with_ack. Shared by BriefingPersistWorker and
 * NextTasksPersistWorker so the null-check/try-catch/log shape lives in
 * one place instead of being copy-pasted per worker. Never throws: a
 * failed ack just means the server retries the same content next tick
 * (the correct fallback), so it must not fail an otherwise-successful
 * persist. No-ops if [version] is null (an in-flight push sent before the
 * server started tagging messages with one). */
internal fun postPushAck(msgType: String, version: String?, tag: String) {
    version ?: return
    try {
        postNtfySignal("ack:$msgType:$version")
    } catch (e: IOException) {
        Log.w(tag, "failed to post ack for $msgType $version", e)
    }
}
