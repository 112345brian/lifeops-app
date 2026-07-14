package com.lifeops.briefing

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import com.lifeops.briefing.data.AttentionReason
import com.lifeops.briefing.data.BriefingState
import com.lifeops.briefing.data.NextTask
import com.lifeops.briefing.data.NextTasksState
import com.lifeops.briefing.data.TodayEvent
import com.lifeops.briefing.data.WidgetDisplayConfig
import com.lifeops.briefing.data.WidgetSection
import kotlinx.coroutines.launch

/** Matches the web control panel's dark M3 palette (lifeops/templates/base.html's
 * CSS custom properties) so the two surfaces feel like the same product
 * instead of the configure screen looking like an unstyled default
 * MaterialTheme{} form dropped on top of a widget (confirmed 2026-07-13:
 * that mismatch was a big part of why this screen read as "fugly"). */
private val LifeOpsDarkColors = darkColorScheme(
    primary = Color(0xFFA9C7FF),
    onPrimary = Color(0xFF0A2F5C),
    primaryContainer = Color(0xFF2F4D80),
    onPrimaryContainer = Color(0xFFDAE5FF),
    secondaryContainer = Color(0xFF24262F),
    onSecondaryContainer = Color(0xFFE4E2E9),
    background = Color(0xFF101116),
    onBackground = Color(0xFFE4E2E9),
    surface = Color(0xFF101116),
    onSurface = Color(0xFFE4E2E9),
    surfaceVariant = Color(0xFF1A1C23),
    onSurfaceVariant = Color(0xFFA4A2AE),
    outline = Color(0xFF4A4B57),
    outlineVariant = Color(0xFF2C2E38),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF5C1A1A),
)

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
        // targetSdk 35+ forces edge-to-edge drawing regardless of this call,
        // but without it Compose's WindowInsets composition locals never got
        // real dispatched values here -- navigationBarsPadding() rendered as
        // 0dp even with this set (confirmed 2026-07-13 on a real Samsung
        // device: the Save button still sat directly under/behind the
        // 3-button nav bar's icons after adding navigationBarsPadding()).
        // Rather than keep guessing at *why* Compose's own insets dispatch
        // isn't reaching this Activity's window, navBarHeightDp() below
        // reads the nav bar's real height straight from the platform
        // resource instead -- doesn't depend on insets dispatch at all.
        WindowCompat.setDecorFitsSystemWindows(window, false)
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
        // Which provider (BriefingWidgetReceiver vs. a single-stat preset
        // like GymWidgetReceiver) placed this instance -- determines what
        // this screen should show pre-selected before anything's been saved.
        val providerClassName = AppWidgetManager.getInstance(this)
            .getAppWidgetInfo(appWidgetId)?.provider?.className
        val presetDefault = WidgetPresets.defaultConfigFor(providerClassName)

        val navBarHeightDp = navigationBarHeightDp()

        setContent {
            MaterialTheme(colorScheme = LifeOpsDarkColors) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    WidgetConfigScreen(
                        loadInitial = { loadInitialState(appWidgetId, glanceId, presetDefault) },
                        onSave = { config -> saveAndFinish(appWidgetId, glanceId, config) },
                        navBarHeightDp = navBarHeightDp,
                    )
                }
            }
        }
    }

    /** One DataStore read for everything the config screen needs: the
     * persisted display config (or [fallback] if none saved yet), and the
     * currently-persisted briefing/next-tasks snapshot (feeds [WidgetPreview]
     * real data when available). */
    private suspend fun loadInitialState(
        appWidgetId: Int,
        glanceId: GlanceId,
        fallback: WidgetDisplayConfig,
    ): ConfigScreenData {
        val prefs = getAppWidgetState(this, PreferencesGlanceStateDefinition, glanceId)
        val configJson = prefs[WidgetKeys.DISPLAY_CONFIG_JSON]
        val config = try {
            if (configJson != null) WidgetDisplayConfig.fromJson(configJson) else fallback
        } catch (e: org.json.JSONException) {
            fallback
        }
        val state = try {
            prefs[WidgetKeys.BRIEFING_JSON]?.let { BriefingState.fromJson(it) }
        } catch (e: org.json.JSONException) {
            null
        }
        val nextTasks = try {
            prefs[WidgetKeys.NEXT_TASKS_JSON]?.let { NextTasksState.fromJson(it) }
        } catch (e: org.json.JSONException) {
            null
        }
        return ConfigScreenData(config, state, nextTasks)
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

/** Bundles everything WidgetConfigScreen needs from one DataStore read: the
 * config to seed the form with, and the real persisted state/next-tasks
 * (nullable -- a freshly-placed widget has neither yet) [WidgetPreview]
 * renders live data from when available. */
private data class ConfigScreenData(
    val config: WidgetDisplayConfig,
    val state: BriefingState?,
    val nextTasks: NextTasksState?,
)

private fun sectionLabel(section: WidgetSection): String = when (section) {
    WidgetSection.SEVERITY_DOTS -> "Severity dots"
    WidgetSection.GYM_RING -> "Gym ring"
    WidgetSection.MONEY_TILE -> "Money tile"
    WidgetSection.COURSEWORK_TILE -> "Coursework tile"
    WidgetSection.BRIEFING_PARAGRAPH -> "Briefing text"
    WidgetSection.TODAY_EVENTS -> "Today's events"
    WidgetSection.UP_NEXT -> "Up next tasks"
    WidgetSection.WEATHER -> "Weather"
    WidgetSection.SLEEP_TILE -> "Sleep tile"
    WidgetSection.SOCIAL -> "Social (partner/friends)"
}

private fun <T> List<T>.moved(from: Int, to: Int): List<T> {
    val mutable = toMutableList()
    val item = mutable.removeAt(from)
    mutable.add(to, item)
    return mutable
}

/** Reads the 3-button/gesture nav bar's real height straight from the
 * platform resource, in dp -- see the [WidgetConfigActivity.onCreate]
 * comment for why this exists instead of Compose's own
 * navigationBarsPadding(). Falls back to 0dp (no bar, e.g. fully gestural
 * nav with no persistent bar) if the resource isn't present. */
private fun Activity.navigationBarHeightDp(): Dp {
    val resId = resources.getIdentifier("navigation_bar_height", "dimen", "android")
    val px = if (resId > 0) resources.getDimensionPixelSize(resId) else 0
    return (px / resources.displayMetrics.density).dp
}

private const val MAX_TASKS_SLIDER_MAX = 9f
private const val MAX_TASKS_SLIDER_STEPS = 7 // 1..9 inclusive, 7 intermediate steps

@Composable
private fun WidgetConfigScreen(
    loadInitial: suspend () -> ConfigScreenData,
    onSave: suspend (WidgetDisplayConfig) -> Unit,
    navBarHeightDp: Dp,
) {
    var initial by remember { mutableStateOf<ConfigScreenData?>(null) }
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

    var order by remember { mutableStateOf(loaded.config.sectionOrder) }
    var hidden by remember { mutableStateOf(loaded.config.hiddenSections) }
    var scale by remember { mutableStateOf(loaded.config.scale) }
    var maxTasksAuto by remember { mutableStateOf(loaded.config.maxTasksOverride == null) }
    var maxTasksValue by remember { mutableStateOf((loaded.config.maxTasksOverride ?: 3).toFloat()) }

    // Save lives in a Scaffold bottomBar, not as the last item in the
    // scrolling content -- on a long section list it used to be scrolled
    // out of reach until you happened to keep scrolling past everything
    // else; now it's always one tap away regardless of scroll position.
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            // Scaffold's bottomBar isn't automatically padded above the
            // system nav bar/gesture bar -- targetSdk 35+ enforces
            // edge-to-edge by default, so without this the Save button
            // rendered right underneath the 3-button nav bar's Home
            // button, unreachable. navBarHeightDp is read straight from the
            // platform resource (see [navigationBarHeightDp]), not from
            // Compose's own WindowInsets -- navigationBarsPadding() measured
            // 0dp here even after enabling edge-to-edge (confirmed
            // 2026-07-13 on a real Samsung device: the button still
            // rendered directly behind the nav bar's icons).
            Surface(
                shadowElevation = 8.dp,
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.padding(bottom = navBarHeightDp),
            ) {
                Button(
                    onClick = {
                        val config = WidgetDisplayConfig(
                            sectionOrder = order,
                            hiddenSections = hidden,
                            scale = scale,
                            maxTasksOverride = if (maxTasksAuto) null else maxTasksValue.toInt(),
                        )
                        // rememberCoroutineScope, not GlobalScope -- tied to
                        // this composable's lifecycle so it can't leak past
                        // the screen being torn down mid-save.
                        scope.launch { onSave(config) }
                    },
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                ) {
                    Text("Save")
                }
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = "Widget setup", style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold)

            ConfigCard(title = "Preview") {
                WidgetPreview(
                    state = loaded.state,
                    nextTasks = loaded.nextTasks,
                    order = order,
                    hidden = hidden,
                    scale = scale,
                )
            }

            ConfigCard(title = "Sections", subtitle = "Toggle on/off, reorder with the arrows.") {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    order.forEachIndexed { index, section ->
                        if (index > 0) HorizontalDivider()
                        SectionRow(
                            label = sectionLabel(section),
                            enabled = section !in hidden,
                            onToggle = { checked ->
                                hidden = if (checked) hidden - section else hidden + section
                            },
                            onMoveUp = { order = order.moved(index, index - 1) },
                            onMoveDown = { order = order.moved(index, index + 1) },
                            moveUpEnabled = index > 0,
                            moveDownEnabled = index < order.lastIndex,
                        )
                    }
                }
            }

            ConfigCard(title = "Font & icon size", subtitle = "${"%.2f".format(scale)}x") {
                Slider(value = scale, onValueChange = { scale = it },
                    valueRange = WidgetDisplayConfig.MIN_SCALE..WidgetDisplayConfig.MAX_SCALE)
            }

            ConfigCard(title = "Task list") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text = "Auto-size to the widget's placed height", modifier = Modifier.weight(1f))
                    Switch(checked = maxTasksAuto, onCheckedChange = { maxTasksAuto = it })
                }
                if (!maxTasksAuto) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = "Max tasks shown: ${maxTasksValue.toInt()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Slider(
                        value = maxTasksValue,
                        onValueChange = { maxTasksValue = it },
                        valueRange = 1f..MAX_TASKS_SLIDER_MAX,
                        steps = MAX_TASKS_SLIDER_STEPS,
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

/** One card, consistent title/subtitle/content styling and spacing --
 * every section of the screen (Preview/Sections/Font/Task list) wraps in
 * one of these instead of being loose Text+content siblings directly on
 * the page background, which read as an unstyled form rather than a
 * designed screen (confirmed 2026-07-13). */
@Composable
private fun ConfigCard(title: String, subtitle: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Column {
                Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (subtitle != null) {
                    Text(text = subtitle, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            content()
        }
    }
}

@Composable
private fun SectionRow(
    label: String,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    moveUpEnabled: Boolean,
    moveDownEnabled: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        IconButton(onClick = onMoveUp, enabled = moveUpEnabled, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Move up",
                tint = if (moveUpEnabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outlineVariant)
        }
        IconButton(onClick = onMoveDown, enabled = moveDownEnabled, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Move down",
                tint = if (moveDownEnabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outlineVariant)
        }
        Spacer(modifier = Modifier.width(4.dp))
        Switch(checked = enabled, onCheckedChange = onToggle)
    }
}

/** Sample values shown when no real BriefingState has synced yet (e.g. a
 * widget placed before the first daily briefing has ever landed) -- so the
 * preview still means something on first configure, not just a blank box.
 * Deliberately NOT wired into the real BriefingWidget/BriefingContent
 * rendering path -- this is a plain-Compose approximation of that layout,
 * not a literal re-render of it (Glance composables run in a special
 * AppWidget composition that outputs RemoteViews, not something this
 * regular Activity's Compose tree can embed directly). Good enough to
 * show section order/visibility/grouping/scale at a glance; not intended
 * to be pixel-identical to the real widget. */
private val SAMPLE_STATE = BriefingState(
    date = null, text = "Sample briefing text — this is what your paragraph will look like.",
    attentionState = "watch", attentionSymbol = "◆", attentionLabel = "WATCH",
    gymLast7d = 3, gymTarget = 4, discretionaryDollars = 250, courseworkHoursNext7d = 4.5,
    temperatureF = 72, weatherHighF = 80, weatherLowF = 60, weatherCondition = "Sunny",
    sleepMinutes = 420, partnerDaysSince = 2, friendDaysSince = 5, friendDaysUntil = 4,
    reasons = listOf(AttentionReason("coursework", "risk"), AttentionReason("money", "watch")),
)
private val SAMPLE_TASKS = NextTasksState(
    tasks = listOf(
        NextTask(id = "sample-1", title = "Sample task", start = null),
        NextTask(id = "sample-2", title = "Another task", start = null),
    ),
    events = listOf(TodayEvent(title = "Sample event", start = null)),
)

private val PREVIEW_BG = Color(0xFF1C1C1E)
private val PREVIEW_ON_BG = Color(0xFFECECEC)
private val PREVIEW_ON_BG_DIM = Color(0xFF9A9A9A)
private val PREVIEW_TILE_BG = Color(0xFF2C2C2E)

@Composable
private fun WidgetPreview(
    state: BriefingState?,
    nextTasks: NextTasksState?,
    order: List<WidgetSection>,
    hidden: Set<WidgetSection>,
    scale: Float,
) {
    val usingSample = state?.text == null
    val previewState = if (usingSample) SAMPLE_STATE else state!!
    val previewTasks = if (usingSample) SAMPLE_TASKS else (nextTasks ?: NextTasksState.empty())
    val visible = order.filter { it !in hidden }
    // Same rule as BriefingContent's dotsInline: severity dots ride on the
    // badge's own row only when left in their default (first) position;
    // moved anywhere else, they get their own standalone row instead.
    val dotsInline = visible.firstOrNull() == WidgetSection.SEVERITY_DOTS
    // Text-gated sections vanish until a real briefing has landed, same as
    // BriefingContent -- reuses TEXT_GATED_SECTIONS so this can't silently
    // drift from what the placed widget actually does. Deliberately does
    // NOT also apply the real widget's SMALL-bucket/LARGE-only gating: an
    // earlier version did, using the widget's CURRENT placed size -- but a
    // freshly-added widget on this launcher gets dropped at a small default
    // footprint before the user has touched it, so that gating hid every
    // section the user had just toggled on, contradicting their own choice
    // (confirmed 2026-07-13, live device: "Briefing text" toggled on, never
    // appeared in preview). The Sections list below already gives the user
    // full manual control over what shows; the preview's job is to render
    // that choice honestly, not re-guess it from an incidental placement size.
    val renderableOrder = visible.filter { section ->
        !(section == WidgetSection.SEVERITY_DOTS && dotsInline) &&
            !(previewState.text == null && section in TEXT_GATED_SECTIONS)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(PREVIEW_BG)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy((6 * scale).dp),
    ) {
        if (usingSample) {
            Text(
                text = "Sample data — nothing's synced to this widget yet",
                color = PREVIEW_ON_BG_DIM, fontSize = 10.sp,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "${previewState.attentionSymbol ?: "●"} ${previewState.attentionLabel ?: "OK"}",
                color = PREVIEW_ON_BG, fontWeight = FontWeight.Bold, fontSize = (14 * scale).sp,
            )
            if (dotsInline && previewState.reasons.isNotEmpty()) {
                Spacer(modifier = Modifier.width((8 * scale).dp))
                PreviewSeverityDots(previewState.reasons, scale)
            }
        }

        // groupSectionsForRendering (shared with the real widget) only
        // batches a CONTIGUOUS run of tile sections into one row -- unlike
        // the old version of this preview, which grouped every tile
        // section together regardless of position, misrepresenting what
        // reordering actually does (confirmed 2026-07-13).
        for (group in groupSectionsForRendering(renderableOrder)) {
            if (group.size > 1) {
                Row(horizontalArrangement = Arrangement.spacedBy((6 * scale).dp)) {
                    group.forEach { PreviewTile(it, previewState, scale) }
                }
                continue
            }
            when (val section = group.first()) {
                WidgetSection.SEVERITY_DOTS -> if (previewState.reasons.isNotEmpty()) {
                    PreviewSeverityDots(previewState.reasons, scale)
                }
                WidgetSection.GYM_RING, WidgetSection.MONEY_TILE, WidgetSection.COURSEWORK_TILE,
                WidgetSection.SLEEP_TILE -> PreviewTile(section, previewState, scale)
                WidgetSection.WEATHER -> PreviewWeatherCard(previewState, scale)
                WidgetSection.SOCIAL -> PreviewSocialRow(previewState, scale)
                WidgetSection.BRIEFING_PARAGRAPH ->
                    Text(
                        text = previewState.text ?: "",
                        color = PREVIEW_ON_BG, fontSize = (12 * scale).sp,
                        maxLines = 3,
                    )
                WidgetSection.TODAY_EVENTS -> previewTasks.events.forEach {
                    Text(text = "📅 ${it.title}", color = PREVIEW_ON_BG, fontSize = (11 * scale).sp)
                }
                WidgetSection.UP_NEXT -> if (previewTasks.tasks.isNotEmpty()) {
                    Column {
                        Text(text = "Up next", color = PREVIEW_ON_BG, fontWeight = FontWeight.Bold,
                            fontSize = (11 * scale).sp)
                        previewTasks.tasks.take(3).forEach {
                            Text(text = "☐ ${it.title}", color = PREVIEW_ON_BG, fontSize = (11 * scale).sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewTile(section: WidgetSection, state: BriefingState, scale: Float) {
    val (emoji, value) = when (section) {
        WidgetSection.GYM_RING -> "🏋" to "${state.gymLast7d ?: 0}/${state.gymTarget ?: 0}"
        WidgetSection.MONEY_TILE -> "" to formatMoney(state.discretionaryDollars ?: 0)
        WidgetSection.COURSEWORK_TILE -> "📚" to "${state.courseworkHoursNext7d ?: 0}h"
        WidgetSection.SLEEP_TILE -> "😴" to formatSleepDuration(state.sleepMinutes ?: 0)
        else -> "" to ""
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(PREVIEW_TILE_BG)
            .padding((6 * scale).dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (emoji.isNotEmpty()) {
            Text(text = emoji, fontSize = (12 * scale).sp)
            Spacer(modifier = Modifier.width((3 * scale).dp))
        }
        Text(text = value, color = PREVIEW_ON_BG, fontWeight = FontWeight.Bold, fontSize = (12 * scale).sp)
    }
}

@Composable
private fun PreviewWeatherCard(state: BriefingState, scale: Float) {
    // Mirrors the real WeatherSection in BriefingWidget.kt (temp+unit+hi/lo
    // on the left, condition glyph+label on the right) -- the earlier
    // version of this preview dropped high/low entirely, which made the
    // card look like it wasn't earning its full-width space (confirmed
    // 2026-07-13).
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            // Matches the real WeatherSection's WEATHER_BG -- kept as its
            // own literal here since the preview is plain Compose (not
            // Glance), not something that can import a GlanceModifier-side
            // color, but it's the same #2F4D80 app-accent blue, not a
            // separately-drifting value.
            .background(Color(0xFF2F4D80))
            .padding((10 * scale).dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Text(text = "${state.temperatureF ?: "--"}", color = Color.White, fontWeight = FontWeight.Bold,
                fontSize = (28 * scale).sp)
            Text(text = "°F", color = Color.White, fontSize = (11 * scale).sp, lineHeight = (11 * scale).sp)
            // High/low get their own column, arrows flush left against each
            // other -- matches WeatherSection in BriefingWidget.kt: putting
            // "°F" on the same line as "↑85°" pushed the up-arrow in by
            // "°F"'s width while "↓67°" started at the column's own left
            // edge, so the two arrows never lined up (confirmed 2026-07-13,
            // live device screenshot). lineHeight is set explicitly to match
            // fontSize on every line here -- Text() only overriding fontSize
            // keeps the inherited (much taller) MaterialTheme default line
            // height, which is what caused the original vertical gap.
            Column {
                state.weatherHighF?.let {
                    Text(text = "↑${it}°", color = Color.White,
                        fontSize = (11 * scale).sp, lineHeight = (11 * scale).sp)
                }
                state.weatherLowF?.let {
                    Text(text = "↓${it}°", color = Color.White,
                        fontSize = (10 * scale).sp, lineHeight = (10 * scale).sp)
                }
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = weatherEmoji(state.weatherCondition), fontSize = (22 * scale).sp)
            state.weatherCondition?.let { Text(text = it, color = Color.White, fontSize = (10 * scale).sp) }
        }
    }
}

@Composable
private fun PreviewSocialRow(state: BriefingState, scale: Float) {
    fun label(daysSince: Int?, daysUntil: Int?): String? =
        daysSince?.let { s -> daysUntil?.let { u -> "${s}d→${u}d" } ?: "${s}d" }
    Row(horizontalArrangement = Arrangement.spacedBy((6 * scale).dp)) {
        label(state.partnerDaysSince, state.partnerDaysUntil)?.let {
            Text(text = "💜 $it", color = PREVIEW_ON_BG, fontSize = (11 * scale).sp)
        }
        label(state.friendDaysSince, state.friendDaysUntil)?.let {
            Text(text = "👥 $it", color = PREVIEW_ON_BG, fontSize = (11 * scale).sp)
        }
    }
}

/** Reuses BriefingWidget's own domain order/ranking/color logic
 * (DOT_DOMAIN_ORDER, worstSeverityByDomain, severityDotColor -- made
 * internal specifically for this) rather than re-deriving "which domain is
 * worst" a second time, so the preview can never disagree with the real
 * widget about what a dot's color means. Only the rendering itself
 * (a plain Compose circle vs. Glance's bitmap-Image trick) differs. */
@Composable
private fun PreviewSeverityDots(reasons: List<AttentionReason>, scale: Float) {
    val worstByDomain = worstSeverityByDomain(reasons)
    Row(horizontalArrangement = Arrangement.spacedBy((5 * scale).dp), verticalAlignment = Alignment.CenterVertically) {
        DOT_DOMAIN_ORDER.forEach { domain ->
            val severity = worstByDomain[domain] ?: "ok"
            Box(
                modifier = Modifier
                    .size((7 * scale).dp)
                    .clip(RoundedCornerShape(50))
                    .background(severityDotColor(severity)),
            )
        }
    }
}
