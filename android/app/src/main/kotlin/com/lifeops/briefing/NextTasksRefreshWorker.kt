package com.lifeops.briefing

import android.content.Context
import android.util.Log
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.lifeops.briefing.data.BriefingState
import com.lifeops.briefing.data.NextTasksState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Periodically pulls BOTH the "what's next" task list AND the current
 * briefing from the lifeops server, pushing each into every placed widget's
 * Glance state. As of the server pushing next-tasks over FCM too (runner.py's
 * push_next_tasks, on every tick), BOTH fields here are now self-heal
 * fallbacks for their FCM push counterparts (BriefingFcmService), not the
 * primary freshness path for either one -- and this is also the ONLY
 * Tailscale-dependent part of the widget's ambient (non-panel) operation
 * left; everything else (briefing, next-tasks, task completion, token
 * registration) now has a Tailscale-independent push/relay path, with this
 * pull only mattering when a push is missed (server down at that exact
 * moment, FCM token not registered yet, widget freshly placed/reinstalled,
 * phone off). Originally next-tasks was pull-only and briefing was push-only,
 * which silently drifted them out of sync with each other -- confirmed
 * 2026-07-12 as the root cause of "sometimes it just shows one old
 * sentence/nothing" -- so both fields going through the same push+pull-
 * fallback shape now is deliberate, not incidental.
 *
 * 15-minute staleness is fine as a fallback cadence for both fields, since
 * the push path still delivers near-instantly (and Tailscale-independently)
 * when it works -- this pull only matters once push has already failed.
 *
 * HTTP client choice: same reasoning as before -- one GET, no request body,
 * no need for a full HTTP client.
 */
class NextTasksRefreshWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val baseUrl = WidgetConfigStore.getBaseUrl(applicationContext)
        val token = WidgetConfigStore.getToken(applicationContext)

        if (baseUrl == null || token == null) {
            // Not configured yet -- nothing to do.
            return@withContext Result.success()
        }

        val nextTasksResult = refreshNextTasks(baseUrl, token)
        refreshBriefing(baseUrl, token) // best-effort; never overrides nextTasksResult
        nextTasksResult
    }

    private suspend fun refreshNextTasks(baseUrl: String, token: String): Result {
        val body: String
        try {
            body = fetchNextTasks(baseUrl, token)
        } catch (e: SocketTimeoutException) {
            Log.e(TAG, "timeout fetching $baseUrl/api/next-tasks", e)
            return Result.retry()
        } catch (e: IOException) {
            Log.e(TAG, "error fetching $baseUrl/api/next-tasks", e)
            return Result.retry()
        } catch (e: JSONException) {
            // Malformed body shouldn't happen per the server contract; don't
            // crash the worker or hammer a response shape that won't change.
            Log.e(TAG, "malformed next-tasks response body", e)
            return Result.success()
        }

        val state = NextTasksState.fromApiResponse(body, System.currentTimeMillis())
        applyToAllInstances(applicationContext, state)
        return Result.success()
    }

    /** Best-effort: a briefing-fetch failure shouldn't fail the whole worker
     * (next-tasks may have already succeeded) -- just log and let the next
     * 15-minute cycle retry naturally. */
    private suspend fun refreshBriefing(baseUrl: String, token: String) {
        val body = try {
            fetchBriefing(baseUrl, token) ?: return // 404 = no briefing generated yet today
        } catch (e: SocketTimeoutException) {
            Log.e(TAG, "timeout fetching $baseUrl/api/briefing", e)
            return
        } catch (e: IOException) {
            Log.e(TAG, "error fetching $baseUrl/api/briefing", e)
            return
        }
        val state = try {
            BriefingState.fromApiResponse(body, System.currentTimeMillis())
        } catch (e: JSONException) {
            Log.e(TAG, "malformed briefing response body", e)
            return
        }
        val manager = GlanceAppWidgetManager(applicationContext)
        for (glanceId in manager.getGlanceIds(BriefingWidget::class.java)) {
            persistBriefingForInstance(applicationContext, glanceId, state)
        }
    }

    private fun fetchNextTasks(baseUrl: String, token: String): String {
        return httpRequest(
            url = authenticatedUrl(baseUrl, "/api/next-tasks", token),
            method = "GET",
            connectTimeoutMs = CONNECT_TIMEOUT_MS,
            readTimeoutMs = READ_TIMEOUT_MS,
            requireExactCode = HttpURLConnection.HTTP_OK,
        )
    }

    /** Null return means "no briefing generated yet today" (server's 404),
     * which is a normal, expected state -- not an error. */
    private fun fetchBriefing(baseUrl: String, token: String): String? {
        val url = URL(authenticatedUrl(baseUrl, "/api/briefing", token))
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
        }
        try {
            val code = connection.responseCode
            if (code == 404) return null
            if (code != HttpURLConnection.HTTP_OK) {
                // Redact the query string (carries ?token=...) -- Log.e(tag,
                // msg, throwable) logs this exception's own message in full
                // even when the catch site's own log string omits the token.
                throw IOException("Unexpected HTTP status $code from ${url.toString().substringBefore('?')}")
            }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun applyToAllInstances(context: Context, state: NextTasksState) {
        val manager = GlanceAppWidgetManager(context)
        for (glanceId in manager.getGlanceIds(BriefingWidget::class.java)) {
            persistNextTasksForInstance(context, glanceId, state)
        }
    }

    companion object {
        private const val TAG = "NextTasksRefreshWorker"
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 15_000
        private const val MIN_BACKOFF_MS = 30_000L
        const val UNIQUE_PERIODIC_WORK_NAME = "next_tasks_refresh_periodic"

        /** Schedules the recurring 15-minute pull. Safe to call multiple
         * times: ExistingPeriodicWorkPolicy.KEEP means a repeat call (e.g.
         * onEnabled firing again) won't reset an already-scheduled request's
         * interval/backoff. */
        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<NextTasksRefreshWorker>(15, TimeUnit.MINUTES)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, MIN_BACKOFF_MS, TimeUnit.MILLISECONDS)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}

/** Shared by both this worker and CompleteTaskAction so a fresh NextTasksState
 * is persisted identically from either path. Filters out any task the user
 * just completed locally (see PendingRemovals.kt) that the server hasn't
 * caught up to reflecting yet -- otherwise this full overwrite would
 * silently resurrect it in the widget for however long remains until the
 * server-side ingest() cycle actually completes it. */
internal suspend fun persistNextTasksForInstance(context: Context, glanceId: GlanceId, state: NextTasksState) {
    updateAppWidgetState(context, glanceId) { prefs ->
        val now = System.currentTimeMillis()
        val pending = PendingRemovals.readActive(prefs, now)
        val filtered = if (pending.isEmpty()) state else state.copy(tasks = state.tasks.filterNot { it.id in pending })
        prefs[WidgetKeys.NEXT_TASKS_JSON] = filtered.toJson()
    }
    BriefingWidget().update(context, glanceId)
}
