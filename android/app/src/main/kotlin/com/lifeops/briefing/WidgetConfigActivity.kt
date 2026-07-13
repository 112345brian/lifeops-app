package com.lifeops.briefing

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import com.lifeops.briefing.data.WidgetDisplayConfig
import com.lifeops.briefing.data.WidgetSection
import kotlinx.coroutines.launch

/** Per-widget-instance display customization screen -- Android's standard
 * AppWidget "configure" activity, launched by the App Widget host whenever
 * this widget is added or the user re-opens its configuration (see
 * android:configure on briefing_widget_info.xml + the
 * ACTION_APPWIDGET_CONFIGURE intent-filter in AndroidManifest.xml). Follows
 * the required contract: RESULT_CANCELED is set immediately so backing out
 * mid-configuration doesn't leave a half-placed widget behind; RESULT_OK is
 * only set once the user actually saves.
 *
 * Persists to the same per-GlanceId Glance Preferences DataStore
 * BriefingState/NextTasksState already use (WidgetKeys.DISPLAY_CONFIG_JSON)
 * -- not the separate global WidgetConfigStore (that's for the secret
 * panel URL/token, shared across every instance; this is per-instance
 * display preference, non-secret). */
class WidgetConfigActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Must happen before any UI work -- if the user backs out without
        // saving, the App Widget host must not add the widget.
        setResult(Activity.RESULT_CANCELED)

        val appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        actionBar?.hide()
        val glanceId = GlanceAppWidgetManager(this).getGlanceIdBy(appWidgetId)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    WidgetConfigScreen(
                        loadInitial = { loadDisplayConfig(glanceId) },
                        onSave = { config -> saveAndFinish(appWidgetId, glanceId, config) },
                    )
                }
            }
        }
    }

    private suspend fun loadDisplayConfig(glanceId: GlanceId): WidgetDisplayConfig {
        val prefs = getAppWidgetState(this, PreferencesGlanceStateDefinition, glanceId)
        val json = prefs[WidgetKeys.DISPLAY_CONFIG_JSON]
        return try {
            if (json != null) WidgetDisplayConfig.fromJson(json) else WidgetDisplayConfig.default()
        } catch (e: org.json.JSONException) {
            WidgetDisplayConfig.default()
        }
    }

    private suspend fun saveAndFinish(appWidgetId: Int, glanceId: GlanceId, config: WidgetDisplayConfig) {
        updateAppWidgetState(this, glanceId) { prefs ->
            prefs[WidgetKeys.DISPLAY_CONFIG_JSON] = config.toJson()
        }
        BriefingWidget().update(this, glanceId)
        setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
        finish()
    }
}

private fun sectionLabel(section: WidgetSection): String = when (section) {
    WidgetSection.SEVERITY_DOTS -> "Severity dots"
    WidgetSection.GYM_RING -> "Gym ring"
    WidgetSection.MONEY_TILE -> "Money tile"
    WidgetSection.COURSEWORK_TILE -> "Coursework tile"
    WidgetSection.BRIEFING_PARAGRAPH -> "Briefing text"
    WidgetSection.TODAY_EVENTS -> "Today's events"
    WidgetSection.UP_NEXT -> "Up next tasks"
}

private fun <T> List<T>.moved(from: Int, to: Int): List<T> {
    val mutable = toMutableList()
    val item = mutable.removeAt(from)
    mutable.add(to, item)
    return mutable
}

private const val MAX_TASKS_SLIDER_MAX = 9f
private const val MAX_TASKS_SLIDER_STEPS = 7 // 1..9 inclusive, 7 intermediate steps

@Composable
private fun WidgetConfigScreen(
    loadInitial: suspend () -> WidgetDisplayConfig,
    onSave: suspend (WidgetDisplayConfig) -> Unit,
) {
    var initial by remember { mutableStateOf<WidgetDisplayConfig?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        initial = loadInitial()
    }

    val loaded = initial
    if (loaded == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    var order by remember { mutableStateOf(loaded.sectionOrder) }
    var hidden by remember { mutableStateOf(loaded.hiddenSections) }
    var scale by remember { mutableStateOf(loaded.scale) }
    var maxTasksAuto by remember { mutableStateOf(loaded.maxTasksOverride == null) }
    var maxTasksValue by remember { mutableStateOf((loaded.maxTasksOverride ?: 3).toFloat()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = "Widget layout", style = MaterialTheme.typography.titleLarge)
        Text(text = "Toggle sections on/off, reorder with ▲▼.",
            style = MaterialTheme.typography.bodySmall)

        order.forEachIndexed { index, section ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Checkbox(
                    checked = section !in hidden,
                    onCheckedChange = { checked ->
                        hidden = if (checked) hidden - section else hidden + section
                    },
                )
                Text(text = sectionLabel(section), modifier = Modifier.weight(1f))
                TextButton(onClick = { order = order.moved(index, index - 1) }, enabled = index > 0) {
                    Text("▲")
                }
                TextButton(onClick = { order = order.moved(index, index + 1) }, enabled = index < order.lastIndex) {
                    Text("▼")
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(text = "Font & icon size: ${"%.2f".format(scale)}x")
        Slider(value = scale, onValueChange = { scale = it }, valueRange = 0.85f..1.3f)

        Spacer(modifier = Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = maxTasksAuto, onCheckedChange = { maxTasksAuto = it })
            Text(text = "Auto-size \"Up next\" task count")
        }
        if (!maxTasksAuto) {
            Text(text = "Max tasks shown: ${maxTasksValue.toInt()}")
            Slider(
                value = maxTasksValue,
                onValueChange = { maxTasksValue = it },
                valueRange = 1f..MAX_TASKS_SLIDER_MAX,
                steps = MAX_TASKS_SLIDER_STEPS,
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = {
                val config = WidgetDisplayConfig(
                    sectionOrder = order,
                    hiddenSections = hidden,
                    scale = scale,
                    maxTasksOverride = if (maxTasksAuto) null else maxTasksValue.toInt(),
                )
                // rememberCoroutineScope, not GlobalScope -- tied to this
                // composable's lifecycle so it can't leak past the screen
                // being torn down mid-save.
                scope.launch { onSave(config) }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Save")
        }
    }
}
