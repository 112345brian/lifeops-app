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
        registerToken(applicationContext, token)
        Result.success()
    }

    companion object {
        const val KEY_TOKEN = "token"
    }
}
