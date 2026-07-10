package com.lifeops.briefing

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.CheckBox
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.provideContent
import androidx.glance.currentState
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.semantics.semantics
import androidx.glance.semantics.testTag
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.state.PreferencesGlanceStateDefinition
import com.lifeops.briefing.data.BriefingState
import com.lifeops.briefing.data.NextTask
import com.lifeops.briefing.data.NextTasksState

/**
 * The Glance widget itself: describes what to render for each widget instance.
 *
 * Reads the persisted [BriefingState] (serialized JSON under
 * [WidgetKeys.BRIEFING_JSON]) and [NextTasksState] (under
 * [WidgetKeys.NEXT_TASKS_JSON]) and renders: markdown-ish bold/plain briefing
 * text, a compact stat row, a "received" timestamp, and up to a few upcoming
 * tasks with real checkboxes. Tapping the body (outside a checkbox) opens the
 * control panel.
 *
 * Briefing state is written by [BriefingSyncWorker] from either an FCM push
 * or an immediate setup pull. Next-tasks state is written by
 * [NextTasksRefreshWorker]'s periodic pull and by [CompleteTaskAction]'s
 * immediate update after a checkbox tap.
 */
class BriefingWidget : GlanceAppWidget() {

    override val stateDefinition = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val prefs = currentState<Preferences>()
            val briefingJson = prefs[WidgetKeys.BRIEFING_JSON]
            val briefing = if (briefingJson != null) BriefingState.fromJson(briefingJson) else BriefingState.empty()
            val nextTasksJson = prefs[WidgetKeys.NEXT_TASKS_JSON]
            val nextTasks = if (nextTasksJson != null) NextTasksState.fromJson(nextTasksJson) else NextTasksState.empty()

            GlanceTheme {
                BriefingContent(briefing, nextTasks)
            }
        }
    }
}

@Composable
private fun BriefingContent(state: BriefingState, nextTasks: NextTasksState) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .padding(8.dp)
            .clickable(actionRunCallback<OpenPanelAction>()),
    ) {
        if (state.text == null) {
            Text(
                text = "No briefing yet — tap to configure",
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant),
            )
        } else {
            // Body: each source line becomes a Row of bold/non-bold Text
            // runs, stacked in a Column to preserve line breaks.
            BriefingText(state.text)

            // Stat row -- only the facts that are present.
            val stats = buildList {
                if (state.gymThisWeek != null && state.gymTarget != null) {
                    add("Gym ${state.gymThisWeek}/${state.gymTarget}")
                }
                if (state.discretionaryDollars != null) {
                    add("$${state.discretionaryDollars}")
                }
                if (state.courseworkHoursNext7d != null) {
                    add("${state.courseworkHoursNext7d}h/7d")
                }
            }
            if (stats.isNotEmpty()) {
                Row(modifier = GlanceModifier.fillMaxWidth().padding(top = 2.dp)) {
                    stats.forEachIndexed { index, stat ->
                        if (index > 0) {
                            Spacer(modifier = GlanceModifier.width(8.dp))
                        }
                        Text(
                            text = stat,
                            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant),
                        )
                    }
                }
            }

            // "As of" info line -- when this snapshot was received, if known.
            state.fetchedAtEpochMillis?.let {
                Text(
                    text = "as of ${relativeTime(it)}",
                    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant),
                )
            }
        }

        if (nextTasks.tasks.isNotEmpty()) {
            Spacer(modifier = GlanceModifier.height(4.dp))
            Text(
                text = "Up next",
                style = TextStyle(
                    fontWeight = FontWeight.Bold,
                    color = GlanceTheme.colors.onSurface,
                ),
            )
            for (task in nextTasks.tasks) {
                NextTaskRow(task)
            }
        }
    }
}

@Composable
internal fun BriefingText(text: String) {
    Column(modifier = GlanceModifier.fillMaxWidth()) {
        for ((lineIndex, line) in text.lineSequence().filter { it.isNotBlank() }.withIndex()) {
            Row(modifier = GlanceModifier.fillMaxWidth()) {
                for ((segmentIndex, markedSegment) in parseMarkupLine(line).withIndex()) {
                    val (segment, isBold) = markedSegment
                    Text(
                        text = segment,
                        style = TextStyle(
                            fontWeight = if (isBold) FontWeight.Bold else FontWeight.Normal,
                            color = GlanceTheme.colors.onSurface,
                        ),
                        modifier = GlanceModifier.semantics {
                            testTag = "briefing-text-$lineIndex-$segmentIndex"
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun NextTaskRow(task: NextTask) {
    CheckBox(
        checked = false, // a completed task drops off the list on the next refresh rather than rendering checked-then-vanishing
        onCheckedChange = actionRunCallback<CompleteTaskAction>(
            actionParametersOf(
                CompleteTaskAction.TASK_ID_KEY to task.id,
            ),
        ),
        text = task.title,
        style = TextStyle(color = GlanceTheme.colors.onSurface),
        modifier = GlanceModifier.fillMaxWidth(),
    )
}

/**
 * Splits [line] into (text, isBold) runs on `**...**` pairs.
 *
 * Examples:
 *  - "Hello **world**!" -> [("Hello ", false), ("world", true), ("!", false)]
 *  - "**Bold start** then normal" -> [("Bold start", true), (" then normal", false)]
 *  - "Just plain text" -> [("Just plain text", false)]
 *  - "" -> [("", false)]
 */
private fun parseMarkupLine(line: String): List<Pair<String, Boolean>> {
    val boldRegex = Regex("\\*\\*(.+?)\\*\\*")
    val result = mutableListOf<Pair<String, Boolean>>()
    var lastIndex = 0
    for (match in boldRegex.findAll(line)) {
        if (match.range.first > lastIndex) {
            result.add(line.substring(lastIndex, match.range.first) to false)
        }
        result.add(match.groupValues[1] to true)
        lastIndex = match.range.last + 1
    }
    if (lastIndex < line.length) {
        result.add(line.substring(lastIndex) to false)
    }
    if (result.isEmpty()) {
        result.add(line to false)
    }
    return result
}

/** Coarse "Xm/Xh/Xd ago" label from an epoch-millis timestamp to now. */
private fun relativeTime(fetchedAtEpochMillis: Long): String {
    val diffMinutes = (System.currentTimeMillis() - fetchedAtEpochMillis) / 60_000L
    return when {
        diffMinutes < 1 -> "just now"
        diffMinutes < 60 -> "${diffMinutes}m ago"
        diffMinutes < 60 * 24 -> "${diffMinutes / 60}h ago"
        else -> "${diffMinutes / (60 * 24)}d ago"
    }
}
