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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lifeops.briefing.PanelActivity
import com.lifeops.briefing.SoloStatPresentation
import com.lifeops.briefing.WidgetConfigStore
import com.lifeops.briefing.data.NextTask
import com.lifeops.briefing.data.NotableEvent
import com.lifeops.briefing.data.TodayEvent
import com.lifeops.briefing.gymFallbackStatPresentation
import com.lifeops.briefing.moneyStatPresentation
import com.lifeops.briefing.notableEventDay
import com.lifeops.briefing.notableEventTime
import com.lifeops.briefing.severityDotColor
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * The new home/launcher screen (see [MainActivity]): status badge, the
 * LLM-narrated briefing headline, today's events, this week's notable
 * events, money and gym status cards, next tasks with complete actions, and
 * the quick actions docs/lifeops_capability_todo.md's "Full App Home"
 * section calls out (run catchup, log gym, skip gym, block today/tomorrow,
 * refresh widget/briefing, open full panel).
 *
 * The money/gym/notable-events sections are all pure reads of fields
 * [TodayData.briefing] ([com.lifeops.briefing.data.BriefingState]) already
 * carries -- this screen previously only ever rendered
 * `data.nextTasks.events` (today-only) and left `data.briefing.notableEvents`
 * /`discretionaryDollars`/`gymLast7d` etc. unrendered even though
 * [BriefingWidget][com.lifeops.briefing.BriefingWidget] (the Glance home
 * screen widget) already surfaces all of it. Reuses that widget's own
 * presentation logic (`moneyStatPresentation`, `gymFallbackStatPresentation`,
 * `notableEventDay`/`notableEventTime`, all `internal` in BriefingWidget.kt)
 * rather than re-deriving the same formatting/framing decisions here.
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
        // verticalScroll, not a fixed-height Column: the new "This week"/
        // Money/Gym sections (on top of events/tasks/quick actions that were
        // already here) can easily exceed one screen's height, and a plain
        // Column with no scroll silently clips everything below the
        // viewport -- including Quick Actions/"Run catchup", with no way to
        // reach them. The task list below is a plain Column.forEach, not a
        // LazyColumn, specifically so it can live inside this outer scroll
        // (a LazyColumn nested in a scrollable Column needs an unbounded
        // height, which LazyColumn doesn't support) -- fine given this
        // list's realistic size (a handful of outstanding tasks, not a
        // long feed).
        Column(
            modifier = modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
        ) {
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

            if (data.briefing.notableEvents.isNotEmpty()) {
                SectionLabel("This week")
                data.briefing.notableEvents.forEach { NotableEventRow(it) }
                Spacer(Modifier.height(12.dp))
            }

            // Money and gym: mirror BriefingWidget.kt's own solo-tile
            // presentation logic (moneyStatPresentation/
            // gymFallbackStatPresentation) rather than re-deriving the
            // label/value/status text here, so the in-app screen can never
            // drift from what the widget already shows for the exact same
            // BriefingState fields. Status DOT color still comes from
            // severityDotColor keyed off attention.compute()'s per-domain
            // reasons (not the widget's own bespoke accent colors), per this
            // screen's existing convention (StatusHeader/ReasonRow in
            // AttentionScreen.kt both do the same) -- the widget's accent
            // set is a Glance-specific choice, not the app-wide severity
            // language this screen otherwise speaks.
            if (data.briefing.discretionaryDollars != null || data.briefing.discretionaryCurrentDollars != null) {
                SectionLabel("Money")
                val severity = data.briefing.reasons.firstOrNull { it.domain == "money" }?.severity ?: "ok"
                val stat = moneyStatPresentation(
                    dollars = data.briefing.discretionaryDollars ?: data.briefing.discretionaryCurrentDollars ?: 0,
                    severity = severity,
                    todayDollars = data.briefing.discretionaryTodayDollars,
                    currentDollars = data.briefing.discretionaryCurrentDollars,
                )
                StatCard(stat, severity)
                Spacer(Modifier.height(12.dp))
            }

            val gymLast7d = data.briefing.gymLast7d
            val gymTarget = data.briefing.gymTarget
            if (gymLast7d != null && gymTarget != null) {
                SectionLabel("Gym")
                val severity = data.briefing.reasons.firstOrNull { it.domain == "gym" }?.severity ?: "ok"
                val stat = gymFallbackStatPresentation(gymLast7d, gymTarget)
                StatCard(stat, severity)
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
                Column {
                    data.nextTasks.tasks.forEach { task ->
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

/**
 * One row of the "This week" section -- [NotableEvent]s from
 * `data.briefing.notableEvents` (the server's rolling next-7-days
 * infrequent-event heads-up: haircuts, one-off appointments, "movie on
 * Wednesday" -- see [NotableEvent]'s own kdoc), as opposed to [EventRow]
 * above which only ever shows TODAY's events. Day/time formatting is
 * [notableEventDay]/[notableEventTime], reused as-is from BriefingWidget.kt
 * rather than re-deriving the same date parsing here -- those two are
 * `internal` (not `private`) specifically so both the Glance widget and this
 * plain-Compose screen render the exact same "Wed"/"6:00 PM" strings for the
 * same event.
 */
@Composable
private fun NotableEventRow(event: NotableEvent) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            Column(modifier = Modifier.padding(end = 12.dp)) {
                Text(
                    text = notableEventDay(event),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                notableEventTime(event)?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(text = event.title, style = MaterialTheme.typography.titleMedium)
        }
    }
}

/**
 * Shared card for the Money/Gym sections -- same visual language as
 * AttentionScreen.kt's `ReasonRow` (`surfaceVariant` container, `14.dp`
 * inner padding, a [severityDotColor] dot ahead of a `labelMedium` header,
 * then a `titleMedium` headline value). [stat]'s label/value/status TEXT
 * comes from BriefingWidget.kt's own `moneyStatPresentation`/
 * `gymFallbackStatPresentation` (so this screen can't drift from the
 * widget's "safe to spend" / "3/4 this week" framing -- see
 * android/CLAUDE.md's "Money widget design" section for why that framing
 * matters over a raw balance/alert block), but the status DOT's COLOR
 * deliberately comes from [severityDotColor] fed by attention.compute()'s
 * own per-domain [severity] rather than [stat]'s bespoke Glance accent
 * colors, to stay consistent with every other severity dot on this screen. */
@Composable
private fun StatCard(stat: SoloStatPresentation, severity: String) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row {
                Surface(
                    color = severityDotColor(severity),
                    shape = CircleShape,
                    modifier = Modifier.size(10.dp).clip(CircleShape),
                ) {}
                Text(
                    text = "  ${stat.label} · ${stat.status}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(text = stat.value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            stat.secondary?.let {
                Text(text = it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
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
