package com.lifeops.briefing.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lifeops.briefing.RoutineStatus
import com.lifeops.briefing.data.LifeOpsDatabase
import com.lifeops.briefing.data.RoutineDefaults
import com.lifeops.briefing.data.RoutineEntity
import com.lifeops.briefing.data.toRoutine
import com.lifeops.briefing.severityDotColor
import com.lifeops.briefing.status
import java.time.LocalDateTime
import kotlinx.coroutines.launch

/**
 * Human-readable cadence phrasing for a [RoutineEntity], replacing the old
 * "1x / 7d · since_last" raw-parameter dump. Written as a plain top-level
 * function (no Compose dependency) so it's independently unit-testable --
 * see `RoutineCadenceLabelTest.kt`.
 *
 * The two [RoutineEntity.anchor] models genuinely mean different things (see
 * [com.lifeops.briefing.Anchor]'s own kdoc), so they get different phrasing
 * rather than a single templated string:
 * - anchor=window is a quota ("N times in a rolling period") -- "4x a week"
 *   reads as a target.
 * - anchor=since_last is a cooldown ("due again N days after the last time")
 *   -- [RoutineEntity.timesPerWindow] isn't even consulted by [status] for
 *   this anchor (see `Routine.kt`'s `status()`), so surfacing it here would
 *   describe a number the due-check doesn't actually use. "Every N days"
 *   describes the real rule instead.
 */
fun RoutineEntity.cadenceLabel(): String = when (anchor) {
    RoutineEntity.ANCHOR_SINCE_LAST -> when (perDays) {
        1 -> "Every day"
        7 -> "Every week"
        else -> "Every $perDays days"
    }
    else -> when {
        timesPerWindow == 1 && perDays == 7 -> "Once a week"
        timesPerWindow == 1 && perDays == 1 -> "Once a day"
        perDays == 7 -> "${timesPerWindow}x a week"
        perDays == 1 -> "${timesPerWindow}x a day"
        else -> "${timesPerWindow}x every $perDays days"
    }
}

/** List of every persisted [RoutineEntity] -- the "Recurring" tab. Tap one
 * to open [RoutineEditScreen]; the FAB starts a new one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutineListScreen(onOpenRoutine: (String) -> Unit, onNewRoutine: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var routines by remember { mutableStateOf(emptyList<RoutineEntity>()) }
    // Keyed by routine.id (== the domain string HistoryEventDao already
    // indexes completions by -- see getTimestampsIsoForDomain's kdoc).
    // Deliberately NOT a stored/cached column on RoutineEntity: due/not-due
    // and times-this-window are a function of *now* plus the completion
    // history, not a fact about the routine itself, so persisting a
    // snapshot would go stale the moment time passes or a completion is
    // logged elsewhere (e.g. LifeOpsComputeWorker). Computing it live here
    // via the same status() primitive LifeOpsComputeWorker already uses for
    // gym/social keeps this screen's notion of "due" identical to the one
    // driving notifications, instead of a second, potentially-diverging
    // implementation.
    var statuses by remember { mutableStateOf(emptyMap<String, RoutineStatus>()) }

    LaunchedEffect(Unit) {
        val db = LifeOpsDatabase.getInstance(context)
        val dao = db.routineDao()
        // Seeds the gym/partner/friends/meal defaults the old Python
        // backend always silently computed with, so a fresh install (or
        // one predating this seeding) doesn't show an empty list -- see
        // RoutineDefaults' kdoc. IGNORE conflict strategy means this never
        // clobbers a routine the user already edited.
        dao.insertIfAbsent(RoutineDefaults.ALL)
        val all = dao.getAll()
        routines = all
        val now = LocalDateTime.now()
        statuses = all.associate { routine ->
            val timestamps = db.historyEventDao().getTimestampsIsoForDomain(routine.id)
            routine.id to status(routine.toRoutine(), timestamps, now)
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Recurring") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = onNewRoutine) { Text("+") }
        },
    ) { padding ->
        if (routines.isEmpty()) {
            Column(modifier = modifier.fillMaxSize().padding(padding).padding(24.dp)) {
                Text(
                    text = "No routines yet. Tap + to create one (gym, chores, social check-ins, etc).",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            LazyColumn(modifier = modifier.fillMaxSize().padding(padding)) {
                items(routines, key = { it.id }) { routine ->
                    RoutineCard(routine = routine, status = statuses[routine.id], onOpenRoutine = onOpenRoutine)
                }
            }
        }
    }
}

/**
 * One routine's card: a due/not-due status dot, a human-readable cadence
 * phrase, and a progress indicator -- the "glanceable status" language real
 * habit-tracking apps (Streaks, Loop Habit Tracker, Habitify) use instead of
 * printing raw cadence parameters as text. See this task's design-research
 * brief for the specific apps/patterns this is modeled on.
 */
@Composable
private fun RoutineCard(routine: RoutineEntity, status: RoutineStatus?, onOpenRoutine: (String) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // The status dot is the single glance-first signal this
                // redesign is built around -- everything else in the card is
                // supporting detail read only on a second look. Reusing
                // severityDotColor's existing "fucked"/"ok" buckets (the
                // same ones AttentionScreen's dots use) rather than
                // inventing a third color for this screen keeps "red = due,
                // needs you" consistent across the whole app instead of
                // introducing a second color language for the same concept.
                // "risk" (amber) is intentionally unused here: due/not-due
                // is a hard binary for a routine (unlike an AttentionReason,
                // which has real graduated severity), so a two-color signal
                // is more honest than manufacturing a third bucket.
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(
                            color = severityDotColor(if (status?.due == true) "fucked" else "ok"),
                            shape = CircleShape,
                        ),
                )
                Spacer(Modifier.width(8.dp))
                Text(text = routine.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = routine.cadenceLabel(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            when (status) {
                is RoutineStatus.Window -> {
                    // Progress-toward-target as a fraction of the current
                    // window, not the raw target as text -- the exact
                    // pattern Loop Habit Tracker's mini progress bar and
                    // this task's design research both call out for
                    // flexible-cadence routines ("2/4 this week" beats
                    // printing "target=4").
                    val target = routine.timesPerWindow.coerceAtLeast(1)
                    LinearProgressIndicator(
                        progress = { (status.timesThisWindow.toFloat() / target).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                        color = severityDotColor(if (status.due) "fucked" else "ok"),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "${status.timesThisWindow}/${routine.timesPerWindow} this window",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                is RoutineStatus.SinceLast -> {
                    val daysSince = status.daysSinceLast
                    if (daysSince != null) {
                        // Elapsed fraction of the cooldown window -- e.g.
                        // 5 of 7 days since last completion reads as
                        // "mostly through the window" even before it flips
                        // to due, matching the same "progress toward the
                        // next reset" language the Window branch above uses.
                        LinearProgressIndicator(
                            progress = { (daysSince.toFloat() / routine.perDays.coerceAtLeast(1)).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                            color = severityDotColor(if (status.due) "fucked" else "ok"),
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                    Text(
                        text = if (daysSince == null) "Not logged yet" else "${daysSince}d ago",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                null -> {
                    // Status hasn't loaded yet (first composition, before
                    // LaunchedEffect's query returns) -- render the card
                    // without a progress row rather than a misleading
                    // "not due" default.
                }
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { onOpenRoutine(routine.id) }) { Text("Edit") }
        }
    }
}

/**
 * Basic/advanced routine editor. Per the locked-in product decision (see
 * this task's brief and docs/lifeops_capability_todo.md's Design
 * Principles: "any routine/recurring-item editor defaults to a basic view...
 * scripting is opt-in advanced view only"): the basic view is plain fields
 * for times/per_days/anchor/on_due; the advanced view is a single raw-text
 * field for [RoutineEntity.constraintsJson] (the LifeScript/extras blob) --
 * no autocomplete or syntax-aware editing, just an editable text field
 * holding the current constraint string, matching that same principle
 * rather than building a fancier syntax-aware editor no one asked for.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutineEditScreen(routineId: String?, onSaved: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var id by remember { mutableStateOf(routineId ?: "") }
    var title by remember { mutableStateOf("") }
    var timesPerWindow by remember { mutableStateOf("1") }
    var perDays by remember { mutableStateOf("7") }
    var anchor by remember { mutableStateOf(RoutineEntity.ANCHOR_WINDOW) }
    var onDue by remember { mutableStateOf(RoutineEntity.ON_DUE_NOTIFY) }
    var constraintsJson by remember { mutableStateOf("") }
    var advanced by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(routineId == null) }

    LaunchedEffect(routineId) {
        if (routineId != null) {
            val existing = LifeOpsDatabase.getInstance(context).routineDao().getById(routineId)
            if (existing != null) {
                id = existing.id
                title = existing.title
                timesPerWindow = existing.timesPerWindow.toString()
                perDays = existing.perDays.toString()
                anchor = existing.anchor
                onDue = existing.onDue
                constraintsJson = existing.constraintsJson ?: ""
            }
            loaded = true
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(if (routineId == null) "New routine" else "Edit routine") }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        if (!loaded) return@Scaffold
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(padding)
                .padding(20.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(text = "Advanced view", style = MaterialTheme.typography.labelLarge)
                Switch(checked = advanced, onCheckedChange = { advanced = it })
            }
            Spacer(Modifier.height(12.dp))

            if (!advanced) {
                OutlinedTextField(
                    value = id,
                    onValueChange = { id = it },
                    label = { Text("Id (e.g. gym, meal, social)") },
                    enabled = routineId == null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = timesPerWindow,
                        onValueChange = { timesPerWindow = it },
                        label = { Text("Times") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = perDays,
                        onValueChange = { perDays = it },
                        label = { Text("Per days") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(text = "Anchor", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = anchor == RoutineEntity.ANCHOR_WINDOW,
                        onClick = { anchor = RoutineEntity.ANCHOR_WINDOW },
                        label = { Text("window") },
                    )
                    FilterChip(
                        selected = anchor == RoutineEntity.ANCHOR_SINCE_LAST,
                        onClick = { anchor = RoutineEntity.ANCHOR_SINCE_LAST },
                        label = { Text("since_last") },
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(text = "On due", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = onDue == RoutineEntity.ON_DUE_NOTIFY,
                        onClick = { onDue = RoutineEntity.ON_DUE_NOTIFY },
                        label = { Text("notify") },
                    )
                    FilterChip(
                        selected = onDue == RoutineEntity.ON_DUE_SCHEDULE_TASK,
                        onClick = { onDue = RoutineEntity.ON_DUE_SCHEDULE_TASK },
                        label = { Text("schedule_task") },
                    )
                }
            } else {
                Text(
                    text = "Raw LifeScript / extras constraint string. No syntax help here by " +
                        "design -- edit the same JSON/LifeScript text the basic view's fields " +
                        "otherwise generate.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = constraintsJson,
                    onValueChange = { constraintsJson = it },
                    label = { Text("constraintsJson") },
                    modifier = Modifier.fillMaxWidth().weight(1f, fill = false).height(240.dp),
                )
            }

            Spacer(Modifier.height(20.dp))
            TextButton(
                onClick = {
                    val times = timesPerWindow.toIntOrNull()
                    val days = perDays.toIntOrNull()
                    if (id.isBlank() || times == null || days == null) {
                        scope.launch { snackbarHostState.showSnackbar("Id, times, and per-days must be valid") }
                        return@TextButton
                    }
                    scope.launch {
                        LifeOpsDatabase.getInstance(context).routineDao().upsert(
                            RoutineEntity(
                                id = id.trim(),
                                title = title.ifBlank { id.trim() },
                                timesPerWindow = times,
                                perDays = days,
                                anchor = anchor,
                                onDue = onDue,
                                constraintsJson = constraintsJson.ifBlank { null },
                            ),
                        )
                        onSaved()
                    }
                },
            ) { Text("Save") }
        }
    }
}
