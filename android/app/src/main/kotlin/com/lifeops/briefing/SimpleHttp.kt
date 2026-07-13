package com.lifeops.briefing

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
