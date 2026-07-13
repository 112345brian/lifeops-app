package com.lifeops.briefing

import android.content.Context
import android.util.Log
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.lifeops.briefing.data.NextTasksState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import org.json.JSONException

/**
 * Does the actual persist for an FCM-delivered next-tasks payload -- the
 * Tailscale-independent counterpart to NextTasksRefreshWorker's periodic
 * direct pull, which stays in place as a self-heal fallback for the rare
 * dropped push. Same guaranteed-execution reasoning as
 * [BriefingPersistWorker]: WorkManager, not a bare coroutine launched
 * straight from [BriefingFcmService.onMessageReceived].
 */
class NextTasksPersistWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val payload = inputData.getString(KEY_PAYLOAD) ?: return@withContext Result.failure()
        val state = try {
            NextTasksState.fromApiResponse(payload, System.currentTimeMillis())
        } catch (e: JSONException) {
            Log.e(TAG, "malformed FCM next-tasks payload", e)
            return@withContext Result.failure()
        }
        val manager = GlanceAppWidgetManager(applicationContext)
        for (glanceId in manager.getGlanceIds(BriefingWidget::class.java)) {
            // Shared with NextTasksRefreshWorker's periodic pull so a fresh
            // NextTasksState is persisted identically (including the
            // PendingRemovals filtering) from either path.
            persistNextTasksForInstance(applicationContext, glanceId, state)
        }
        // Confirm receipt so the server knows this push actually landed
        // (see runner.py's _push_with_ack) instead of just trusting its own
        // send() call. Best-effort: a failed ack just means the server
        // retries this same content next tick, which is exactly the
        // correct fallback -- must not fail an otherwise-successful persist.
        inputData.getString(KEY_VERSION)?.let { version ->
            try {
                postNtfySignal("ack:next_tasks:$version")
            } catch (e: IOException) {
                Log.w(TAG, "failed to post ack for next_tasks $version", e)
            }
        }
        Result.success()
    }

    companion object {
        const val KEY_PAYLOAD = "payload"
        const val KEY_VERSION = "version"
        private const val TAG = "NextTasksPersistWorker"
    }
}
