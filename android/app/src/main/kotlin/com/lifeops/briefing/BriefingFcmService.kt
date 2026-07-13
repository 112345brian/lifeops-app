package com.lifeops.briefing

import android.content.Context
import android.util.Log
import androidx.glance.GlanceId
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.lifeops.briefing.data.BriefingState
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
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
 * single "payload" field -- the same {date, text, facts} JSON shape
 * /api/briefing already returns -- so onMessageReceived fires unconditionally
 * (foreground or background) rather than only being auto-displayed by the
 * system when the app isn't running.
 */
class BriefingFcmService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        val payload = message.data["payload"] ?: return
        // Enqueue, don't launch-and-hope: see BriefingPersistWorker's
        // doc comment for why a bare coroutine here can silently lose the
        // push if the OS reclaims the process before the write completes.
        val request = OneTimeWorkRequestBuilder<BriefingPersistWorker>()
            .setInputData(workDataOf(BriefingPersistWorker.KEY_PAYLOAD to payload))
            .build()
        WorkManager.getInstance(applicationContext).enqueue(request)
    }

    /** Called once on first install/token refresh, and again whenever the
     * token rotates. Best-effort: if the panel URL isn't configured yet, this
     * silently no-ops -- SettingsActivity's Save also (re-)registers the
     * current token, which covers the common case where Settings gets
     * configured after this fires. Enqueued via WorkManager for the same
     * guaranteed-execution reason as BriefingPersistWorker. */
    override fun onNewToken(token: String) {
        val request = OneTimeWorkRequestBuilder<RegisterTokenWorker>()
            .setInputData(workDataOf(RegisterTokenWorker.KEY_TOKEN to token))
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
 * FCM token gets to the server the same way from either path. */
internal fun registerToken(context: android.content.Context, token: String) {
    val baseUrl = WidgetConfigStore.getBaseUrl(context) ?: return
    val authToken = WidgetConfigStore.getToken(context) ?: return
    try {
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
                Log.e("BriefingFcmService", "register-fcm-token returned HTTP $code")
            }
        } finally {
            connection.disconnect()
        }
    } catch (e: IOException) {
        Log.e("BriefingFcmService", "error registering FCM token at $baseUrl", e)
    }
}
