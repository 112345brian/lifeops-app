package com.lifeops.briefing

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
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
        // Which provider (BriefingWidgetReceiver vs. a single-stat preset
        // like GymWidgetReceiver) placed this instance -- determines what
        // this screen should show pre-selected before anything's been saved.
        val providerClassName = AppWidgetManager.getInstance(this)
            .getAppWidgetInfo(appWidgetId)?.provider?.className
        val presetDefault = WidgetPresets.defaultConfigFor(providerClassName)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    WidgetConfigScreen(
                        loadInitial = { loadInitialState(appWidgetId, glanceId, presetDefault) },
                        onSave = { config -> saveAndFinish(appWidgetId, glanceId, config) },
                    )
                }
            }
        }
    }

    /** One DataStore read for everything the config screen needs: the
     * persisted display config (or [fallback] if none saved yet), the
     * currently-persisted briefing/next-tasks snapshot (feeds [WidgetPreview]
     * real data when available), and the instance's actual size bucket --
     * see [resolveSizeBucket]. */
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
        val bucket = resolveSizeBucket(appWidgetId, glanceId)
        return ConfigScreenData(config, state, nextTasks, bucket)
    }

    /** The preview used to render every section regardless of what size the
     * widget was actually placed at -- misrepresenting what a small
     * single-stat preset (or any widget resized down) will actually show
     * (confirmed 2026-07-13). Prefers the real placed size
     * (GlanceAppWidgetManager.getAppWidgetSizes, populated once the App
     * Widget host has actually bound and sized this instance -- works for
     * re-opening configure on an ALREADY-placed widget); falls back to the
     * provider's declared minWidth/minHeight for a widget being configured
     * for the very first time, before any size is known yet. Reuses the
     * exact same [bucketFor] the real widget uses, not a re-derived
     * approximation. */
    private suspend fun resolveSizeBucket(appWidgetId: Int, glanceId: GlanceId): WidgetSizeBucket {
        val sizes = try {
            GlanceAppWidgetManager(this).getAppWidgetSizes(glanceId)
        } catch (e: Exception) {
            emptyList()
        }
        val size = sizes.maxByOrNull { it.width.value * it.height.value } ?: run {
            val info = AppWidgetManager.getInstance(this).getAppWidgetInfo(appWidgetId)
            if (info != null) DpSize(info.minWidth.dp, info.minHeight.dp) else DpSize(250.dp, 250.dp)
        }
        return bucketFor(size)
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
 * config to seed the form with, the real persisted state/next-tasks
 * (nullable -- a freshly-placed widget has neither yet) [WidgetPreview]
 * renders live data from when available, and the instance's actual size
 * bucket (see [WidgetConfigActivity.resolveSizeBucket]) so the preview only
 * shows what that size will actually render. */
private data class ConfigScreenData(
    val config: WidgetDisplayConfig,
    val state: BriefingState?,
    val nextTasks: NextTasksState?,
    val bucket: WidgetSizeBucket,
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

private const val MAX_TASKS_SLIDER_MAX = 9f
private const val MAX_TASKS_SLIDER_STEPS = 7 // 1..9 inclusive, 7 intermediate steps

@Composable
private fun WidgetConfigScreen(
    loadInitial: suspend () -> ConfigScreenData,
    onSave: suspend (WidgetDisplayConfig) -> Unit,
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = "Preview", style = MaterialTheme.typography.titleLarge)
        WidgetPreview(
            state = loaded.state,
            nextTasks = loaded.nextTasks,
            order = order,
            hidden = hidden,
            scale = scale,
            bucket = loaded.bucket,
        )

        Spacer(modifier = Modifier.height(8.dp))
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
        Slider(value = scale, onValueChange = { scale = it },
            valueRange = WidgetDisplayConfig.MIN_SCALE..WidgetDisplayConfig.MAX_SCALE)

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
    sleepMinutes = 420, partnerDaysSince = 2, friendDaysSince = 5,
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
    bucket: WidgetSizeBucket,
) {
    val usingSample = state?.text == null
    val previewState = if (usingSample) SAMPLE_STATE else state!!
    val previewTasks = if (usingSample) SAMPLE_TASKS else (nextTasks ?: NextTasksState.empty())
    val visible = order.filter { it !in hidden }
    // Same rule as BriefingContent's dotsInline: severity dots ride on the
    // badge's own row only when left in their default (first) position;
    // moved anywhere else, they get their own standalone row instead.
    val dotsInline = visible.firstOrNull() == WidgetSection.SEVERITY_DOTS
    // Same gating BriefingContent applies: text-gated sections vanish
    // until a real briefing has landed, and at SMALL only the compact
    // tile sections survive alongside the badge -- reusing the real
    // TEXT_GATED_SECTIONS/TILE_SECTIONS/bucket check (not a re-derived
    // copy) so this can't silently drift from what the placed widget
    // actually does (confirmed 2026-07-13: the preview used to render
    // every section regardless of bucket, overpromising content a small
    // widget would never show).
    val renderableOrder = visible.filter { section ->
        !(section == WidgetSection.SEVERITY_DOTS && dotsInline) &&
            !(previewState.text == null && section in TEXT_GATED_SECTIONS) &&
            (bucket != WidgetSizeBucket.SMALL || section in TILE_SECTIONS)
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
                    if (bucket == WidgetSizeBucket.LARGE) {
                        Text(
                            text = previewState.text ?: "",
                            color = PREVIEW_ON_BG, fontSize = (12 * scale).sp,
                            maxLines = 3,
                        )
                    }
                WidgetSection.TODAY_EVENTS -> if (bucket == WidgetSizeBucket.LARGE) {
                    previewTasks.events.forEach {
                        Text(text = "📅 ${it.title}", color = PREVIEW_ON_BG, fontSize = (11 * scale).sp)
                    }
                }
                WidgetSection.UP_NEXT -> if (bucket == WidgetSizeBucket.LARGE && previewTasks.tasks.isNotEmpty()) {
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
            .background(Color(0xFF3B4A78))
            .padding((10 * scale).dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Text(text = "${state.temperatureF ?: "--"}", color = Color.White, fontWeight = FontWeight.Bold,
                fontSize = (28 * scale).sp)
            // "°F↑85°" / "↓67°" -- matches WeatherSection's real 2-line
            // grouping, not 3 separate stacked lines.
            Column {
                Text(text = "°F" + (state.weatherHighF?.let { "↑${it}°" } ?: ""),
                    color = Color.White, fontSize = (11 * scale).sp)
                state.weatherLowF?.let { Text(text = "↓${it}°", color = Color.White, fontSize = (10 * scale).sp) }
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
            Text(text = "💜 ${it}d", color = PREVIEW_ON_BG, fontSize = (11 * scale).sp)
        }
        state.friendDaysSince?.let {
            Text(text = "👥 ${it}d", color = PREVIEW_ON_BG, fontSize = (11 * scale).sp)
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
