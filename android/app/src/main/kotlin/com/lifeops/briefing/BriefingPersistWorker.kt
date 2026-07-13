package com.lifeops.briefing

import android.content.Context
import android.util.Log
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.lifeops.briefing.data.BriefingState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import org.json.JSONException

/**
 * Does the actual persist for an FCM-delivered briefing payload, run as a
 * WorkManager job rather than a bare coroutine launched directly from
 * [BriefingFcmService.onMessageReceived]. FCM's contract only guarantees the
 * app gets to run long enough to call `onMessageReceived` -- background work
 * merely *started* inside it (a `CoroutineScope(...).launch { }` with no
 * lifecycle tie) can get cancelled mid-write if the OS reclaims the process
 * under Doze/App Standby before the DataStore write completes, silently
 * losing that push. Enqueuing through WorkManager gives the write an actual
 * guaranteed execution window instead.
 */
class BriefingPersistWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val payload = inputData.getString(KEY_PAYLOAD) ?: return@withContext Result.failure()
        val state = try {
            BriefingState.fromApiResponse(payload, System.currentTimeMillis())
        } catch (e: JSONException) {
            Log.e(TAG, "malformed FCM briefing payload", e)
            return@withContext Result.failure()
        }
        val manager = GlanceAppWidgetManager(applicationContext)
        for (glanceId in manager.getGlanceIds(BriefingWidget::class.java)) {
            persistBriefingForInstance(applicationContext, glanceId, state)
        }
        // Confirm receipt so the server knows this push actually landed
        // (see runner.py's _push_with_ack) instead of just trusting its own
        // send() call. Best-effort: a failed ack just means the server
        // retries this same content next tick, which is exactly the
        // correct fallback -- must not fail an otherwise-successful persist.
        inputData.getString(KEY_VERSION)?.let { version ->
            try {
                postNtfySignal("ack:briefing:$version")
            } catch (e: IOException) {
                Log.w(TAG, "failed to post ack for briefing $version", e)
            }
        }
        Result.success()
    }

    companion object {
        const val KEY_PAYLOAD = "payload"
        const val KEY_VERSION = "version"
        private const val TAG = "BriefingPersistWorker"
    }
}
