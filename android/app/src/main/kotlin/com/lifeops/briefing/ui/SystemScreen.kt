package com.lifeops.briefing.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.unit.dp
import com.lifeops.briefing.PanelActivity
import com.lifeops.briefing.SettingsActivity
import com.lifeops.briefing.WidgetConfigStore
import com.lifeops.briefing.WidgetKeys
import com.lifeops.briefing.authenticatedUrl
import com.lifeops.briefing.httpRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class SystemStatus(
    val panelConfigured: Boolean,
    val flowSavvyConfigured: Boolean,
    val briefingFetchedAt: Long?,
    val nextTasksFetchedAt: Long?,
    val ynabRefreshStatus: String?,
    val ynabRefreshAt: Long?,
    val locationReportAt: Long?,
    val weatherFetchAt: Long?,
)

private sealed interface PanelCheck {
    data object Idle : PanelCheck
    data object Checking : PanelCheck
    data class Reachable(val fcmRegistered: Boolean) : PanelCheck
    data class Unreachable(val message: String) : PanelCheck
}

private fun timeAgo(epochMillis: Long?): String {
    if (epochMillis == null) return "never"
    val ageMs = System.currentTimeMillis() - epochMillis
    val mins = ageMs / 60_000
    return when {
        mins < 1 -> "just now"
        mins < 60 -> "${mins}m ago"
        mins < 24 * 60 -> "${mins / 60}h ago"
        else -> SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(epochMillis))
    }
}

private fun loadStatus(context: Context): SystemStatus {
    val ynabGate = context.getSharedPreferences(WidgetKeys.YNAB_REFRESH_PREFS_NAME, Context.MODE_PRIVATE)
    val locationGate = context.getSharedPreferences(WidgetKeys.LOCATION_PREFS_NAME, Context.MODE_PRIVATE)
    val weatherGate = context.getSharedPreferences(WidgetKeys.WEATHER_PREFS_NAME, Context.MODE_PRIVATE)
    return SystemStatus(
        panelConfigured = WidgetConfigStore.getBaseUrl(context) != null && WidgetConfigStore.getToken(context) != null,
        flowSavvyConfigured = WidgetConfigStore.getFlowSavvyBaseUrl(context) != null &&
            WidgetConfigStore.getFlowSavvyToken(context) != null,
        briefingFetchedAt = null,
        nextTasksFetchedAt = null,
        ynabRefreshStatus = ynabGate.getString(WidgetKeys.KEY_LAST_YNAB_REFRESH_STATUS, null),
        ynabRefreshAt = ynabGate.getLong(WidgetKeys.KEY_LAST_YNAB_REFRESH_AT, 0L).takeIf { it > 0 },
        locationReportAt = locationGate.getLong(WidgetKeys.KEY_LAST_LOCATION_REPORT_AT, 0L).takeIf { it > 0 },
        weatherFetchAt = weatherGate.getLong(WidgetKeys.KEY_LAST_WEATHER_FETCH_AT, 0L).takeIf { it > 0 },
    )
}

/**
 * System screen: connection status (panel reachable, last sync, FCM token
 * registered) plus stale/error status for the on-device gates (YNAB/
 * location/weather) that already existed before this screen -- reusing
 * their existing SharedPreferences keys (see [WidgetKeys]) rather than
 * inventing a second status-tracking mechanism.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SystemScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf(loadStatus(context)) }
    var todayData by remember { mutableStateOf(TodayData.EMPTY) }
    var panelCheck by remember { mutableStateOf<PanelCheck>(PanelCheck.Idle) }

    LaunchedEffect(Unit) {
        status = loadStatus(context)
        todayData = TodayRepository.load(context)
    }

    Scaffold(topBar = { TopAppBar(title = { Text("System") }) }) { padding ->
        Column(modifier = modifier.fillMaxSize().padding(padding).padding(20.dp)) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(text = "Connection", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    StatusLine("Panel configured", if (status.panelConfigured) "yes" else "no, open Settings")
                    StatusLine("FlowSavvy configured", if (status.flowSavvyConfigured) "yes" else "no")
                    StatusLine("Briefing last synced", timeAgo(todayData.briefing.fetchedAtEpochMillis))
                    StatusLine("Next tasks last synced", timeAgo(todayData.nextTasks.fetchedAtEpochMillis))
                    when (val check = panelCheck) {
                        is PanelCheck.Idle -> {}
                        is PanelCheck.Checking -> StatusLine("Panel reachability", "checking...")
                        is PanelCheck.Reachable -> StatusLine(
                            "Panel reachability",
                            "reachable, FCM token registered: ${if (check.fcmRegistered) "yes" else "no"}",
                        )
                        is PanelCheck.Unreachable -> StatusLine("Panel reachability", "unreachable: ${check.message}")
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = {
                        panelCheck = PanelCheck.Checking
                        scope.launch {
                            panelCheck = checkPanelHealth(context)
                        }
                    }) { Text("Check panel now") }
                }
            }

            Spacer(Modifier.height(16.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(text = "Background gates", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    StatusLine("YNAB refresh", "${status.ynabRefreshStatus ?: "never"}, ${timeAgo(status.ynabRefreshAt)}")
                    StatusLine("Location report", timeAgo(status.locationReportAt))
                    StatusLine("Phone weather fetch", timeAgo(status.weatherFetchAt))
                }
            }

            Spacer(Modifier.height(20.dp))
            Row {
                Button(onClick = {
                    TodayRepository.forceRefresh(context)
                }) { Text("Force refresh") }
                Spacer(Modifier.height(0.dp))
                OutlinedButton(onClick = {
                    context.startActivity(Intent(context, SettingsActivity::class.java))
                }) { Text("Settings") }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    val baseUrl = WidgetConfigStore.getBaseUrl(context)
                    if (baseUrl != null) context.startActivity(PanelActivity.intent(context, baseUrl))
                },
                enabled = status.panelConfigured,
            ) { Text("Open web panel") }
        }
    }
}

@Composable
private fun StatusLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(text = "$label: ", style = MaterialTheme.typography.bodyMedium)
        Text(text = value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private suspend fun checkPanelHealth(context: Context): PanelCheck = withContext(Dispatchers.IO) {
    val baseUrl = WidgetConfigStore.getBaseUrl(context) ?: return@withContext PanelCheck.Unreachable("not configured")
    val token = WidgetConfigStore.getToken(context) ?: return@withContext PanelCheck.Unreachable("not configured")
    try {
        val body = httpRequest(
            url = authenticatedUrl(baseUrl, "/api/health", token),
            method = "GET",
            connectTimeoutMs = 5_000,
            readTimeoutMs = 5_000,
        )
        val json = JSONObject(body)
        PanelCheck.Reachable(fcmRegistered = json.optBoolean("fcm_token_registered", false))
    } catch (e: IOException) {
        PanelCheck.Unreachable(e.message ?: "connection failed")
    }
}
