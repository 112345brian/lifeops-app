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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.text.font.FontFamily
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
    WidgetSection.NOTABLE_EVENTS -> "Notable events"
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

// Same package as BriefingWidget.kt -- reuses its MAX_TASKS_HARD_CEILING
// directly rather than a second hand-copied literal, so this slider can't
// drift from the real ceiling and let a user pick a value that
// allocateDynamicListCounts silently clamps back down.
private val MAX_TASKS_SLIDER_MAX = MAX_TASKS_HARD_CEILING.toFloat()
private val MAX_TASKS_SLIDER_STEPS = MAX_TASKS_HARD_CEILING - 2 // 1..N inclusive, N-2 intermediate steps

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
                            // Not user-toggleable from this screen -- carried
                            // through unchanged from whatever was loaded, so
                            // saving a "LifeOps Combo" instance's config
                            // (even just to force a redraw, see
                            // android/CLAUDE.md) doesn't silently revert it
                            // back to the generic section-by-section layout.
                            comboGrid = loaded.config.comboGrid,
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
                if (loaded.config.comboGrid) {
                    ComboGridPreview(state = loaded.state, order = order, hidden = hidden, scale = scale)
                } else {
                    WidgetPreview(
                        state = loaded.state,
                        nextTasks = loaded.nextTasks,
                        order = order,
                        hidden = hidden,
                        scale = scale,
                    )
                }
            }

            ConfigCard(
                title = "Sections",
                subtitle = if (loaded.config.comboGrid) {
                    "Combo shows the first cells that fit the placed size."
                } else {
                    "Toggle on/off, reorder with the arrows."
                },
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    order.forEachIndexed { index, section ->
                        if (loaded.config.comboGrid && section !in WidgetDisplayConfig.COMBO_GRID_SUPPORTED_SECTIONS) {
                            return@forEachIndexed
                        }
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

            if (!loaded.config.comboGrid) {
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
    gymLast7d = 3, gymTarget = 4, discretionaryDollars = 250, discretionaryTodayDollars = 40,
    courseworkHoursNext7d = 4.5,
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
    val previewState = if (usingSample) SAMPLE_STATE else state
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
                // The real widget's NotableEventsSection has its own
                // compact/list/empty-state branching (see BriefingWidget.kt)
                // that this preview doesn't attempt to fully mirror -- but it
                // must show SOMETHING, since previewState.notableEvents is
                // real loaded data the user can toggle on/off from this
                // screen same as any other section.
                WidgetSection.NOTABLE_EVENTS -> {
                    Column {
                        Text(text = "Coming up", color = PREVIEW_ON_BG, fontWeight = FontWeight.Bold,
                            fontSize = (11 * scale).sp)
                        if (previewState.notableEvents.isEmpty()) {
                            Text(text = "Nothing scheduled", color = PREVIEW_ON_BG_DIM, fontSize = (11 * scale).sp)
                        } else {
                            previewState.notableEvents.take(3).forEach {
                                Text(text = "📅 ${it.title}", color = PREVIEW_ON_BG, fontSize = (11 * scale).sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Plain-Compose approximation of ComboGridContent for the configure
 * screen. It honors the same combo-compatible section order/visibility and
 * reuses BriefingWidget's own presentation helpers so labels, values,
 * statuses, severity colors, and notable-event day formatting stay aligned
 * with the real Glance widget. It is not a full simulator for every
 * launcher span; the real widget chooses 2x2/3x2/4x2/taller variants from
 * LocalSize.current at render time. */
@Composable
private fun ComboGridPreview(state: BriefingState?, order: List<WidgetSection>, hidden: Set<WidgetSection>, scale: Float) {
    val usingSample = state?.text == null
    val previewState = if (usingSample) SAMPLE_STATE else state
    Column(verticalArrangement = Arrangement.spacedBy((6 * scale).dp)) {
        if (usingSample) {
            Text(
                text = "Sample data — nothing's synced to this widget yet",
                color = PREVIEW_ON_BG_DIM, fontSize = 10.sp,
            )
        }
        val moneySeverity = previewState.reasons.firstOrNull { it.domain == "money" }?.severity
        val courseworkSeverity = previewState.reasons.firstOrNull { it.domain == "coursework" }?.severity
        val socialItems = listOfNotNull(
            previewState.partnerDaysSince?.let {
                SocialItem("💜", "PARTNER", SocialMetric(it, previewState.partnerDaysUntil))
            },
            previewState.friendDaysSince?.let {
                SocialItem("👥", "FRIENDS", SocialMetric(it, previewState.friendDaysUntil))
            },
        )
        val visibleComboSections = order.filter {
            it !in hidden && it in WidgetDisplayConfig.COMBO_GRID_SUPPORTED_SECTIONS
        }
        val topStats = buildList {
            visibleComboSections.forEach { section ->
                when (section) {
                    WidgetSection.GYM_RING ->
                        if (previewState.gymLast7d != null && previewState.gymTarget != null) {
                            add(gymFallbackStatPresentation(previewState.gymLast7d, previewState.gymTarget))
                        }
                    WidgetSection.MONEY_TILE -> previewState.discretionaryDollars?.let {
                        add(moneyStatPresentation(it, moneySeverity, previewState.discretionaryTodayDollars))
                    }
                    WidgetSection.SOCIAL -> socialStatPresentation(socialItems, compact = false)?.let { add(it) }
                    WidgetSection.COURSEWORK_TILE -> previewState.courseworkHoursNext7d?.let {
                        add(courseworkStatPresentation(it, courseworkSeverity))
                    }
                    WidgetSection.SLEEP_TILE -> previewState.sleepMinutes?.let { add(sleepStatPresentation(it)) }
                    else -> Unit
                }
            }
        }
        val showWeather = WidgetSection.WEATHER in visibleComboSections && previewState.temperatureF != null
        val showEvents = WidgetSection.NOTABLE_EVENTS in visibleComboSections && previewState.notableEvents.isNotEmpty()
        val rowStats = topStats.take(2)
        val fullWidthStats = topStats.drop(2)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(280f / 220f)
                .clip(RoundedCornerShape(COMBO_OUTER_RADIUS))
                .background(COMBO_BG),
        ) {
            Column(modifier = Modifier.fillMaxHeight().weight(1f)) {
                if (rowStats.isNotEmpty()) {
                    Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
                        rowStats.forEachIndexed { index, stat ->
                            if (index > 0) ComboPreviewDivider()
                            ComboPreviewStatTile(stat, scale, Modifier.fillMaxHeight().weight(1f))
                        }
                    }
                }
                fullWidthStats.forEach { stat ->
                    if (rowStats.isNotEmpty()) ComboPreviewDividerHorizontal()
                    ComboPreviewStatTile(stat, scale, Modifier.fillMaxWidth().weight(1f))
                }
                if (showWeather) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            // Matches WeatherCard's WEATHER_BG (see
                            // PreviewWeatherCard's own comment for why this
                            // is a literal copy, not an import -- Glance's
                            // WEATHER_BG constant is file-private to
                            // BriefingWidget.kt and this preview is plain
                            // Compose, not Glance, anyway).
                            .background(Color(0xFF2F4D80))
                            .padding((4 * scale).dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(text = "${previewState.temperatureF}°F", color = Color.White, fontWeight = FontWeight.Bold,
                            fontSize = (COMBO_TILE_VALUE_SP * scale).sp)
                        Text(text = weatherEmoji(previewState.weatherCondition), fontSize = (COMBO_TILE_VALUE_SP * scale).sp)
                    }
                }
            }
            // See ComboEventsTile's own doc in BriefingWidget.kt: MONEY_SOLO_BG,
            // not MONEY_TILE_OK_BG -- a notable event has no severity, and
            // tinting the whole quadrant "ok"-green read as a false status
            // signal (confirmed 2026-07-15: "why is the upcoming events
            // green").
            if (showEvents) {
                ComboPreviewDivider()
                val eventsToShow = previewState.notableEvents.take(COMBO_EVENTS_SHOWN)
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(1f)
                        .background(MONEY_SOLO_BG)
                        .padding((8 * scale).dp),
                    verticalArrangement = Arrangement.spacedBy((4 * scale).dp),
                ) {
                    // "Coming up" header -- see ComboEventsTile's own doc for
                    // why a header-less centered "Nothing upcoming"/list read as
                    // adrift with nothing to anchor it (confirmed 2026-07-15 UI
                    // audit).
                    Text(text = "Coming up", color = PREVIEW_ON_BG, fontWeight = FontWeight.Bold,
                        fontSize = (COMBO_EVENTS_HEADER_SP * scale).sp)
                    if (eventsToShow.isEmpty()) {
                        Text(text = "Nothing scheduled", color = PREVIEW_ON_BG_DIM,
                            fontSize = (COMBO_TILE_VALUE_SP * scale).sp)
                    } else {
                        eventsToShow.forEach { event ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(text = notableEventDay(event), fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold, color = PREVIEW_ON_BG,
                                    fontSize = (COMBO_TILE_VALUE_SP * scale).sp,
                                    modifier = Modifier.width((26 * scale).dp))
                                notableEventTime(event)?.let { time ->
                                    Text(text = time, fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold, color = PREVIEW_ON_BG,
                                        fontSize = (COMBO_TILE_VALUE_SP * scale).sp,
                                        modifier = Modifier.width((54 * scale).dp))
                                }
                                Text(text = event.title, color = PREVIEW_ON_BG, fontWeight = FontWeight.Bold,
                                    maxLines = 1, fontSize = (COMBO_TILE_VALUE_SP * scale).sp)
                            }
                        }
                        val hiddenCount = previewState.notableEvents.size - eventsToShow.size
                        if (hiddenCount > 0) {
                            Text(text = "+$hiddenCount more", color = PREVIEW_ON_BG_DIM,
                                fontSize = (COMBO_TILE_VALUE_SP * scale).sp)
                        }
                    }
                }
            }
        }
    }
}

// Same literal sizes as SoloStatCard's own Glance-side defaults (8f/22f/9f/
// 5.dp) -- kept as its own copy since Glance's TextStyle/Compose's TextStyle
// are different types this preview can't share a constant with, same as
// every other *Preview composable's literal-copy comments on this screen.
private const val COMBO_PREVIEW_LABEL_SP = 8f
private const val COMBO_PREVIEW_VALUE_SP = 22f
private const val COMBO_PREVIEW_STATUS_SP = 9f
private val COMBO_PREVIEW_STATUS_PADDING = 5.dp

/** Plain-Compose twin of [SoloStatCard]'s label/value/status-bar shape --
 * every solo widget (Money/Coursework/Sleep/Social/Events) uses that shape
 * via Glance, and the combo grid's stat tiles now match it EXACTLY (see
 * ComboGridContent's docstring on why the old combo-specific shrunk sizes
 * are gone) rather than the old flat value-only [ComboPreviewTile] it
 * replaces, or the smaller COMBO_LABEL_SP/COMBO_STATUS_SP-based sizing this
 * used before 2026-07-15. */
@Composable
private fun ComboPreviewStatTile(stat: SoloStatPresentation, scale: Float, modifier: Modifier) {
    Column(
        modifier = modifier.background(MONEY_SOLO_BG),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text = stat.label, color = PREVIEW_ON_BG_DIM, fontWeight = FontWeight.Bold,
            fontSize = (COMBO_PREVIEW_LABEL_SP * scale).sp, maxLines = 1)
        Text(text = stat.value, color = PREVIEW_ON_BG, fontWeight = FontWeight.Bold,
            fontSize = (COMBO_PREVIEW_VALUE_SP * scale).sp, maxLines = 1,
            modifier = Modifier.padding(top = 1.dp))
        stat.secondary?.let {
            Text(text = it, color = PREVIEW_ON_BG_DIM, fontWeight = FontWeight.Bold,
                fontSize = (COMBO_PREVIEW_LABEL_SP * scale).sp, maxLines = 1,
                modifier = Modifier.padding(top = 1.dp))
        }
        Spacer(modifier = Modifier.height((4 * scale).dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(stat.accent)
                .padding(vertical = COMBO_PREVIEW_STATUS_PADDING),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = stat.status, color = Color(0xFF171A20), fontWeight = FontWeight.Bold,
                fontSize = (COMBO_PREVIEW_STATUS_SP * scale).sp, maxLines = 1)
        }
    }
}

// Plain-Compose twin of ComboTileDividerHorizontal.
@Composable
private fun ComboPreviewDividerHorizontal() {
    Spacer(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(COMBO_DIVIDER_COLOR),
    )
}

// Plain-Compose twin of ComboTileDivider -- see its doc for why this is an
// opaque hairline, not a transparent Spacer gap.
@Composable
private fun ComboPreviewDivider() {
    Spacer(
        modifier = Modifier
            .fillMaxHeight()
            .width(1.dp)
            .background(COMBO_DIVIDER_COLOR),
    )
}

/** [section]'s severity, mirroring exactly what [TileRow]'s own StatTile/
 * MoneyTile calls read for the real widget (state.reasons for money/
 * coursework, [sleepSeverity] for sleep, no severity dimension for gym --
 * see StatTile's own doc) -- so this preview can't silently disagree with
 * what color the real widget will actually render (confirmed 2026-07-15:
 * this tile used to always be flat neutral gray here regardless of
 * severity, for all four sections, which never matched the real widget --
 * Money's severity tint predates this fix, Coursework/Sleep's is new). */
private fun previewTileSeverity(section: WidgetSection, state: BriefingState): String? = when (section) {
    WidgetSection.MONEY_TILE -> state.reasons.firstOrNull { it.domain == "money" }?.severity
    WidgetSection.COURSEWORK_TILE -> state.reasons.firstOrNull { it.domain == "coursework" }?.severity
    WidgetSection.SLEEP_TILE -> state.sleepMinutes?.let(::sleepSeverity)
    else -> null
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
    val bg = when (previewTileSeverity(section, state)) {
        null -> PREVIEW_TILE_BG
        "risk", "fucked" -> MONEY_TILE_RISK_BG
        "watch" -> MONEY_TILE_WATCH_BG
        else -> MONEY_TILE_OK_BG
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
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
    // Mirrors the real WeatherCard in BriefingWidget.kt (temp+unit+hi/lo
    // on the left, condition glyph+label on the right) -- the earlier
    // version of this preview dropped high/low entirely, which made the
    // card look like it wasn't earning its full-width space (confirmed
    // 2026-07-13).
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            // Matches the real WeatherCard's WEATHER_BG -- kept as its
            // own literal here since the preview is plain Compose (not
            // Glance), not something that can import a GlanceModifier-side
            // color, but it's the same #2F4D80 app-accent blue, not a
            // separately-drifting value.
            .background(Color(0xFF2F4D80))
            .padding((10 * scale).dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        // Two flat rows (temp+unit, then high/low side by side) -- matches
        // WeatherCard in BriefingWidget.kt exactly. A Row with a
        // multi-line Column nested beside the big number looked fine here
        // (plain Compose) but broke on the real Glance-rendered widget, so
        // this preview switched to the same two-simple-rows shape the real
        // widget now uses rather than keep previewing a layout the actual
        // widget doesn't render (confirmed 2026-07-13). lineHeight is set
        // explicitly to match fontSize on every line -- Text() only
        // overriding fontSize keeps the inherited (much taller) MaterialTheme
        // default line height, which caused an earlier vertical gap bug.
        Column {
            // Top-aligned: matches WeatherCard -- the closest available
            // approximation of a top-right superscript given neither
            // Compose nor the real widget's Glance TextStyle support
            // baselineShift (confirmed 2026-07-13, user's call).
            Row(verticalAlignment = Alignment.Top) {
                Text(text = "${state.temperatureF ?: "--"}", color = Color.White, fontWeight = FontWeight.Bold,
                    fontSize = (28 * scale).sp)
                Text(text = "°F", color = Color.White, fontSize = (11 * scale).sp, lineHeight = (11 * scale).sp)
            }
            // Both on the same font size, matching WeatherCard's fix --
            // no reason for high/low to differ now that they're side by
            // side on one row instead of two differently-sized lines.
            Row {
                state.weatherHighF?.let {
                    Text(text = "↑${it}°", color = Color.White,
                        fontSize = (10 * scale).sp, lineHeight = (10 * scale).sp)
                }
                if (state.weatherHighF != null && state.weatherLowF != null) {
                    Spacer(modifier = Modifier.width((6 * scale).dp))
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
    Row(horizontalArrangement = Arrangement.spacedBy((6 * scale).dp)) {
        state.partnerDaysSince?.let {
            PreviewSocialChip("💜", SocialMetric(it, state.partnerDaysUntil), scale)
        }
        state.friendDaysSince?.let {
            PreviewSocialChip("👥", SocialMetric(it, state.friendDaysUntil), scale)
        }
    }
}

/** Plain-Compose twin of [SocialTile]'s rounded, metric-tinted chip -- this
 * preview used to render Social as bare emoji+text with no card/color at
 * all, the widest gap between this screen and the real widget of any
 * section (Money/Coursework/Sleep at least got a flat neutral tile; Social
 * got nothing). Reuses [SocialMetric.bgColor] directly rather than
 * re-deriving planned/overdue thresholds a third time (BriefingWidget.kt's
 * own [SocialTile] and [socialSeverity] already read the same property),
 * so this can't disagree with the real widget about what color a given
 * metric gets (confirmed 2026-07-15 audit: "ensure they are consistent with
 * each other and the UX principles"). */
@Composable
private fun PreviewSocialChip(emoji: String, metric: SocialMetric, scale: Float) {
    val bg = if (metric.bgColor == Color(0x00000000)) PREVIEW_TILE_BG else metric.bgColor
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .padding((6 * scale).dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = emoji, fontSize = (17 * scale).sp)
        Spacer(modifier = Modifier.width((4 * scale).dp))
        Text(text = metric.fullLabel, color = PREVIEW_ON_BG, fontWeight = FontWeight.Bold, fontSize = (15 * scale).sp)
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
