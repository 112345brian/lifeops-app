package com.lifeops.briefing

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Runs [registerToken] as a guaranteed-execution WorkManager job rather than
 * a bare coroutine launched directly from [BriefingFcmService.onNewToken] --
 * same reasoning as [BriefingPersistWorker]: FCM's contract doesn't promise
 * background work merely *started* inside onNewToken will run to
 * completion if the OS reclaims the process first.
 */
class RegisterTokenWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val token = inputData.getString(KEY_TOKEN) ?: return@withContext Result.failure()
        // registerToken already tries direct-then-ntfy-relay internally;
        // both failing (e.g. phone fully offline, not just off-tailnet)
        // must retry with WorkManager's backoff rather than reporting
        // success and silently dropping the registration for good.
        if (registerToken(applicationContext, token)) Result.success() else Result.retry()
    }

    companion object {
        const val KEY_TOKEN = "token"
    }
}
