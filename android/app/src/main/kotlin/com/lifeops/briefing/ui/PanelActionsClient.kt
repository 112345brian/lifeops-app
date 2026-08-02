package com.lifeops.briefing.ui

import android.content.Context
import android.net.Uri
import com.lifeops.briefing.WidgetConfigStore
import com.lifeops.briefing.authenticatedUrl
import com.lifeops.briefing.httpRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection

/** Thrown when a quick action can't run because the panel base URL/auth
 * token (Settings screen) isn't configured yet. */
class PanelNotConfiguredException : IOException("Panel URL/token is not configured (see Settings)")

/**
 * Direct Kotlin callers for the panel's small JSON Actions API
 * (`lifeops/web.py`) -- `POST /api/gym/log`, `/api/gym/skip`,
 * `/api/schedule/block-day`, `/api/domains/{name}/run` -- backing the Today
 * screen's quick actions (docs/lifeops_capability_todo.md's "Full App Home"
 * section: "run catchup, log gym, skip gym, block today/tomorrow"). Per
 * that doc's own "Actions API" note, the backend routes have existed for a
 * while but only `/api/tasks/{id}/complete` had an Android caller before
 * this file -- "not a backend gap, a client-UI gap."
 *
 * TODO: gate every one of these once BiometricGate.kt lands -- each is a
 * user-initiated write action (the exact category that in-flight work is
 * meant to cover); until then these fire directly with no gate, per this
 * task's brief ("don't block on it... leave a clear TODO").
 */
object PanelActionsClient {
    private fun requireConfig(context: Context): Pair<String, String> {
        val baseUrl = WidgetConfigStore.getBaseUrl(context) ?: throw PanelNotConfiguredException()
        val token = WidgetConfigStore.getToken(context) ?: throw PanelNotConfiguredException()
        return baseUrl to token
    }

    private suspend fun post(context: Context, path: String, body: String? = null): JSONObject =
        withContext(Dispatchers.IO) {
            val (baseUrl, token) = requireConfig(context)
            val raw = httpRequest(
                url = authenticatedUrl(baseUrl, path, token),
                method = "POST",
                body = body,
                requireExactCode = HttpURLConnection.HTTP_OK,
                headers = if (body != null) mapOf("Content-Type" to "application/json") else emptyMap(),
            )
            JSONObject(raw)
        }

    /** `POST /api/gym/log` -- logs a same-day gym session (quick-action
     * equivalent of ticking today on the panel's gym calendar). */
    suspend fun logGym(context: Context): JSONObject = post(context, "/api/gym/log")

    /** `POST /api/gym/skip` -- logs today as a deliberate skip, not a missed
     * session, and re-plans immediately. */
    suspend fun skipGym(context: Context): JSONObject = post(context, "/api/gym/skip")

    /** `POST /api/schedule/block-day` with `{"date": "YYYY-MM-DD"}` -- marks
     * a whole day unavailable for scheduling. [date] must already be an
     * ISO-8601 calendar date; the server 400s on anything else. */
    suspend fun blockDay(context: Context, date: String): JSONObject =
        post(context, "/api/schedule/block-day", body = JSONObject().put("date", date).toString())

    /** `POST /api/domains/{name}/run` -- triggers one domain out-of-cycle.
     * "Run catchup" is this call with `name = "catchup"`; the same call also
     * backs any future single-domain "run now" action. */
    suspend fun runDomain(context: Context, name: String): JSONObject =
        post(context, "/api/domains/${Uri.encode(name)}/run")

    suspend fun runCatchup(context: Context): JSONObject = runDomain(context, "catchup")
}
