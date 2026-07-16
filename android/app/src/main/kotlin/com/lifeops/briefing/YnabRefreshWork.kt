package com.lifeops.briefing

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

internal fun enqueueForcedYnabRefresh(context: Context) {
    WorkManager.getInstance(context).enqueue(
        OneTimeWorkRequestBuilder<NextTasksRefreshWorker>()
            .setInputData(workDataOf(NextTasksRefreshWorker.INPUT_FORCE_YNAB_REFRESH to true))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build(),
    )
}

/** Fired from [OpenExternalAppAction] when the money tile is tapped through
 * to YNAB: gives the user a couple minutes to actually edit a category over
 * there, then forces a refresh so the widget doesn't sit on stale data until
 * the next periodic tick or a manual config-screen Save. Uses
 * enqueueUniqueWork(REPLACE) so mashing the money tile repeatedly just
 * resets the 2-minute timer to the latest tap instead of stacking up
 * redundant API calls. */
internal fun enqueueDelayedYnabRefresh(context: Context) {
    WorkManager.getInstance(context).enqueueUniqueWork(
        UNIQUE_DELAYED_YNAB_REFRESH_WORK_NAME,
        ExistingWorkPolicy.REPLACE,
        OneTimeWorkRequestBuilder<NextTasksRefreshWorker>()
            .setInputData(workDataOf(NextTasksRefreshWorker.INPUT_FORCE_YNAB_REFRESH to true))
            .setInitialDelay(2, TimeUnit.MINUTES)
            .build(),
    )
}

private const val UNIQUE_DELAYED_YNAB_REFRESH_WORK_NAME = "delayed_ynab_refresh"
