package com.lifeops.briefing.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.lifeops.briefing.data.HistoryEventEntity
import com.lifeops.briefing.data.LifeOpsDatabase
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val HISTORY_FORMATTER = DateTimeFormatter.ofPattern("MMM d, HH:mm")

/** Thin, read-only "History" page -- every logged [HistoryEventEntity]
 * (gym/chore/social/meal completions and skips), most recent first. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var events by remember { mutableStateOf(emptyList<HistoryEventEntity>()) }

    LaunchedEffect(Unit) {
        events = LifeOpsDatabase.getInstance(context).historyEventDao().getAll().sortedByDescending { it.timestampEpochMillis }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("History") }) }) { padding ->
        if (events.isEmpty()) {
            Column(modifier = modifier.fillMaxSize().padding(padding).padding(24.dp)) {
                Text(
                    text = "No history recorded on this device yet.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            LazyColumn(modifier = modifier.fillMaxSize().padding(padding)) {
                items(events, key = { it.id }) { event ->
                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Row {
                            Text(text = event.domain.uppercase(), style = MaterialTheme.typography.labelLarge)
                            Text(
                                text = "  ${event.eventType}",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            text = Instant.ofEpochMilli(event.timestampEpochMillis)
                                .atZone(ZoneId.systemDefault())
                                .format(HISTORY_FORMATTER),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
