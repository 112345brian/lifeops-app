package com.lifeops.briefing

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.firebase.messaging.FirebaseMessaging
import com.lifeops.briefing.data.BriefingState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONException
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Completes setup work that must survive SettingsActivity closing: registers
 * the current FCM token and pulls today's briefing so a newly placed widget
 * does not remain empty until the next daily push.
 */
class BriefingSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        inputData.getString(KEY_PUSH_PAYLOAD)?.let { payload ->
            return@withContext try {
                val state = BriefingState.fromApiResponse(payload, System.currentTimeMillis())
                persistBriefingForAllInstances(applicationContext, state)
                Result.success()
            } catch (e: JSONException) {
                Log.e(TAG, "malformed FCM briefing payload", e)
                Result.failure()
            }
        }

        val baseUrl = WidgetConfigStore.getBaseUrl(applicationContext)
            ?: return@withContext Result.success()
        val authToken = WidgetConfigStore.getToken(applicationContext)
            ?: return@withContext Result.success()

        try {
            val body = fetchCurrentBriefing(baseUrl, authToken)
            if (body != null) {
                val state = BriefingState.fromApiResponse(body, System.currentTimeMillis())
                persistBriefingForAllInstances(applicationContext, state)
            }

            val fcmToken = FirebaseMessaging.getInstance().token.await()
            if (registerToken(applicationContext, fcmToken)) Result.success() else Result.retry()
        } catch (e: CancellationException) {
            throw e
        } catch (e: SocketTimeoutException) {
            Log.e(TAG, "timeout syncing briefing", e)
            Result.retry()
        } catch (e: IOException) {
            Log.e(TAG, "error syncing briefing", e)
            Result.retry()
        } catch (e: JSONException) {
            Log.e(TAG, "malformed briefing response body", e)
            Result.failure()
        } catch (e: Exception) {
            Log.e(TAG, "unexpected briefing sync error", e)
            Result.retry()
        }
    }

    private fun fetchCurrentBriefing(baseUrl: String, token: String): String? {
        val url = URL(authenticatedUrl(baseUrl, "/api/briefing", token))
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
        }
        try {
            return when (val code = connection.responseCode) {
                HttpURLConnection.HTTP_OK ->
                    connection.inputStream.bufferedReader().use { it.readText() }
                HttpURLConnection.HTTP_NOT_FOUND -> null
                else -> throw IOException("Unexpected HTTP status $code from $url")
            }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val TAG = "BriefingSyncWorker"
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 15_000
        private const val MIN_BACKOFF_MS = 30_000L
        private const val SETUP_WORK_NAME = "briefing_setup_sync"
        private const val PUSH_WORK_NAME = "briefing_push_update"
        private const val KEY_PUSH_PAYLOAD = "briefing_push_payload"

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<BriefingSyncWorker>()
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, MIN_BACKOFF_MS, TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                SETUP_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }

        fun enqueuePayload(context: Context, payload: String) {
            val request = OneTimeWorkRequestBuilder<BriefingSyncWorker>()
                .setInputData(workDataOf(KEY_PUSH_PAYLOAD to payload))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                PUSH_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }
    }
}

private suspend fun <T> com.google.android.gms.tasks.Task<T>.await(): T =
    suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
        addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(it) }
    }

internal fun authenticatedUrl(baseUrl: String, path: String, token: String): String {
    if (token.isBlank()) return "$baseUrl$path"
    val encodedToken = URLEncoder.encode(token, StandardCharsets.UTF_8.name())
    return "$baseUrl$path?token=$encodedToken"
}
