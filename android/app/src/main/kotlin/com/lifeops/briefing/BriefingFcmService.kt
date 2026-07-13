package com.lifeops.briefing

import android.content.Context
import android.util.Log
import androidx.glance.GlanceId
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.work.BackoffPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.lifeops.briefing.data.BriefingState
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/**
 * Receives the daily briefing via Firebase Cloud Messaging -- replaces the
 * earlier ntfy-broadcast design (BriefingReceiver, removed), which turned out
 * to be unreliable: a manifest-registered BroadcastReceiver for an implicit,
 * third-party broadcast cannot wake a stopped app on modern Android. FCM
 * messages go through Google Play Services' privileged, OS-exempted delivery
 * path (the same one every app with reliable notifications uses), so this
 * actually wakes the app reliably regardless of whether it's running.
 *
 * The server sends a DATA-ONLY message (not a "notification" message) with a
 * "type" field ("briefing" or "next_tasks") and a "payload" field carrying
 * that type's JSON body -- so onMessageReceived fires unconditionally
 * (foreground or background) rather than only being auto-displayed by the
 * system when the app isn't running, and can dispatch to the right persist
 * worker instead of assuming every push is a briefing. next_tasks pushes are
 * the Tailscale-independent counterpart to NextTasksRefreshWorker's periodic
 * direct pull (fcm.py's send_next_tasks docstring has the full reasoning).
 */
private const val REGISTER_TOKEN_MIN_BACKOFF_MS = 30_000L

class BriefingFcmService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        val payload = message.data["payload"] ?: return
        // "version" travels alongside the payload so the persist worker can
        // echo it back as an ack once persisted -- see runner.py's
        // _push_with_ack. Missing "version" (an old in-flight push sent
        // before the server started tagging messages) just means no ack
        // gets posted for it; the server's own state file would already
        // have nothing to match it against either.
        val version = message.data["version"]
        // Missing "type" defaults to "briefing" for compatibility with any
        // in-flight push sent before the server started tagging messages.
        val request = when (message.data["type"] ?: "briefing") {
            "next_tasks" -> OneTimeWorkRequestBuilder<NextTasksPersistWorker>()
                .setInputData(workDataOf(
                    NextTasksPersistWorker.KEY_PAYLOAD to payload,
                    NextTasksPersistWorker.KEY_VERSION to version,
                ))
                .build()
            else -> OneTimeWorkRequestBuilder<BriefingPersistWorker>()
                .setInputData(workDataOf(
                    BriefingPersistWorker.KEY_PAYLOAD to payload,
                    BriefingPersistWorker.KEY_VERSION to version,
                ))
                .build()
        }
        // Enqueue, don't launch-and-hope: see BriefingPersistWorker's
        // doc comment for why a bare coroutine here can silently lose the
        // push if the OS reclaims the process before the write completes.
        WorkManager.getInstance(applicationContext).enqueue(request)
    }

    /** Called once on first install/token refresh, and again whenever the
     * token rotates. registerToken tries the direct panel call and falls
     * back to an ntfy signal, so this doesn't strictly need the panel URL
     * configured yet to eventually succeed -- SettingsActivity's Save also
     * (re-)registers the current token regardless, covering the common case
     * where Settings gets configured after this fires. Enqueued via
     * WorkManager, with retry-on-failure, for the same guaranteed-execution
     * reason as BriefingPersistWorker. */
    override fun onNewToken(token: String) {
        val request = OneTimeWorkRequestBuilder<RegisterTokenWorker>()
            .setInputData(workDataOf(RegisterTokenWorker.KEY_TOKEN to token))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, REGISTER_TOKEN_MIN_BACKOFF_MS, TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(applicationContext).enqueue(request)
    }

}

/** Shared by BriefingFcmService's push path and NextTasksRefreshWorker's
 * periodic pull so a fresh BriefingState is persisted identically from
 * either path. */
internal suspend fun persistBriefingForInstance(context: Context, glanceId: GlanceId, state: BriefingState) {
    updateAppWidgetState(context, glanceId) { prefs ->
        prefs[WidgetKeys.BRIEFING_JSON] = state.toJson()
        state.fetchedAtEpochMillis?.let { prefs[WidgetKeys.LAST_FETCHED_AT] = it }
    }
    BriefingWidget().update(context, glanceId)
}

/** Shared by both onNewToken and SettingsActivity's Save button so a fresh
 * FCM token gets to the server the same way from either path. Tries the
 * direct, Tailscale-gated call first; falls back to a `token:<value>` ntfy
 * signal (see runner.py's ingest()) when that fails for any reason, same
 * hybrid shape as CompleteTaskAction -- token registration shouldn't
 * silently no-op just because the phone isn't on the tailnet at install or
 * rotation time. Returns true if EITHER path succeeded, so
 * [RegisterTokenWorker] can tell WorkManager to retry (with backoff) when
 * both fail -- e.g. the phone is fully offline, not just off-tailnet --
 * instead of silently dropping the registration for good. */
internal fun registerToken(context: android.content.Context, token: String): Boolean {
    val baseUrl = WidgetConfigStore.getBaseUrl(context)
    val authToken = WidgetConfigStore.getToken(context)

    if (baseUrl != null && authToken != null) {
        try {
            registerTokenDirect(baseUrl, authToken, token)
            return true
        } catch (e: IOException) {
            Log.w("BriefingFcmService", "direct token registration failed, falling back to ntfy signal", e)
        }
    }

    return try {
        postNtfySignal("token:$token")
        true
    } catch (e: IOException) {
        Log.e("BriefingFcmService", "error posting token registration signal", e)
        false
    }
}

private fun registerTokenDirect(baseUrl: String, authToken: String, token: String) {
    val url = URL("$baseUrl/api/register-fcm-token?token=$authToken")
    val connection = (url.openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        doOutput = true
        setRequestProperty("Content-Type", "application/json")
        connectTimeout = 10_000
        readTimeout = 15_000
    }
    try {
        // JSONObject, not raw string interpolation -- an unescaped
        // token containing a quote or backslash would otherwise produce
        // malformed JSON the server rejects with a silent 400.
        val body = JSONObject().put("fcm_token", token).toString()
        connection.outputStream.use { it.write(body.toByteArray()) }
        val code = connection.responseCode
        if (code != HttpURLConnection.HTTP_OK) {
            throw IOException("Unexpected HTTP status $code from register-fcm-token")
        }
    } finally {
        connection.disconnect()
    }
}

