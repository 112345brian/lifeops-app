package com.lifeops.briefing

import android.content.Context
import android.util.Log
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.state.updateAppWidgetState
import com.lifeops.briefing.data.NextTasksState
import java.io.IOException
import java.net.HttpURLConnection

/**
 * Fired when the user taps a task's checkbox in the widget. Tries
 * `/api/tasks/{id}/complete` on the Tailscale-gated panel first -- that
 * completes the task in FlowSavvy and returns the fresh next-tasks list in
 * the same response, so the widget updates immediately with no extra round
 * trip. If that call fails (most commonly because the phone isn't on the
 * tailnet right now), falls back to posting a "complete:<id>" signal to the
 * public ntfy topic lifeops already polls for phone->server signals (see
 * ntfy.py/runner.py's ingest()); the next ingest() cycle (at most ~2 min
 * away) does the actual FlowSavvy completion server-side. Since the
 * fallback has no synchronous response with a fresh task list, the checked
 * task is optimistically dropped from the locally cached list right away so
 * the checkbox doesn't look like it did nothing.
 */
class CompleteTaskAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val taskId = parameters[TASK_ID_KEY] ?: return
        val baseUrl = WidgetConfigStore.getBaseUrl(context)
        val token = WidgetConfigStore.getToken(context)

        if (baseUrl != null && token != null) {
            try {
                val body = completeTaskDirect(baseUrl, token, taskId)
                val state = NextTasksState.fromApiResponse(body, System.currentTimeMillis())
                persistNextTasksForInstance(context, glanceId, state)
                return
            } catch (e: IOException) {
                Log.w(TAG, "direct completion failed for $taskId, falling back to ntfy signal", e)
            }
        }

        try {
            postCompleteSignal(taskId)
        } catch (e: IOException) {
            Log.e(TAG, "error posting complete signal for $taskId", e)
            return // network hiccup -- leave it checked-off-looking-unchanged; user can retap
        }

        removeTaskLocally(context, glanceId, taskId)
    }

    private fun completeTaskDirect(baseUrl: String, token: String, taskId: String): String {
        return httpRequest(
            url = "$baseUrl/api/tasks/$taskId/complete?token=$token",
            method = "POST",
            requireExactCode = HttpURLConnection.HTTP_OK,
        )
    }

    private fun postCompleteSignal(taskId: String) {
        if (BuildConfig.NTFY_SIGNAL_TOPIC.isBlank()) {
            throw IOException("ntfy signal topic is not configured in local.properties")
        }
        httpRequest(
            url = "https://ntfy.sh/${BuildConfig.NTFY_SIGNAL_TOPIC}",
            method = "POST",
            body = "complete:$taskId",
        )
    }

    private suspend fun removeTaskLocally(context: Context, glanceId: GlanceId, taskId: String) {
        updateAppWidgetState(context, glanceId) { prefs ->
            // Mark this id pending-removed FIRST and unconditionally -- a
            // concurrent full refresh (periodic pull, or the one-time pull
            // on widget placement/Settings Save) landing before the
            // server-side completion catches up must not resurrect this
            // task, whether or not we have a local task list to also filter
            // right now. See PendingRemovals.kt.
            PendingRemovals.add(prefs, taskId, System.currentTimeMillis())

            val currentJson = prefs[WidgetKeys.NEXT_TASKS_JSON] ?: return@updateAppWidgetState
            // A malformed persisted NEXT_TASKS_JSON shouldn't crash this
            // action callback -- leave state untouched and let the next
            // periodic pull (which replaces it wholesale) recover instead.
            val current = try {
                NextTasksState.fromJson(currentJson)
            } catch (e: org.json.JSONException) {
                return@updateAppWidgetState
            }
            val updated = current.copy(tasks = current.tasks.filterNot { it.id == taskId })
            prefs[WidgetKeys.NEXT_TASKS_JSON] = updated.toJson()
        }
        BriefingWidget().update(context, glanceId)
    }

    companion object {
        private const val TAG = "CompleteTaskAction"
        val TASK_ID_KEY = ActionParameters.Key<String>("taskId")
    }
}
