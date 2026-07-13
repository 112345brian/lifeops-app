package com.lifeops.briefing

import android.content.Context
import android.util.Log
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.state.updateAppWidgetState
import com.lifeops.briefing.data.NextTasksState
import java.io.IOException

/**
 * Fired when the user taps a task's checkbox in the widget. POSTs a
 * "complete:<id>" signal straight to the public ntfy topic lifeops already
 * polls for phone->server signals (see ntfy.py/runner.py's ingest()) --
 * NOT the Tailscale-gated panel. Completing a task from the widget
 * shouldn't require being on the tailnet; ntfy.sh is reachable from
 * anywhere, and the next ingest() cycle (at most ~2 min away, per
 * register_task.ps1's signal-tier trigger) does the actual FlowSavvy
 * completion server-side. Since there's no synchronous response with a
 * fresh task list (unlike the old direct-to-panel call), the checked task
 * is optimistically dropped from the locally cached list right away so the
 * checkbox doesn't look like it did nothing.
 */
class CompleteTaskAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val taskId = parameters[TASK_ID_KEY] ?: return

        try {
            postCompleteSignal(taskId)
        } catch (e: IOException) {
            Log.e(TAG, "error posting complete signal for $taskId", e)
            return // network hiccup -- leave it checked-off-looking-unchanged; user can retap
        }

        removeTaskLocally(context, glanceId, taskId)
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
