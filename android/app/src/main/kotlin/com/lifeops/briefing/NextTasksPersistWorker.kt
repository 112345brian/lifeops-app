package com.lifeops.briefing

import android.content.Context
import android.util.Log
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.lifeops.briefing.data.NextTasksState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
        Result.success()
    }

    companion object {
        const val KEY_PAYLOAD = "payload"
        private const val TAG = "NextTasksPersistWorker"
    }
}
