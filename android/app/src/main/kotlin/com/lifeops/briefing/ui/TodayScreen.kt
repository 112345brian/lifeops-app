package com.lifeops.briefing.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lifeops.briefing.PanelActivity
import com.lifeops.briefing.WidgetConfigStore
import com.lifeops.briefing.data.NextTask
import com.lifeops.briefing.data.TodayEvent
import com.lifeops.briefing.severityDotColor
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * The new home/launcher screen (see [MainActivity]): status badge, the
 * LLM-narrated briefing headline, today's events, next tasks with complete
 * actions, and the quick actions docs/lifeops_capability_todo.md's "Full App
 * Home" section calls out (run catchup, log gym, skip gym, block
 * today/tomorrow, refresh widget/briefing, open full panel).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var data by remember { mutableStateOf(TodayData.EMPTY) }
    var loading by remember { mutableStateOf(true) }
    var actionInFlight by remember { mutableStateOf(false) }
    // Which task's checkbox is showing a transient "completing" state --
    // NOT "this task is complete": completion is reflected by the task
    // disappearing from data.nextTasks.tasks after reload(). Tracked here
    // (rather than as permanent per-row remembered state in TaskRow) so a
    // failed completeTask() call correctly reverts the checkbox instead of
    // leaving it stuck checked forever with no task actually completed.
    var completingTaskId by remember { mutableStateOf<String?>(null) }

    suspend fun reload() {
        loading = true
        data = TodayRepository.load(context)
        loading = false
    }

    LaunchedEffect(Unit) { reload() }

    fun runAction(label: String, block: suspend () -> Unit) {
        if (actionInFlight) return
        actionInFlight = true
        scope.launch {
            try {
                block()
                snackbarHostState.showSnackbar("$label done")
            } catch (e: Exception) {
                snackbarHostState.showSnackbar("$label failed: ${e.message ?: "unknown error"}")
            } finally {
                actionInFlight = false
                reload()
            }
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Today") }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(modifier = modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            StatusHeader(
                attentionState = data.briefing.attentionState,
                attentionLabel = data.briefing.attentionLabel,
                headline = data.briefing.attentionHeadline ?: data.briefing.text,
                noWidgetPlaced = data.noWidgetPlaced,
            )
            Spacer(Modifier.height(16.dp))

            if (loading) {
                CircularProgressIndicator(modifier = Modifier.padding(8.dp))
            }

            if (data.nextTasks.events.isNotEmpty()) {
                SectionLabel("Today's events")
                data.nextTasks.events.forEach { EventRow(it) }
                Spacer(Modifier.height(12.dp))
            }

            SectionLabel("Next tasks")
            if (data.nextTasks.tasks.isEmpty()) {
                Text(
                    text = if (data.noWidgetPlaced) {
                        "Place the LifeOps widget on your home screen to start syncing tasks."
                    } else {
                        "Nothing outstanding."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                    items(data.nextTasks.tasks, key = { it.id }) { task ->
                        TaskRow(
                            task = task,
                            completing = completingTaskId == task.id,
                            onComplete = {
                                completingTaskId = task.id
                                runAction("Complete") {
                                    try {
                                        TodayRepository.completeTask(context, task.id)
                                    } finally {
                                        completingTaskId = null
                                    }
                                }
                            },
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))
            SectionLabel("Quick actions")
            QuickActions(
                enabled = !actionInFlight,
                onLogGym = { runAction("Log gym") { PanelActionsClient.logGym(context) } },
                onSkipGym = { runAction("Skip gym") { PanelActionsClient.skipGym(context) } },
                onBlockToday = {
                    runAction("Block today") { PanelActionsClient.blockDay(context, LocalDate.now().toString()) }
                },
                onBlockTomorrow = {
                    runAction("Block tomorrow") {
                        PanelActionsClient.blockDay(context, LocalDate.now().plusDays(1).toString())
                    }
                },
                onRunCatchup = {
                    // "Run catchup" and the old separate "Force refresh"
                    // button are the SAME action now that both go through
                    // PanelActionsClient.runCatchup/TodayRepository.forceRefresh
                    // (both just enqueue LifeOpsComputeWorker's one-time
                    // compute tick -- see PanelActionsClient.runDomain's own
                    // kdoc) -- merged into one button rather than keeping two
                    // controls that do the exact same thing. Fire-and-forget:
                    // the enqueue is asynchronous, not awaitable from here --
                    // reload() below just re-reads whatever is currently
                    // persisted; a follow-up manual refresh picks up the
                    // tick's result once it lands.
                    TodayRepository.forceRefresh(context)
                    scope.launch {
                        snackbarHostState.showSnackbar("Refresh queued")
                        reload()
                    }
                },
                onOpenPanel = {
                    val baseUrl = WidgetConfigStore.getBaseUrl(context)
                    if (baseUrl == null) {
                        scope.launch { snackbarHostState.showSnackbar("Configure the panel URL in Settings first") }
                    } else {
                        // authenticatedIntent, not a bare PanelActivity.intent(context, baseUrl) --
                        // the latter loads the panel with no ?token=, so the
                        // server never sets its auth cookie (see
                        // PanelActivity.authenticatedIntent's kdoc).
                        context.startActivity(PanelActivity.authenticatedIntent(context, baseUrl))
                    }
                },
            )
        }
    }
}

@Composable
private fun StatusHeader(
    attentionState: String?,
    attentionLabel: String?,
    headline: String?,
    noWidgetPlaced: Boolean,
) {
    Row {
        Surface(
            color = severityDotColor(attentionState ?: "ok"),
            shape = CircleShape,
            modifier = Modifier.size(16.dp),
        ) {}
        Spacer(Modifier.height(0.dp))
        Text(
            text = "  ${attentionLabel ?: "OK"}",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
    }
    Spacer(Modifier.height(6.dp))
    Text(
        text = when {
            noWidgetPlaced -> "No widget placed yet -- place the LifeOps widget to start syncing."
            !headline.isNullOrBlank() -> headline
            else -> "You are clear. Follow the next scheduled move."
        },
        style = MaterialTheme.typography.bodyLarge,
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

@Composable
private fun EventRow(event: TodayEvent) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(text = event.start?.takeLast(8) ?: "--", modifier = Modifier.padding(end = 12.dp))
        Text(text = event.title, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun TaskRow(task: NextTask, completing: Boolean, onComplete: () -> Unit) {
    // Derived, not remembered: showing checked is purely a transient
    // "in flight" indicator while completing == true. If the completion
    // fails, TodayScreen resets completing to null (in a finally block) and
    // this correctly reverts to unchecked, instead of a permanently-latched
    // local `true` that survived a failed call with no way back.
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Checkbox(
            checked = completing,
            enabled = !completing,
            onCheckedChange = { onComplete() },
        )
        Column {
            Text(text = task.title)
            task.start?.let {
                Text(text = it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuickActions(
    enabled: Boolean,
    onLogGym: () -> Unit,
    onSkipGym: () -> Unit,
    onBlockToday: () -> Unit,
    onBlockTomorrow: () -> Unit,
    onRunCatchup: () -> Unit,
    onOpenPanel: () -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onLogGym, enabled = enabled) { Text("Log gym") }
        OutlinedButton(onClick = onSkipGym, enabled = enabled) { Text("Skip gym") }
        OutlinedButton(onClick = onBlockToday, enabled = enabled) { Text("Block today") }
        OutlinedButton(onClick = onBlockTomorrow, enabled = enabled) { Text("Block tomorrow") }
        Button(onClick = onRunCatchup, enabled = enabled) { Text("Run catchup") }
        OutlinedButton(onClick = onOpenPanel) { Text("Open full panel") }
    }
}
