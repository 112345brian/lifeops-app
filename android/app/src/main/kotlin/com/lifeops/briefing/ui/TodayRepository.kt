package com.lifeops.briefing.ui

import android.content.Context
import android.net.Uri
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.lifeops.briefing.BriefingWidget
import com.lifeops.briefing.LifeOpsComputeWorker
import com.lifeops.briefing.PendingRemovals
import com.lifeops.briefing.WidgetConfigStore
import com.lifeops.briefing.WidgetKeys
import com.lifeops.briefing.authenticatedUrl
import com.lifeops.briefing.data.BriefingState
import com.lifeops.briefing.data.NextTask
import com.lifeops.briefing.data.NextTasksState
import com.lifeops.briefing.httpRequest
import com.lifeops.briefing.persistNextTasksForInstance
import com.lifeops.briefing.postNtfySignal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import java.io.IOException
import java.net.HttpURLConnection

/**
 * Read/write model backing the Today and Attention screens. Deliberately
 * reads the SAME [BriefingState]/[NextTasksState] JSON [BriefingWidget]
 * already renders (persisted per placed-widget-instance Glance state by
 * [com.lifeops.briefing.LifeOpsComputeWorker]'s compute tick) rather than a
 * second parallel store -- "widget equals status plus next move, full app
 * equals status plus reasons plus controls" (docs/lifeops_capability_todo.md's
 * Design Principles) means both surfaces must agree on the same underlying
 * state, never a forked copy of it.
 */
data class TodayData(
    val briefing: BriefingState,
    val nextTasks: NextTasksState,
    /** true if no [BriefingWidget] instance is placed anywhere yet -- there
     * is nowhere for the compute tick to have persisted into, so
     * [briefing]/[nextTasks] are both empty by construction, not because
     * nothing is due. The UI should say so rather than implying "all clear." */
    val noWidgetPlaced: Boolean,
) {
    companion object {
        val EMPTY = TodayData(BriefingState.empty(), NextTasksState(tasks = emptyList()), noWidgetPlaced = true)
    }
}

object TodayRepository {
    private suspend fun glanceIds(context: Context): List<GlanceId> =
        GlanceAppWidgetManager(context).getGlanceIds(BriefingWidget::class.java)

    suspend fun load(context: Context): TodayData = withContext(Dispatchers.IO) {
        val glanceId = glanceIds(context).firstOrNull() ?: return@withContext TodayData.EMPTY
        val prefs = getAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId)
        val briefing = try {
            prefs[WidgetKeys.BRIEFING_JSON]?.let { BriefingState.fromJson(it) }
        } catch (e: JSONException) {
            null
        } ?: BriefingState.empty()
        val nextTasks = try {
            prefs[WidgetKeys.NEXT_TASKS_JSON]?.let { NextTasksState.fromJson(it) }
        } catch (e: JSONException) {
            null
        } ?: NextTasksState(tasks = emptyList())
        TodayData(briefing, nextTasks, noWidgetPlaced = false)
    }

    /**
     * Same completion contract as [com.lifeops.briefing.CompleteTaskAction]
     * (direct `/api/tasks/{id}/complete` first, ntfy `complete:<id>` signal +
     * optimistic local removal as the offline fallback), reused rather than
     * reinvented -- applied to every placed widget instance since this call
     * (a Today-screen tap) has no single GlanceId to scope itself to the way
     * a widget checkbox tap does.
     *
     * Intentionally NOT gated behind [com.lifeops.briefing.BiometricGate].
     * Per the locked-in product decision (`BiometricGate.kt`'s own kdoc),
     * gating applies to user-initiated writes that are financial (YNAB) or
     * spend LLM API credit -- never to routine/schedule/task actions.
     * Completing a task is the latter, same category as gym log/skip and
     * block-day (`ui/PanelActionsClient.kt`), so it stays ungated -- wiring
     * the gate here just because the primitive now exists would apply it
     * beyond its actual criterion.
     */
    suspend fun completeTask(context: Context, taskId: String) = withContext(Dispatchers.IO) {
        val ids = glanceIds(context)
        val baseUrl = WidgetConfigStore.getBaseUrl(context)
        val token = WidgetConfigStore.getToken(context)

        if (baseUrl != null && token != null) {
            try {
                val body = httpRequest(
                    url = authenticatedUrl(baseUrl, "/api/tasks/${Uri.encode(taskId)}/complete", token),
                    method = "POST",
                    requireExactCode = HttpURLConnection.HTTP_OK,
                )
                val state = NextTasksState.fromApiResponse(body, System.currentTimeMillis())
                for (glanceId in ids) persistNextTasksForInstance(context, glanceId, state)
                return@withContext
            } catch (e: IOException) {
                // network hiccup -- fall through to the ntfy fallback below
            } catch (e: JSONException) {
                // malformed/auth-interstitial response -- equally
                // untrustworthy, same fallback
            }
        }

        try {
            postNtfySignal("complete:$taskId")
        } catch (e: IOException) {
            return@withContext // leave state unchanged; user can retap
        }
        for (glanceId in ids) removeTaskLocally(context, glanceId, taskId)
    }

    private suspend fun removeTaskLocally(context: Context, glanceId: GlanceId, taskId: String) {
        updateAppWidgetState(context, glanceId) { prefs ->
            val current = prefs[WidgetKeys.NEXT_TASKS_JSON]?.let {
                try {
                    NextTasksState.fromJson(it)
                } catch (e: JSONException) {
                    null
                }
            }
            val task = current?.tasks?.firstOrNull { it.id == taskId }
                ?: NextTask(id = taskId, title = taskId, start = null)
            // Mark pending-removed first and unconditionally -- same
            // ordering CompleteTaskAction.kt's own removeTaskLocally uses,
            // for the same reason (a concurrent full refresh must not
            // resurrect this task before the server-side completion lands).
            PendingRemovals.add(prefs, task, System.currentTimeMillis())
            if (current == null) return@updateAppWidgetState
            val updated = current.copy(tasks = current.tasks.filterNot { it.id == taskId })
            prefs[WidgetKeys.NEXT_TASKS_JSON] = updated.toJson()
        }
        BriefingWidget().update(context, glanceId)
    }

    /** "Force refresh" / "refresh widget or briefing" quick action -- the
     * same one-time [LifeOpsComputeWorker] enqueue SettingsActivity's Save
     * button already performs (see that file), not a second manual-tick
     * mechanism. */
    fun forceRefresh(context: Context) {
        WorkManager.getInstance(context).enqueue(OneTimeWorkRequestBuilder<LifeOpsComputeWorker>().build())
    }
}
