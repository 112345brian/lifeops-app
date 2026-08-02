package com.lifeops.briefing.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lifeops.briefing.data.LifeOpsDatabase
import com.lifeops.briefing.data.RoutineEntity
import kotlinx.coroutines.launch

/** List of every persisted [RoutineEntity] -- the "Recurring" tab. Tap one
 * to open [RoutineEditScreen]; the FAB starts a new one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutineListScreen(onOpenRoutine: (String) -> Unit, onNewRoutine: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var routines by remember { mutableStateOf(emptyList<RoutineEntity>()) }

    LaunchedEffect(Unit) {
        routines = LifeOpsDatabase.getInstance(context).routineDao().getAll()
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
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                        ) {
                            Text(text = routine.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(
                                text = "${routine.timesPerWindow}x / ${routine.perDays}d · ${routine.anchor}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(8.dp))
                            TextButton(onClick = { onOpenRoutine(routine.id) }) { Text("Edit") }
                        }
                    }
                }
            }
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
