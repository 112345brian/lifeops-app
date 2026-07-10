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
 * Periodically pulls the "what's next" task list from the lifeops server and
 * pushes it into every placed widget's Glance state. Unlike the briefing
 * (push-only via FCM), this is a real periodic pull: the user confirmed
 * 15-minute staleness is fine here since completing 3 tasks in under 15
 * minutes isn't realistic, and this is scoped narrowly to just this one
 * field rather than the whole widget.
 *
 * HTTP client choice: one GET, no request body, no need for a full HTTP
 * client.
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

        val body: String
        try {
            body = fetchNextTasks(baseUrl, token)
        } catch (e: SocketTimeoutException) {
            Log.e(TAG, "timeout fetching $baseUrl/api/next-tasks", e)
            return@withContext Result.retry()
        } catch (e: IOException) {
            Log.e(TAG, "error fetching $baseUrl/api/next-tasks", e)
            return@withContext Result.retry()
        } catch (e: JSONException) {
            // Malformed body shouldn't happen per the server contract; don't
            // crash the worker or hammer a response shape that won't change.
            Log.e(TAG, "malformed next-tasks response body", e)
            return@withContext Result.success()
        }

        val state = NextTasksState.fromApiResponse(body, System.currentTimeMillis())
        applyToAllInstances(applicationContext, state)
        Result.success()
    }

    private fun fetchNextTasks(baseUrl: String, token: String): String {
        val url = URL(authenticatedUrl(baseUrl, "/api/next-tasks", token))
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
        }
        try {
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                throw IOException("Unexpected HTTP status $code from $url")
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
 * is persisted identically from either path. */
internal suspend fun persistNextTasksForInstance(context: Context, glanceId: GlanceId, state: NextTasksState) {
    updateAppWidgetState(context, glanceId) { prefs ->
        prefs[WidgetKeys.NEXT_TASKS_JSON] = state.toJson()
    }
    BriefingWidget().update(context, glanceId)
}
