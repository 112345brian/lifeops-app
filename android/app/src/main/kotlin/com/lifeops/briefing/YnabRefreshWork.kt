package com.lifeops.briefing

import android.content.Context
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf

internal fun enqueueForcedYnabRefresh(context: Context) {
    WorkManager.getInstance(context).enqueue(
        OneTimeWorkRequestBuilder<NextTasksRefreshWorker>()
            .setInputData(workDataOf(NextTasksRefreshWorker.INPUT_FORCE_YNAB_REFRESH to true))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build(),
    )
}
