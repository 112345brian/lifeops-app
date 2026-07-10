package com.lifeops.briefing

import android.content.Context
import android.util.Log
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import com.lifeops.briefing.data.NextTasksState
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fired when the user taps a task's checkbox in the widget. POSTs
 * /api/tasks/{id}/complete (which completes the task in FlowSavvy server-side
 * and returns the fresh next-tasks list in the same response), then persists
 * that fresh list directly -- no need to wait for NextTasksRefreshWorker's
 * next 15-minute cycle.
 */
class CompleteTaskAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val taskId = parameters[TASK_ID_KEY] ?: return
        val baseUrl = WidgetConfigStore.getBaseUrl(context) ?: return
        val token = WidgetConfigStore.getToken(context) ?: return

        val body = try {
            completeTask(baseUrl, token, taskId)
        } catch (e: IOException) {
            Log.e(TAG, "error completing task $taskId at $baseUrl", e)
            return // network hiccup -- next periodic pull will reconcile
        }

        val state = NextTasksState.fromApiResponse(body, System.currentTimeMillis())
        persistNextTasksForInstance(context, glanceId, state)
    }

    private fun completeTask(baseUrl: String, token: String, taskId: String): String {
        val url = URL("$baseUrl/api/tasks/$taskId/complete?token=$token")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 15_000
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

    companion object {
        private const val TAG = "CompleteTaskAction"
        val TASK_ID_KEY = ActionParameters.Key<String>("taskId")
    }
}
