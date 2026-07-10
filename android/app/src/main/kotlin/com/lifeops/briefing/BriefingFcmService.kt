package com.lifeops.briefing

import android.util.Log
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.lifeops.briefing.data.BriefingState
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

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
        BriefingSyncWorker.enqueuePayload(applicationContext, payload)
    }

    /** Called once on first install/token refresh, and again whenever the
     * token rotates. Best-effort: if the panel URL isn't configured yet, this
     * silently no-ops -- SettingsActivity's Save also (re-)registers the
     * current token, which covers the common case where Settings gets
     * configured after this fires. */
    override fun onNewToken(token: String) {
        BriefingSyncWorker.enqueue(applicationContext)
    }
}

/** Registers the current device token with the single-user LifeOps server. */
internal fun registerToken(context: android.content.Context, token: String): Boolean {
    val baseUrl = WidgetConfigStore.getBaseUrl(context) ?: return false
    val authToken = WidgetConfigStore.getToken(context) ?: return false
    try {
        val url = URL(authenticatedUrl(baseUrl, "/api/register-fcm-token", authToken))
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            connectTimeout = 10_000
            readTimeout = 15_000
        }
        try {
            val body = JSONObject().put("fcm_token", token).toString()
            connection.outputStream.use { it.write(body.toByteArray()) }
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                Log.e("BriefingFcmService", "register-fcm-token returned HTTP $code")
                return false
            }
            return true
        } finally {
            connection.disconnect()
        }
    } catch (e: IOException) {
        Log.e("BriefingFcmService", "error registering FCM token at $baseUrl", e)
        return false
    }
}

internal suspend fun persistBriefingForAllInstances(context: android.content.Context, state: BriefingState) {
    val manager = GlanceAppWidgetManager(context)
    for (glanceId in manager.getGlanceIds(BriefingWidget::class.java)) {
        updateAppWidgetState(context, glanceId) { prefs ->
            prefs[WidgetKeys.BRIEFING_JSON] = state.toJson()
            state.fetchedAtEpochMillis?.let { prefs[WidgetKeys.LAST_FETCHED_AT] = it }
        }
        BriefingWidget().update(context, glanceId)
    }
}
