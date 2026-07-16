package com.lifeops.briefing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.datastore.preferences.core.Preferences
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.action.actionParametersOf
import android.appwidget.AppWidgetManager
import androidx.glance.appwidget.CheckBox
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ColumnScope
import androidx.glance.layout.Row
import androidx.glance.layout.RowScope
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontFamily
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.lifeops.briefing.data.AttentionReason
import com.lifeops.briefing.data.BriefingState
import com.lifeops.briefing.data.GymRing
import com.lifeops.briefing.data.MoneyDisplayMode
import com.lifeops.briefing.data.NextTask
import com.lifeops.briefing.data.NextTasksState
import com.lifeops.briefing.data.NotableEvent
import com.lifeops.briefing.data.TodayEvent
import com.lifeops.briefing.data.WeatherInfo
import com.lifeops.briefing.data.WidgetDisplayConfig
import com.lifeops.briefing.data.WidgetSection
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** The three layouts BriefingContent renders, keyed to how much room the
 * placed widget actually has (see BriefingWidget.sizeMode). A user can
 * resize this widget down well below its 4x3 target, and Glance's
 * SizeMode.Single default would previously just hand every size the same
 * full layout to be clipped by the OS -- this codebase has already hit that
 * failure mode once (see the Column/Spacer overflow bug referenced below). */
internal enum class WidgetSizeBucket { SMALL, MEDIUM, LARGE }

private val SMALL_SIZE = DpSize(130.dp, 100.dp)
private val MEDIUM_SIZE = DpSize(250.dp, 150.dp)
private val LARGE_SIZE = DpSize(250.dp, 250.dp)

internal fun bucketFor(size: DpSize): WidgetSizeBucket = when {
    size.height < MEDIUM_SIZE.height -> WidgetSizeBucket.SMALL
    size.height < LARGE_SIZE.height -> WidgetSizeBucket.MEDIUM
    else -> WidgetSizeBucket.LARGE
}

/** Computes the actual type/icon scale from the placed widget footprint.
 * [WidgetDisplayConfig.scale] remains a user adjustment around the automatic
 * base, not a hidden product default. */
internal fun effectiveWidgetScale(
    size: DpSize,
    config: WidgetDisplayConfig,
    solo: Boolean,
    comboGrid: Boolean = config.comboGrid,
): Float {
    val width = size.width.value.coerceAtLeast(1f)
    val height = size.height.value.coerceAtLeast(1f)
    val referenceArea = when {
        comboGrid -> 180f * 140f
        solo -> 95f * 95f
        else -> 220f * 150f
    }
    val areaScale = sqrt((width * height) / referenceArea)
    val narrowSpanLimit = min(width / 90f, height / 70f)
    val automaticScale = min(areaScale, narrowSpanLimit)
        .coerceIn(1.0f, WidgetDisplayConfig.MAX_SCALE)
    return (automaticScale * config.scale).coerceIn(
        WidgetDisplayConfig.MIN_SCALE,
        WidgetDisplayConfig.MAX_SCALE,
    )
}

/**
 * The Glance widget itself: describes what to render for each widget instance.
 *
 * Reads the persisted [BriefingState] (serialized JSON under
 * [WidgetKeys.BRIEFING_JSON]), [NextTasksState] (under
 * [WidgetKeys.NEXT_TASKS_JSON]), and this instance's [WidgetDisplayConfig]
 * (under [WidgetKeys.DISPLAY_CONFIG_JSON], set via WidgetConfigActivity's
 * per-instance widget-configure screen) and renders: markdown-ish bold/plain
 * briefing text, a small stat row, a "received" timestamp, and up to a few
 * upcoming tasks with real checkboxes. Tapping the body (outside a checkbox)
 * opens the control panel.
 *
 * Briefing state is written by [BriefingReceiver] whenever ntfy delivers a
 * "briefing-data" push (push-only, no polling). Next-tasks state is written
 * by [NextTasksRefreshWorker]'s periodic pull and by [CompleteTaskAction]'s
 * immediate update after a checkbox tap.
 */
/** Single GlanceAppWidget shared by every receiver -- the full "LifeOps
 * Briefing" widget AND the single-stat presets (GymWidgetReceiver etc., see
 * BriefingWidgetReceiver.kt) all construct a plain no-arg BriefingWidget().
 * Before any WidgetConfigActivity save has persisted a DISPLAY_CONFIG_JSON
 * for a given instance, [presetDefaultConfig] resolves the right starting
 * layout by asking Android which AppWidgetProvider actually placed THIS
 * specific [GlanceId] -- looked up fresh on every render rather than baked
 * into the class at construction time. That used to be a constructor param
 * threaded through each receiver's `BaseBriefingWidgetReceiver(defaultConfig)`
 * call, which meant (a) the receiver-to-preset mapping was hand-duplicated
 * in two files (here and WidgetPresets.defaultConfigFor, with nothing
 * enforcing they matched), and (b) any OTHER code path that constructed a
 * bare `BriefingWidget()` -- e.g. NextTasksRefreshWorker's background
 * update() calls -- silently got the full-widget default instead of the
 * instance's real preset during the window between a widget being placed
 * and its configure screen being saved. Resolving per-render from the
 * actual placed provider fixes both at once (confirmed 2026-07-13). */
class BriefingWidget : GlanceAppWidget() {

    // Exact (not Responsive's 3 fixed snapshots) so LocalSize.current reports
    // the widget's true continuous placed size -- needed for the LARGE
    // bucket to scale how many upcoming tasks it shows to the actual room
    // available, instead of a placed-larger-than-4x4 widget just showing the
    // same fixed content with dead space below it (2026-07-13).
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // Only needed as a fallback when nothing's been persisted yet (or
        // the persisted JSON is malformed) -- computed once up front rather
        // than inside provideContent{}, since provideContent's block can
        // recompose repeatedly and this does a cross-process AppWidgetManager
        // lookup that shouldn't run on every recomposition.
        val fallbackConfig = presetDefaultConfig(context, id)
        provideContent {
            val prefs = currentState<Preferences>()
            // One malformed persisted field (e.g. a task object missing its
            // required "id") must not blank the ENTIRE widget -- fall back
            // to empty for just that piece rather than letting a JSONException
            // escape provideContent and take down title/text/stats/events too.
            val briefingJson = prefs[WidgetKeys.BRIEFING_JSON]
            val briefing = try {
                if (briefingJson != null) BriefingState.fromJson(briefingJson) else BriefingState.empty()
            } catch (e: org.json.JSONException) {
                BriefingState.empty()
            }
            val nextTasksJson = prefs[WidgetKeys.NEXT_TASKS_JSON]
            val nextTasks = try {
                if (nextTasksJson != null) NextTasksState.fromJson(nextTasksJson) else NextTasksState.empty()
            } catch (e: org.json.JSONException) {
                NextTasksState.empty()
            }
            val configJson = prefs[WidgetKeys.DISPLAY_CONFIG_JSON]
            val config = try {
                if (configJson != null) WidgetDisplayConfig.fromJson(configJson) else fallbackConfig
            } catch (e: org.json.JSONException) {
                fallbackConfig
            }

            // Plain SharedPreferences read (not the per-GlanceId Preferences
            // DataStore above) -- see PhoneWeather.kt: this is a global,
            // phone-wide cache, not per-widget-instance state, written
            // independently of whether the panel is configured at all.
            val phoneWeather = readCachedPhoneWeather(context)

            GlanceTheme {
                BriefingContent(briefing, nextTasks, config, phoneWeather = phoneWeather)
            }
        }
    }
}

/** Resolves which AppWidgetProvider (BriefingWidgetReceiver vs. one of the
 * single-stat presets) actually placed this [id], then looks up its default
 * config via [WidgetPresets] -- the same lookup WidgetConfigActivity does
 * for the configure screen, so both stay in sync automatically. Falls back
 * to the full default if the provider can't be resolved (e.g. a transient
 * AppWidgetManager lookup failure) rather than throwing. */
private suspend fun presetDefaultConfig(context: Context, id: GlanceId): WidgetDisplayConfig {
    val providerClassName = try {
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        AppWidgetManager.getInstance(context).getAppWidgetInfo(appWidgetId)?.provider?.className
    } catch (e: Exception) {
        null
    }
    return WidgetPresets.defaultConfigFor(providerClassName)
}

/** Every tile that shares a row with its neighbors when they're adjacent in
 * [WidgetDisplayConfig.sectionOrder] (see [groupSectionsForRendering]) -- lets
 * the default order (all three together) keep rendering as one instrument-
 * panel strip, while genuinely reordering one away from the others naturally
 * splits it onto its own row instead of requiring a separate "grouped vs.
 * independent" toggle. */
internal val TILE_SECTIONS = setOf(
    WidgetSection.GYM_RING, WidgetSection.MONEY_TILE, WidgetSection.COURSEWORK_TILE, WidgetSection.SLEEP_TILE,
)

/** Sections allowed to render at the SMALL bucket, beyond [TILE_SECTIONS] --
 * kept separate from TILE_SECTIONS itself since these don't render as a
 * StatTile-compatible group (groupSectionsForRendering must never batch
 * them into a shared TileRow). SOCIAL already renders as a StatTile-shaped
 * row (see SocialSection) so it fits SMALL as-is. WEATHER's [WeatherCard]
 * reads LocalSize itself and switches to its compact sizing below the same
 * threshold, so it fits SMALL without a separate code path here. Lets a
 * single-preset Weather/Social widget actually shrink to Android's real
 * minimum footprint instead of being stuck at a 150dp MEDIUM floor
 * (confirmed 2026-07-13: user wanted these resizable down like every
 * other single-stat preset). NOTABLE_EVENTS joins them for the same reason:
 * its own composable ([NotableEventsSection]) collapses to a single
 * "soonest event" line at SMALL, mirroring [WeatherCard]'s self-managed
 * compact sizing rather than needing a separate gate here. */
private val SMALL_BUCKET_ALLOWED = TILE_SECTIONS + WidgetSection.SOCIAL + WidgetSection.WEATHER + WidgetSection.NOTABLE_EVENTS

/** Collapses a config's visible section order into render units: a
 * contiguous run of [TILE_SECTIONS] becomes one group (rendered as one
 * shared Row by [TileRow]); everything else renders alone. */
internal fun groupSectionsForRendering(visibleOrder: List<WidgetSection>): List<List<WidgetSection>> {
    val groups = mutableListOf<MutableList<WidgetSection>>()
    for (section in visibleOrder) {
        val lastGroup = groups.lastOrNull()
        if (section in TILE_SECTIONS && lastGroup != null && lastGroup.last() in TILE_SECTIONS) {
            lastGroup.add(section)
        } else {
            groups.add(mutableListOf(section))
        }
    }
    return groups
}

/** Renders progressively more content as the placed widget size grows --
 * SMALL shows only the status badge (+ severity dots, if left in their
 * default inline position) -- the minimum "am I OK, and what's my next
 * move" signal -- MEDIUM adds the gym/money/coursework tiles + freshness
 * line, LARGE (the 4x3 target size) adds the full briefing paragraph,
 * today's events, and the up-next task list. Every bucket still gets the
 * attention badge -- that's the one thing that must never get squeezed out,
 * no matter how small the widget is resized.
 *
 * [config] (per-widget-instance, set via WidgetConfigActivity) controls
 * which of the 7 [WidgetSection]s show, in what order, and at what
 * font/icon scale -- see [WidgetDisplayConfig]'s docstring. Existing
 * size-bucket gating (what fits at this placed size) and the config's
 * user-visibility (what the user wants to see) combine with a plain AND:
 * a section only ever renders if the bucket allows it here AND the user
 * hasn't hidden it. */
@Composable
internal fun BriefingContent(
    state: BriefingState,
    nextTasks: NextTasksState,
    config: WidgetDisplayConfig = WidgetDisplayConfig.default(),
    // The phone's own directly-fetched NOAA reading (see PhoneWeather.kt) --
    // independent of the server entirely, so this is now the PRIMARY weather
    // source; nextTasks.weather (server, ~15-min refresh) and state's own
    // weather fields (server, once/day) are progressively staler fallbacks.
    phoneWeather: WeatherInfo? = null,
) {
    if (config.comboGrid) {
        ComboGridContent(state, nextTasks, config, phoneWeather)
        return
    }
    val placedSize = LocalSize.current
    val bucket = bucketFor(placedSize)
    // Hide-filtered first, THEN check what's first -- a hidden section ahead
    // of SEVERITY_DOTS in the raw sectionOrder must not defeat the
    // inline-with-badge rule just because it still occupies an earlier
    // index in the unfiltered list.
    val visibleOrder = config.sectionOrder.filter { it !in config.hiddenSections }
    // Severity dots render inline on the badge's own row ONLY when left in
    // their default (first, among VISIBLE sections) position -- moved
    // anywhere else, they detach into their own standalone row instead.
    val dotsInline = visibleOrder.firstOrNull() == WidgetSection.SEVERITY_DOTS
    val showBadge = WidgetSection.SEVERITY_DOTS !in config.hiddenSections
    val renderableOrder = visibleOrder.filter { section ->
        !(section == WidgetSection.SEVERITY_DOTS && dotsInline) &&
            !(state.text == null && section in TEXT_GATED_SECTIONS) &&
            // At SMALL, only the compact sections render -- the
            // richer/wider ones (paragraph/events/up-next) still need
            // at least MEDIUM room. WEATHER and SOCIAL are allowed
            // through too (see SMALL_BUCKET_ALLOWED); WEATHER renders a
            // compact StatTile instead of the full card at this bucket.
            (bucket != WidgetSizeBucket.SMALL || section in SMALL_BUCKET_ALLOWED)
    }
    // Badge hidden AND exactly one section left to show -- this single
    // tile IS the entire widget (a single-stat preset like "LifeOps
    // Weather"), so it should fill and center in the widget's actual
    // placed space rather than keep the fixed compact width meant for
    // sharing a row with 2-3 siblings on the full combo widget. Computed
    // here (not inside the Column below) so the Column's own padding can
    // react to it too -- a single-stat preset's true 1x1 footprint (e.g.
    // 70dp) has much less room to give away than the full widget's, and
    // the flat 12dp padding this Column used unconditionally was eating
    // enough of that footprint to clip the 48dp gym ring against its own
    // edge (confirmed 2026-07-14, live device: "getting clipped by an
    // invisible border").
    val solo = !showBadge && renderableOrder.size == 1
    val scale = effectiveWidgetScale(placedSize, config, solo = solo)
    val outerPadding = when {
        // Solo presets should use the whole launcher cell. Their tile/card
        // children already own their internal padding; adding a root inset
        // makes the widget look undersized next to native widgets.
        solo -> 0.dp
        bucket == WidgetSizeBucket.SMALL -> 4.dp
        else -> 8.dp
    }
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .padding(outerPadding)
            .clickable(actionRunCallback<OpenPanelAction>()),
    ) {
        // Gated behind the "Severity dots" toggle, not unconditional --
        // singleStat() presets (e.g. "LifeOps Weather") hide every section
        // including SEVERITY_DOTS, but this call used to run regardless,
        // so a weather-only widget still showed an unexplained "FUCKED"
        // badge about unrelated coursework/money severity with no dots to
        // give it context (confirmed 2026-07-13, live device: user's
        // weather-only widget said "FUCKED" for no visible reason).
        if (showBadge) {
            AttentionHeader(state, compact = bucket == WidgetSizeBucket.SMALL,
                showInlineDots = dotsInline, scale = scale)
        }

        // SMALL used to hard-stop right here, showing ONLY the badge --
        // correct for the full 7-section widget shrunk down, wrong for a
        // single-stat preset (e.g. "LifeOps Gym"), whose entire purpose is
        // showing its one compact tile even at the smallest size Android
        // allows. TILE_SECTIONS (gym/money/coursework/sleep) are already
        // ~100dp-wide-or-less single-row tiles, so they're let through at
        // SMALL below; the richer/wider sections (paragraph, events,
        // up-next, weather, social) still require at least MEDIUM via
        // their own bucket checks (confirmed 2026-07-13: a placed preset
        // widget couldn't be resized down to its declared 2x1 minimum
        // because this branch discarded its only content at that size).
        // Not shown at SMALL -- no room for it, and it's about the
        // paragraph specifically, which never renders below MEDIUM anyway.
        if (bucket != WidgetSizeBucket.SMALL && state.text == null &&
            WidgetSection.BRIEFING_PARAGRAPH in visibleOrder) {
            // Only the paragraph/money/coursework-tiles/freshness-line are
            // gated behind a real briefing having arrived at least once --
            // today's real calendar events, upcoming tasks, AND the gym ring
            // (pulled independently via NextTasksRefreshWorker, not part of
            // this LLM-generated snapshot) are all independent of briefing
            // text and must keep rendering below, not get swallowed by this
            // placeholder. Gated on BRIEFING_PARAGRAPH being visible at all,
            // not just on state.text -- a single-stat preset widget (e.g.
            // "LifeOps Gym") never shows the paragraph section in the first
            // place, so it has nothing to wait on and shouldn't show a
            // placeholder about a briefing it never displays (confirmed
            // 2026-07-13: this placeholder was swallowing the gym ring on a
            // freshly-placed Gym-only widget for up to a day, since
            // GYM_RING used to be unconditionally text-gated below).
            Text(
                text = "No briefing yet — tap to configure",
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant),
            )
        }

        // Shared height budget across every dynamic (unboundedly-sized) list
        // section enabled in THIS instance -- see allocateDynamicListCounts's
        // docstring for why this must be computed ONCE, jointly, rather than
        // each section (as TODAY_EVENTS/NOTABLE_EVENTS/UP_NEXT each
        // independently would) assuming it alone owns all the extra height.
        // NOTABLE_EVENTS also participates when solo at MEDIUM+ (its own
        // dedicated "LifeOps Events" preset, not sharing room with anything
        // else) -- not at SMALL, where NotableEventsSection collapses to a
        // single compact line with no list/budget involved at all.
        val heightDp = LocalSize.current.height.value.toInt()
        val dynamicSpecs = buildList {
            if (bucket == WidgetSizeBucket.LARGE && WidgetSection.TODAY_EVENTS in renderableOrder &&
                nextTasks.events.isNotEmpty()) {
                add(DynamicListSpec(WidgetSection.TODAY_EVENTS, nextTasks.events.size,
                    EVENT_ROW_HEIGHT_DP, MIN_EVENTS_SHOWN, EVENTS_HARD_CEILING))
            }
            if (WidgetSection.NOTABLE_EVENTS in renderableOrder && state.notableEvents.isNotEmpty() &&
                (bucket == WidgetSizeBucket.LARGE || (solo && bucket != WidgetSizeBucket.SMALL))) {
                add(DynamicListSpec(WidgetSection.NOTABLE_EVENTS, state.notableEvents.size,
                    EVENT_ROW_HEIGHT_DP, MIN_EVENTS_SHOWN, EVENTS_HARD_CEILING))
            }
            if (bucket == WidgetSizeBucket.LARGE && WidgetSection.UP_NEXT in renderableOrder &&
                nextTasks.tasks.isNotEmpty()) {
                val ceiling = listOfNotNull(config.maxTasksOverride, MAX_TASKS_HARD_CEILING).min()
                add(DynamicListSpec(WidgetSection.UP_NEXT, nextTasks.tasks.size,
                    TASK_ROW_HEIGHT_DP, MIN_TASKS_SHOWN, ceiling))
            }
        }
        val dynamicListCounts = allocateDynamicListCounts(
            heightDp, dynamicSpecs, reserveForStaleIndicator = state.text != null)

        for (group in groupSectionsForRendering(renderableOrder)) {
            if (group.size > 1) {
                TileRow(group, state, nextTasks.gymRing, scale, config = config)
                continue
            }
            when (val section = group.first()) {
                WidgetSection.SEVERITY_DOTS -> StandaloneSeverityDots(state.reasons, scale)
                WidgetSection.GYM_RING, WidgetSection.MONEY_TILE, WidgetSection.COURSEWORK_TILE,
                WidgetSection.SLEEP_TILE ->
                    SoloableTileRow(section, state, nextTasks.gymRing, scale, solo, config)
                WidgetSection.BRIEFING_PARAGRAPH -> {
                    val text = state.text
                    if (bucket == WidgetSizeBucket.LARGE && text != null) BriefingParagraph(text, scale)
                }
                WidgetSection.TODAY_EVENTS ->
                    if (bucket == WidgetSizeBucket.LARGE && nextTasks.events.isNotEmpty()) {
                        EventsSection(nextTasks.events,
                            dynamicListCounts[WidgetSection.TODAY_EVENTS] ?: MIN_EVENTS_SHOWN)
                    }
                WidgetSection.NOTABLE_EVENTS ->
                    // No isNotEmpty() gate -- NotableEventsSection renders
                    // its own empty state now (see its doc), so an empty
                    // list still shows "Nothing scheduled"/"None" instead of
                    // this branch skipping the section (and the standalone
                    // "LifeOps Events" solo preset rendering nothing at all).
                    if (bucket == WidgetSizeBucket.LARGE || solo) {
                        NotableEventsSection(state.notableEvents,
                            dynamicListCounts[WidgetSection.NOTABLE_EVENTS] ?: MIN_EVENTS_SHOWN, scale)
                    }
                WidgetSection.UP_NEXT ->
                    if (bucket == WidgetSizeBucket.LARGE && nextTasks.tasks.isNotEmpty()) {
                        UpNextSection(nextTasks.tasks,
                            dynamicListCounts[WidgetSection.UP_NEXT] ?: MIN_TASKS_SHOWN)
                    }
                WidgetSection.WEATHER -> {
                    // Priority: phoneWeather (fetched directly from NOAA by
                    // THIS phone -- zero server dependency, see PhoneWeather.kt)
                    // > nextTasks.weather (server, refreshed ~every 15 min,
                    // same pull as gym_ring) > state's own weather fields
                    // (server, only ever refreshed once/day inside
                    // run_briefing). Each is a progressively staler/more
                    // server-dependent fallback for the one before it -- so
                    // weather still updates even if the LifeOps server is
                    // down entirely (2026-07-15).
                    val w = phoneWeather ?: nextTasks.weather
                    val temperatureF = w?.temperatureF ?: state.temperatureF
                    if (temperatureF != null) {
                        // fillMaxSize() when solo (this card IS the entire
                        // widget) so the blue background actually fills the
                        // real placed area instead of just wrapping its own
                        // content height and leaving dead space around it
                        // (confirmed 2026-07-13: "keep the blue shit as
                        // filled out to 2x1 as you can"); a plain top margin
                        // otherwise, when it's sharing the widget with a
                        // badge/other sections above it.
                        WeatherCard(
                            temperatureF, w?.highF ?: state.weatherHighF,
                            w?.lowF ?: state.weatherLowF, w?.condition ?: state.weatherCondition,
                            scale,
                            modifier = if (solo) GlanceModifier.fillMaxSize() else GlanceModifier.padding(top = 4.dp),
                            appPackage = config.weatherAppPackage)
                    }
                }
                WidgetSection.SOCIAL ->
                    if (state.partnerDaysSince != null || state.friendDaysSince != null) {
                        // Stacked only for the standalone "LifeOps Social"
                        // preset (solo) -- the two chips side by side needed
                        // real width to avoid crowding, so the widget
                        // defaulted wider than gym/money/etc; stacking them
                        // lets it go back to that same narrow 2x1 footprint
                        // instead (confirmed 2026-07-13: "friends and social
                        // widgets should stack on each other ... could easily
                        // just be a 2x1"). The full combo widget keeps the
                        // existing side-by-side row -- it's tuned to fit a
                        // lot of vertical content already, and stacking
                        // there would cost an extra row it doesn't need.
                        SocialSection(state, scale, stacked = solo)
                    }
            }
        }
        if (bucket != WidgetSizeBucket.SMALL && state.text != null) {
            StaleIndicator(state.fetchedAtEpochMillis)
        }
    }
}

/** Sections whose DATA only exists once a real briefing has arrived at
 * least once -- money/coursework/weather/sleep/social figures are all
 * fields on the same [BriefingState] JSON snapshot as [BriefingState.text],
 * so they're never populated independently of it. TODAY_EVENTS/UP_NEXT/
 * GYM_RING are deliberately NOT here: TODAY_EVENTS/UP_NEXT were always
 * independent of briefing text (see EventsSection's docstring), and
 * GYM_RING's primary source
 * ([nextTasks.gymRing][com.lifeops.briefing.data.NextTasksState]) is pulled
 * independently via NextTasksRefreshWorker -- gating it here used to
 * swallow a freshly-placed widget's gym ring for up to a day whenever no
 * briefing had landed yet, which is exactly the only content a single-stat
 * "LifeOps Gym" preset widget has (confirmed 2026-07-13). */
internal val TEXT_GATED_SECTIONS = setOf(
    WidgetSection.BRIEFING_PARAGRAPH,
    WidgetSection.MONEY_TILE, WidgetSection.COURSEWORK_TILE, WidgetSection.WEATHER,
    WidgetSection.SLEEP_TILE, WidgetSection.SOCIAL,
)

private const val LARGE_BASE_HEIGHT_DP = 250
internal const val TASK_ROW_HEIGHT_DP = 34
internal const val MIN_TASKS_SHOWN = 3
internal const val EVENT_ROW_HEIGHT_DP = 24  // plain single-line Text, no checkbox -- shorter than a task row
internal const val MIN_EVENTS_SHOWN = 2
internal const val EVENTS_HARD_CEILING = 5  // matches today_events_input(n=5)'s own server-side cap

// Glance/RemoteViews has no scrolling Column -- content past the widget's
// actual placed height just gets silently clipped, not scrolled. Reserved
// so the freshness line ("as of 2m ago") always has room below whatever
// dynamic lists are enabled, instead of being the first thing clipped off as
// they greedily fill every extra dp with more rows (confirmed 2026-07-13,
// for UP_NEXT specifically: resizing to fit more tasks pushed the freshness
// line off the bottom). See allocateDynamicListCounts's docstring for why
// this reservation is now shared across every enabled dynamic section
// instead of computed once per section independently.
internal const val STALE_INDICATOR_RESERVED_DP = 20

// Glance's hard 10-direct-children-per-container limit (see the note above
// AttentionHeader) means an unclamped user override -- or an unclamped
// allocation here -- could reproduce the exact silent-crash bug already
// fixed once this session. UpNextSection's Column holds a header Text plus
// up to this many NextTaskRows plus (since OverflowIndicator was added)
// possibly one more trailing Text when tasks.size exceeds this ceiling, so
// 8 leaves room for both the header AND that overflow line (1 + 8 + 1 = 10)
// -- 9 would let a real overflow case reach 11 children and reproduce the
// same silent crash.
internal const val MAX_TASKS_HARD_CEILING = 8

/** One dynamic, unboundedly-sized list section that can appear in a single
 * widget instance: TODAY_EVENTS, NOTABLE_EVENTS, and UP_NEXT are all
 * variable-length lists whose row count should grow with the widget's
 * placed height, same idea 2026-07-13's UP_NEXT-only fix introduced. */
internal data class DynamicListSpec(
    val section: WidgetSection,
    val itemCount: Int,
    val rowHeightDp: Int,
    val minShown: Int,
    val hardCeiling: Int,
)

/** Splits the height remaining after LARGE_BASE_HEIGHT_DP + (the freshness
 * line's reservation, if it'll render) across every ENABLED dynamic list
 * section in this instance -- NOT per-section independently, which is what
 * this replaces (the pre-2026-07-15 effectiveMaxTasks/maxTasksForHeight
 * only ever existed for UP_NEXT; TODAY_EVENTS/EventsSection had NO
 * height-awareness at all, so it could already silently clip the freshness
 * line on its own with enough events, the very bug UP_NEXT's fix addressed
 * -- confirmed by re-reading EventsSection's original unconditional `for
 * (event in events)` loop). If two or three of these are enabled in the
 * same instance, letting each independently assume it owns ALL the extra
 * height would let them jointly overflow the widget's real placed size even
 * though any single one alone would have fit.
 *
 * Not a real layout measurement (Glance/RemoteViews has none) -- a rough
 * heuristic like the one it replaces, just no longer scoped to a single
 * section. Guarantees each enabled section's minShown first (never fewer,
 * regardless of how tight it gets), then divides whatever's left one row at
 * a time across sections in order (cheaper and more predictable than a
 * single proportional division, which would round awkwardly), clamped to
 * each section's own hardCeiling and its actual itemCount. */
internal fun allocateDynamicListCounts(
    heightDp: Int,
    specs: List<DynamicListSpec>,
    reserveForStaleIndicator: Boolean,
): Map<WidgetSection, Int> {
    if (specs.isEmpty()) return emptyMap()
    val reserved = if (reserveForStaleIndicator) STALE_INDICATOR_RESERVED_DP else 0
    // LARGE_BASE_HEIGHT_DP already accounts for a section's own minShown
    // rows fitting within the base layout (that's what "base" means) -- do
    // NOT also subtract minShown*rowHeightDp here, or every section's
    // minimum gets paid for twice (once implicitly via LARGE_BASE_HEIGHT_DP,
    // once explicitly here), starving the shared pool of extra rows that
    // heightDp actually has room for.
    var extraDp = (heightDp - LARGE_BASE_HEIGHT_DP - reserved).coerceAtLeast(0)

    // minShown is a target, not a guarantee that overrides a tighter
    // hardCeiling/itemCount -- e.g. a user's maxTasksOverride below
    // MIN_TASKS_SHOWN must still win.
    val counts = specs.associate { it.section to minOf(it.minShown, it.hardCeiling, it.itemCount) }.toMutableMap()
    var progressed = true
    while (extraDp > 0 && progressed) {
        progressed = false
        for (spec in specs) {
            val current = counts.getValue(spec.section)
            if (extraDp < spec.rowHeightDp) continue
            if (current >= spec.hardCeiling || current >= spec.itemCount) continue
            counts[spec.section] = current + 1
            extraDp -= spec.rowHeightDp
            progressed = true
        }
    }
    return counts.mapValues { (section, count) -> count.coerceAtMost(specs.first { it.section == section }.itemCount) }
}

/** Shared with GymBar's on/off-target color and StaleIndicator's warning
 * color -- all three signal "this is fine" vs. "this needs attention" with
 * the same two colors, so they're defined once here rather than as three
 * independently-typed hex literals that could silently drift apart. */
private val COLOR_OK = Color(0xFF276B5E)
private val COLOR_WARN = Color(0xFFA8641F)

internal data class SoloStatPresentation(
    val label: String,
    val value: String,
    val status: String,
    val accent: Color,
    val secondary: String? = null,
)

private const val BASE_BADGE_FONT_SP = 14f

// Glance enforces a hard "no more than 10 direct children per
// Column/Row container" limit when translating to RemoteViews on a real
// device (NOT enforced by runGlanceAppWidgetUnitTest's Robolectric harness
// -- confirmed the hard way: unit tests all green, real device threw
// "IllegalArgumentException: Column container cannot have more than 10
// elements" and silently kept showing the last successfully-rendered
// content instead of the crash being visible anywhere). A composable that
// doesn't wrap its own emissions in a Column/Row/Box flattens them into
// direct children of whatever container calls it -- so every logical
// section below gets its own wrapping Column specifically to keep
// BriefingContent's outer Column under that limit regardless of how many
// sections are enabled, hidden, or reordered.
@Composable
private fun AttentionHeader(state: BriefingState, compact: Boolean, showInlineDots: Boolean, scale: Float) {
    if (state.attentionState == null) return
    val statusColor = when (state.attentionState) {
        "fucked" -> Color(0xFFB3261E)
        "risk" -> Color(0xFFC25100)
        "watch" -> Color(0xFF8A5A00)
        else -> COLOR_OK
    }
    Column {
        // The recommended_action headline ("Do it now or deliberately
        // reschedule it.") used to render here too, but it's implied by the
        // severity badge itself (2026-07-12: user doesn't want it repeated
        // below "FUCKED") -- state.attentionHeadline is still parsed/kept in
        // BriefingState for whatever else might want it, just not shown here.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "${state.attentionSymbol ?: "●"} ${state.attentionLabel ?: state.attentionState.uppercase()}",
                style = TextStyle(fontWeight = FontWeight.Bold, fontSize = (BASE_BADGE_FONT_SP * scale).sp,
                    color = ColorProvider(statusColor)),
            )
            if (showInlineDots && state.reasons.isNotEmpty()) {
                Spacer(modifier = GlanceModifier.width(8.dp))
                SeverityDots(state.reasons, scale)
            }
        }
        if (!compact) {
            Spacer(modifier = GlanceModifier.height(6.dp))
        }
    }
}

/** Severity dots rendered as their own standalone row -- used when the user
 * has moved [WidgetSection.SEVERITY_DOTS] out of its default inline
 * position (see [BriefingContent]'s docstring). Wrapped in a Column so it
 * counts as one child of whatever container calls it. */
@Composable
private fun StandaloneSeverityDots(reasons: List<AttentionReason>, scale: Float) {
    if (reasons.isEmpty()) return
    Column(modifier = GlanceModifier.padding(top = 4.dp)) {
        SeverityDots(reasons, scale)
    }
}

/** Per-domain glyph row -- one dot per tracked domain (coursework, system,
 * money, gym, matching attention.py's _DOMAIN_PRIORITY order), colored by
 * the worst severity attention.compute() found for that domain, green when
 * a domain has no open reason at all. Lets a glance answer "which of my
 * four areas needs something" without reading any text -- the instrument-
 * panel glyph language the rest of the widget is moving toward, rather
 * than one linear headline trying to speak for every domain at once. */
internal val DOT_DOMAIN_ORDER = listOf("coursework", "system", "money", "gym")
private const val BASE_DOT_SIZE_DP = 7
internal val SEVERITY_RANK = mapOf("ok" to 0, "watch" to 1, "risk" to 2, "fucked" to 3)

internal fun severityDotColor(severity: String): Color = when (severity) {
    "fucked" -> Color(0xFFB3261E)
    "risk" -> Color(0xFFC25100)
    "watch" -> Color(0xFF8A5A00)
    else -> COLOR_OK
}

private fun renderDotBitmap(sizePx: Int, colorArgb: Int): Bitmap {
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = colorArgb }
    val radius = sizePx / 2f
    canvas.drawCircle(radius, radius, radius, paint)
    return bitmap
}

// A fixed source-bitmap resolution, not device density -- Image scales the
// bitmap to the target dp box regardless of the source's raw pixel
// dimensions, so (unlike the ring, whose stroke width must scale with its
// dp size) a plain filled circle needs no density lookup at all.
private const val DOT_BITMAP_PX = 32

/** Worst (highest-ranked) severity per domain across [reasons] -- shared by
 * SeverityDots here and WidgetConfigActivity's preview, so both agree on
 * what "worst" means without duplicating the ranking walk. */
internal fun worstSeverityByDomain(reasons: List<AttentionReason>): Map<String, String> {
    val worstByDomain = mutableMapOf<String, String>()
    for (r in reasons) {
        val rank = SEVERITY_RANK[r.severity] ?: continue
        val currentRank = SEVERITY_RANK[worstByDomain[r.domain]] ?: -1
        if (rank > currentRank) {
            worstByDomain[r.domain] = r.severity
        }
    }
    return worstByDomain
}

@Composable
private fun SeverityDots(reasons: List<AttentionReason>, scale: Float) {
    val worstByDomain = worstSeverityByDomain(reasons)
    val dotSizeDp = (BASE_DOT_SIZE_DP * scale).dp
    Row {
        DOT_DOMAIN_ORDER.forEachIndexed { index, domain ->
            if (index > 0) {
                Spacer(modifier = GlanceModifier.width(5.dp))
            }
            val severity = worstByDomain[domain] ?: "ok"
            val bitmap = renderDotBitmap(DOT_BITMAP_PX, severityDotColor(severity).toArgb())
            Image(
                provider = ImageProvider(bitmap),
                contentDescription = "$domain: $severity",
                modifier = GlanceModifier.size(dotSizeDp),
            )
        }
    }
}

/** Full LLM-generated briefing text -- each source line becomes a Row of
 * bold/non-bold Text runs, stacked in a Column to preserve line breaks. Only
 * shown at the LARGE bucket: it's the least essential content relative to
 * the deterministic status/stats above it, and the first thing that should
 * drop away as the widget shrinks. */
private const val BASE_PARAGRAPH_FONT_SP = 15f

@Composable
private fun BriefingParagraph(text: String, scale: Float) {
    Column {
        for (line in text.split("\n")) {
            if (line.isBlank()) {
                // A blank line marks a paragraph break in the source text --
                // a small fixed Spacer reads as a paragraph gap without the
                // extra height a full blank Text row's line-height added.
                Spacer(modifier = GlanceModifier.height(4.dp))
            } else {
                Row(modifier = GlanceModifier.fillMaxWidth()) {
                    for ((segment, isBold) in parseMarkupLine(line)) {
                        Text(
                            text = segment,
                            style = TextStyle(
                                fontWeight = if (isBold) FontWeight.Bold else FontWeight.Normal,
                                fontSize = (BASE_PARAGRAPH_FONT_SP * scale).sp,
                                color = GlanceTheme.colors.onSurface,
                            ),
                        )
                    }
                }
            }
        }
    }
}

private const val BASE_TILE_EMOJI_SP = 16f
private const val BASE_TILE_VALUE_SP = 18f

/** Icon+monospace-number card -- glyph replaces the label, number is the
 * one thing that needs to read at a glance, matching the gym ring's
 * icon-first visual language instead of a plain "$340" text run. Rounded
 * corners match MoneyTile's shape so the two read as one family of tile.
 * Emoji and value sit side by side (not stacked) so the tile reads as one
 * line, matching MoneyTile's single-line height in the shared TileRow.
 *
 * [severity], when non-null, tints the background the same
 * risk/watch/ok-green [MONEY_TILE_RISK_BG]/[MONEY_TILE_WATCH_BG]/
 * [MONEY_TILE_OK_BG] language [MoneyTile] already uses in this same shared
 * row -- until 2026-07-15 Coursework/Sleep rendered here as a permanently
 * neutral gray tile regardless of severity (Coursework's own severity was
 * even computed by [TileRow] and then silently discarded, never reaching
 * this composable at all), so Money was the only tile in the row whose
 * color ever told you anything, which is exactly the "coursework/social's
 * plain emoji+number StatTiles" inconsistency [SeverityValueTile]'s own
 * docstring already called out for the combo grid -- it just never got
 * fixed here, in the main widget's actual default shared row (confirmed
 * 2026-07-15: "why doesn't each individual section have the same UX
 * conventions"). Null (the default) keeps the plain neutral look for
 * anything with no severity signal to show, same "no data, no false signal"
 * reasoning [SocialTile] already uses for its own transparent/neutral
 * fallback. */
@Composable
private fun StatTile(
    emoji: String, value: String, scale: Float, modifier: GlanceModifier = GlanceModifier,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    emojiSp: Float = BASE_TILE_EMOJI_SP, valueSp: Float = BASE_TILE_VALUE_SP, tilePadding: Dp = 6.dp,
    severity: String? = null,
) {
    val bg = when (severity) {
        null -> GlanceTheme.colors.surfaceVariant
        "risk", "fucked" -> ColorProvider(MONEY_TILE_RISK_BG)
        "watch" -> ColorProvider(MONEY_TILE_WATCH_BG)
        else -> ColorProvider(MONEY_TILE_OK_BG)
    }
    Row(
        modifier = modifier
            .cornerRadius(10.dp)
            .background(bg)
            .padding(tilePadding),
        horizontalAlignment = horizontalAlignment,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = emoji, style = TextStyle(fontSize = (emojiSp * scale).sp))
        Spacer(modifier = GlanceModifier.width(4.dp))
        Text(
            text = value,
            style = TextStyle(fontWeight = FontWeight.Bold, fontSize = (valueSp * scale).sp,
                color = GlanceTheme.colors.onSurface),
        )
    }
}

internal val MONEY_TILE_OK_BG = Color(0x4D276B5E)
internal val MONEY_TILE_WATCH_BG = Color(0x4DA8641F)
internal val MONEY_TILE_RISK_BG = Color(0x4DB3261E)
internal val MONEY_SOLO_BG = Color(0xFF171A20)
internal val MONEY_SOLO_OK_ACCENT = Color(0xFF52B69A)
internal val MONEY_SOLO_WATCH_ACCENT = Color(0xFFFFB74D)
internal val MONEY_SOLO_RISK_ACCENT = Color(0xFFFF6B6B)
private const val BASE_MONEY_FONT_SP = 22f
// Used only for the standalone "LifeOps Money" solo preset -- its widget-
// info XML declares the literal 40dp sizing-formula floor for n=1 (the one
// value mathematically guaranteed to render as a true 1x1, since bigger
// values kept rounding up to 2x2 on live-device testing; see
// money_widget_info.xml), which doesn't leave room for the 22sp default
// font without clipping (confirmed 2026-07-14).
private const val MONEY_SOLO_FONT_SP = 13f

/** No glyph, no label -- just a value, big and bold, on a background tinted
 * by [severity] (green/amber/red translucent overlay) -- the shape
 * MoneyTile originated as (the amount itself IS the message for a money
 * figure) and now shared by every tile in [ComboGridContent] so
 * money/coursework/social read as one consistent family of card in that
 * merged 2x2 layout, rather than money's colored card sitting next to
 * coursework/social's plain emoji+number StatTiles (confirmed 2026-07-15:
 * user wants all three to visually match money's severity-colored style,
 * not the neutral StatTile look). */
@Composable
private fun SeverityValueTile(
    value: String, severity: String?, scale: Float, modifier: GlanceModifier = GlanceModifier,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    verticalAlignment: Alignment.Vertical = Alignment.Top,
    fontSp: Float = BASE_MONEY_FONT_SP,
    tilePadding: Dp = 6.dp,
    // 0.dp from ComboGridContent -- see its docstring for why an internal
    // cell boundary must NOT be independently rounded (rounded corners
    // meeting rounded corners at a shared edge leaves a small gap there,
    // exposing whatever's behind the widget -- confirmed 2026-07-15: "there
    // aren't fuckass transparency parts, right?"). Every OTHER caller (the
    // shared TileRow on the full widget, where tiles have a real Spacer gap
    // between them) keeps the normal rounded-card look.
    cornerRadiusDp: Dp = 10.dp,
) {
    val bg = when (severity) {
        "risk", "fucked" -> MONEY_TILE_RISK_BG
        "watch" -> MONEY_TILE_WATCH_BG
        else -> MONEY_TILE_OK_BG
    }
    Column(
        modifier = modifier
            .cornerRadius(cornerRadiusDp)
            .background(ColorProvider(bg))
            .padding(tilePadding),
        horizontalAlignment = horizontalAlignment,
        verticalAlignment = verticalAlignment,
    ) {
        Text(
            text = value,
            style = TextStyle(fontWeight = FontWeight.Bold, fontSize = (fontSp * scale).sp,
                color = GlanceTheme.colors.onSurface),
        )
    }
}

/** Background color carries the same money severity attention.compute()
 * already decided -- green when fine, transparent yellow when the buffer's
 * thin, red when discretionary has gone negative -- looked up from
 * state.reasons rather than re-deriving the <0/<100 thresholds here, so the
 * widget can never disagree with the engine that owns severity. */
@Composable
private fun MoneyTile(
    dollars: Int, severity: String?, scale: Float, modifier: GlanceModifier = GlanceModifier,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    verticalAlignment: Alignment.Vertical = Alignment.Top,
    fontSp: Float = BASE_MONEY_FONT_SP,
    tilePadding: Dp = 6.dp,
    soloStyle: Boolean = false,
    cornerRadiusDp: Dp = 10.dp,
    todayDollars: Int? = null,
    currentDollars: Int? = null,
    currentLabel: String = "YNAB",
    moneyDisplayMode: MoneyDisplayMode = MoneyDisplayMode.YNAB_CURRENT,
    appPackage: String = "",
) {
    val clickableModifier = externalAppClick(modifier, OpenExternalAppAction.TARGET_MONEY, appPackage)
    if (soloStyle) {
        SoloMoneyTile(dollars, severity, scale, clickableModifier,
            todayDollars = todayDollars, currentDollars = currentDollars, currentLabel = currentLabel,
            moneyDisplayMode = moneyDisplayMode)
        return
    }
    SeverityValueTile(
        moneyStatPresentation(dollars, severity, todayDollars, currentDollars,
            compact = true, displayMode = moneyDisplayMode, currentLabel = currentLabel).value,
        severity, scale, clickableModifier,
        horizontalAlignment, verticalAlignment, fontSp, tilePadding, cornerRadiusDp)
}

private fun selectedYnabCategory(state: BriefingState, config: WidgetDisplayConfig) =
    state.ynabCategoryBalances.firstOrNull {
        it.name.equals(config.ynabCategoryName, ignoreCase = true)
    }

/** Shared skeleton for every "solo" single-stat card (label / big value /
 * bottom accent-colored status bar) -- SoloMoneyTile and SocialFocusTile
 * used to be two hand-copied ~50-line composables with identical layout
 * and only their label/value/status/accent computation actually differing
 * (confirmed 2026-07-14 code review; this is the exact WeatherSection/
 * CompactWeatherTile duplication mistake android/CLAUDE.md already
 * documents as a repeated-bug source -- "if you're about to write a
 * second version of an existing section for a different size, stop --
 * parameterize the existing one instead"). Callers compute their own
 * domain-specific label/value/status/accent and hand them here.
 *
 * Samsung's launcher can grant a visually narrow 1x1 surface even when
 * LocalSize reports a larger value. Typography stays conservative so a
 * value like "-$160" never wraps into two lines on the actual home
 * screen. */
@Composable
private fun SoloStatCard(
    label: String, value: String, status: String, accent: Color, scale: Float, modifier: GlanceModifier,
    secondary: String? = null,
    labelSp: Float = 8f,
    valueSp: Float = 22f,
    statusSp: Float = 9f,
    // 0.dp from ComboGridContent -- see SeverityValueTile's cornerRadiusDp
    // doc for why an internal cell boundary must stay flat, not rounded.
    cornerRadiusDp: Dp = 14.dp,
    // Tighter than the default 5.dp in ComboGridContent's combo tiles --
    // each one is only ~1/3 the width AND a fraction of the height of a
    // real solo widget, so the same absolute padding used a
    // disproportionate share of the available vertical room.
    statusVerticalPaddingDp: Dp = 5.dp,
) {
    Column(
        modifier = modifier
            .cornerRadius(cornerRadiusDp)
            .background(ColorProvider(MONEY_SOLO_BG)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalAlignment = Alignment.Top,
    ) {
        Spacer(modifier = GlanceModifier.defaultWeight())
        Column(
            modifier = GlanceModifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                maxLines = 1,
                style = TextStyle(
                    fontWeight = FontWeight.Bold,
                    fontSize = (labelSp * scale).sp,
                    color = GlanceTheme.colors.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                ),
                modifier = GlanceModifier.fillMaxWidth(),
            )
            Text(
                text = value,
                maxLines = 1,
                style = TextStyle(
                    fontWeight = FontWeight.Bold,
                    fontSize = (valueSp * scale).sp,
                    color = GlanceTheme.colors.onSurface,
                    textAlign = TextAlign.Center,
                ),
                modifier = GlanceModifier.fillMaxWidth().padding(top = 2.dp),
            )
            secondary?.let {
                Text(
                    text = it,
                    maxLines = 1,
                    style = TextStyle(
                        fontWeight = FontWeight.Bold,
                        fontSize = (8f * scale).sp,
                        color = GlanceTheme.colors.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    ),
                    modifier = GlanceModifier.fillMaxWidth().padding(top = 1.dp),
                )
            }
        }
        Spacer(modifier = GlanceModifier.defaultWeight())
        Box(
            modifier = GlanceModifier
                .fillMaxWidth()
                .background(ColorProvider(accent))
                .padding(vertical = statusVerticalPaddingDp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = status,
                maxLines = 1,
                style = TextStyle(
                    fontWeight = FontWeight.Bold,
                    fontSize = (statusSp * scale).sp,
                    color = ColorProvider(Color(0xFF171A20)),
                    textAlign = TextAlign.Center,
                ),
                modifier = GlanceModifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun SoloMoneyTile(
    dollars: Int, severity: String?, scale: Float, modifier: GlanceModifier,
    labelSp: Float = 8f, valueSp: Float = 22f, statusSp: Float = 9f,
    cornerRadiusDp: Dp = 14.dp, statusVerticalPaddingDp: Dp = 5.dp,
    todayDollars: Int? = null,
    currentDollars: Int? = null,
    currentLabel: String = "YNAB",
    moneyDisplayMode: MoneyDisplayMode = MoneyDisplayMode.YNAB_CURRENT,
) {
    val stat = moneyStatPresentation(dollars, severity, todayDollars, currentDollars,
        displayMode = moneyDisplayMode, currentLabel = currentLabel)
    SoloStatCard(label = stat.label, value = stat.value, status = stat.status,
        accent = stat.accent, scale = scale, modifier = modifier, secondary = stat.secondary,
        labelSp = labelSp, valueSp = valueSp, statusSp = statusSp,
        cornerRadiusDp = cornerRadiusDp, statusVerticalPaddingDp = statusVerticalPaddingDp)
}

/** Builds the money tile according to the per-widget display mode. The mode
 * chooses the headline number; missing data falls back to the next useful
 * money value rather than blanking the tile. */
internal fun moneyStatPresentation(
    dollars: Int,
    severity: String?,
    todayDollars: Int? = null,
    currentDollars: Int? = null,
    compact: Boolean = false,
    displayMode: MoneyDisplayMode = MoneyDisplayMode.YNAB_CURRENT,
    currentLabel: String = "YNAB",
): SoloStatPresentation {
    val todayAvailable = todayDollars != null && todayDollars > 0
    val selected = when (displayMode) {
        MoneyDisplayMode.YNAB_CURRENT -> if (currentDollars != null) {
            Triple(currentLabel.uppercase(), currentDollars,
                if (!compact && currentDollars != dollars) "PLAN ${formatMoney(dollars)}" else null)
        } else if (todayAvailable) {
            Triple("TODAY", todayDollars, if (!compact) "FUTURE ${formatMoney(dollars)}" else null)
        } else {
            Triple("SPEND", dollars, null)
        }
        MoneyDisplayMode.TODAY -> if (todayAvailable) {
            Triple("TODAY", todayDollars, if (!compact) {
                currentDollars?.let { "${currentLabel.uppercase()} ${formatMoney(it)}" }
                    ?: "FUTURE ${formatMoney(dollars)}"
            } else null)
        } else if (currentDollars != null) {
            Triple(currentLabel.uppercase(), currentDollars,
                if (!compact && currentDollars != dollars) "PLAN ${formatMoney(dollars)}" else null)
        } else {
            Triple("SPEND", dollars, null)
        }
        MoneyDisplayMode.PROJECTED -> Triple("SPEND", dollars, if (!compact && currentDollars != null) {
            "${currentLabel.uppercase()} ${formatMoney(currentDollars)}"
        } else null)
    }
    val displayedDollars = selected.second
    val isNegative = displayedDollars < 0
    val status = when {
        isNegative -> "OVER"
        severity == "watch" -> "LOW"
        severity == "risk" || severity == "fucked" -> "RISK"
        else -> "LEFT"
    }
    val accent = when {
        isNegative || severity == "risk" || severity == "fucked" -> MONEY_SOLO_RISK_ACCENT
        severity == "watch" -> MONEY_SOLO_WATCH_ACCENT
        else -> MONEY_SOLO_OK_ACCENT
    }
    return SoloStatPresentation(selected.first, formatMoney(selected.second), status, accent, secondary = selected.third)
}

internal fun formatMoney(dollars: Int): String = if (dollars < 0) "-$${-dollars}" else "$$dollars"

/** Standalone "LifeOps Coursework" preset: same SoloStatCard shape Money
 * and Social's solo tiles already use (dark card, label + big value +
 * colored status bar), instead of the plain neutral emoji+number StatTile
 * this used to share with the standalone Sleep preset (confirmed
 * 2026-07-15: user wants all the standalone single-stat widgets to match).
 * severity comes from the same attention.compute() "coursework" domain
 * MoneyTile already reads for its own severity, so this can't disagree with
 * the engine that owns it. */
@Composable
private fun SoloCourseworkTile(
    hours: Double, severity: String?, scale: Float, modifier: GlanceModifier,
    labelSp: Float = 8f, valueSp: Float = 22f, statusSp: Float = 9f,
    cornerRadiusDp: Dp = 14.dp, statusVerticalPaddingDp: Dp = 5.dp,
) {
    val stat = courseworkStatPresentation(hours, severity)
    SoloStatCard(label = stat.label, value = stat.value, status = stat.status,
        accent = stat.accent, scale = scale, modifier = modifier,
        labelSp = labelSp, valueSp = valueSp, statusSp = statusSp,
        cornerRadiusDp = cornerRadiusDp, statusVerticalPaddingDp = statusVerticalPaddingDp)
}

internal fun courseworkStatPresentation(hours: Double, severity: String?): SoloStatPresentation {
    val status = when (severity) {
        "risk", "fucked" -> "HEAVY"
        "watch" -> "WATCH"
        else -> "LOAD"
    }
    val accent = when (severity) {
        "risk", "fucked" -> MONEY_SOLO_RISK_ACCENT
        "watch" -> MONEY_SOLO_WATCH_ACCENT
        else -> MONEY_SOLO_OK_ACCENT
    }
    return SoloStatPresentation("COURSEWORK", "${hours}h", status, accent)
}

// Mirrors lifeops/config.py's SLEEP_OK_MIN (330 minutes -- "min minutes of
// real sleep to count 'rested'") rather than inventing a separate
// threshold here: sleep has no attention.compute() domain of its own (no
// backend-computed severity ever reaches BriefingState for it, unlike
// money/coursework), so this is the same "reuse an existing, deliberate
// threshold instead of guessing a new one" approach used for social's
// derived severity.
private const val SLEEP_OK_MIN = 330

internal fun sleepSeverity(minutes: Int): String = if (minutes < SLEEP_OK_MIN) "watch" else "ok"

/** Standalone "LifeOps Sleep" preset -- see [SoloCourseworkTile]'s
 * docstring for why this now uses SoloStatCard instead of the plain
 * StatTile it used to share with Coursework's solo widget. */
@Composable
private fun SoloSleepTile(minutes: Int, scale: Float, modifier: GlanceModifier) {
    val stat = sleepStatPresentation(minutes)
    SoloStatCard(label = stat.label, value = stat.value, status = stat.status,
        accent = stat.accent, scale = scale, modifier = modifier)
}

internal fun sleepStatPresentation(minutes: Int): SoloStatPresentation {
    val severity = sleepSeverity(minutes)
    val status = if (severity == "watch") "LOW" else "SLEEP"
    val accent = if (severity == "watch") MONEY_SOLO_WATCH_ACCENT else MONEY_SOLO_OK_ACCENT
    return SoloStatPresentation("SLEEP", formatSleepDuration(minutes), status, accent)
}

/** Wraps a lone TileRow (e.g. a single-stat preset like "LifeOps Gym")
 * so it fills and centers in the widget's actual placed space instead of
 * hugging its own small content size in the top-left corner -- TileRow's
 * StatTile children are a fixed ~100dp wide (STAT_TILE_WIDTH_DP), sized for
 * sharing a row with 2-3 other tiles on the full combo widget; as the SOLE
 * content of an entire standalone widget, that fixed width left most of
 * the widget's real footprint as dead empty space (confirmed 2026-07-13,
 * live device: "it should take up the whole space of a 2x1 widget"). Only
 * applied when [solo] is true (badge hidden AND this is the only visible
 * section) -- a tile sharing space with siblings must keep its compact
 * fixed width. */
@Composable
private fun SoloableTileRow(
    section: WidgetSection, state: BriefingState, gymRing: GymRing?, scale: Float, solo: Boolean,
    config: WidgetDisplayConfig,
) {
    if (solo) {
        Box(modifier = GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            TileRow(listOf(section), state, gymRing, scale, expand = true, config = config)
        }
    } else {
        TileRow(listOf(section), state, gymRing, scale, config = config)
    }
}

/** Renders a contiguous run of gym-ring/money/coursework as one shared row
 * (see [groupSectionsForRendering]) -- also used for a single tile shown
 * alone, when the user has reordered it away from the other two. Skips
 * emitting the row entirely if nothing in [sections] has data to show
 * (e.g. gym ring section present in order, but no gym data at all yet).
 * [expand] stretches each StatTile-based child to fill the row's width
 * instead of its normal fixed STAT_TILE_WIDTH_DP -- only set true via
 * [SoloableTileRow] when this row is the widget's sole content; multiple
 * tiles sharing a row must keep their compact fixed width regardless. */
@Composable
private fun TileRow(
    sections: List<WidgetSection>, state: BriefingState, gymRing: GymRing?, scale: Float, expand: Boolean = false,
    config: WidgetDisplayConfig,
) {
    val moneySeverity = state.reasons.firstOrNull { it.domain == "money" }?.severity
    val courseworkSeverity = state.reasons.firstOrNull { it.domain == "coursework" }?.severity
    val sleepSev = state.sleepMinutes?.let(::sleepSeverity)
    val hasGym = WidgetSection.GYM_RING in sections &&
        (gymRing != null || (state.gymLast7d != null && state.gymTarget != null))
    val hasMoney = WidgetSection.MONEY_TILE in sections &&
        (state.discretionaryDollars != null || state.discretionaryCurrentDollars != null)
    val hasCoursework = WidgetSection.COURSEWORK_TILE in sections && state.courseworkHoursNext7d != null
    val hasSleep = WidgetSection.SLEEP_TILE in sections && state.sleepMinutes != null
    if (!hasGym && !hasMoney && !hasCoursework && !hasSleep) return
    // fillMaxSize(), not just fillMaxWidth() -- a MoneyTile/StatTile asked
    // to fillMaxHeight() has nothing to fill if ITS parent Row only ever
    // claimed fillMaxWidth(): the Row would just wrap to its own content
    // height regardless, same "background box doesn't actually fill"
    // pattern already hit and fixed for CompactWeatherTile and the gym
    // ring (confirmed 2026-07-14: "the discretionary budget thing should
    // be 1x1" surfaced this same gap for MONEY_TILE/COURSEWORK_TILE/
    // SLEEP_TILE, which none of the earlier fixes touched).
    val rowModifier = if (expand) GlanceModifier.fillMaxSize() else GlanceModifier.fillMaxWidth()
    val tileWidth = if (expand) GlanceModifier.fillMaxSize() else GlanceModifier.width((STAT_TILE_WIDTH_DP * scale).dp)
    val tileAlignment = if (expand) Alignment.CenterHorizontally else Alignment.Start
    val tileVerticalAlignment = if (expand) Alignment.CenterVertically else Alignment.Top

    Row(
        modifier = rowModifier.padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        var addedFirst = false
        for (section in sections) {
            val rendered = when (section) {
                WidgetSection.GYM_RING -> when {
                    gymRing != null -> {
                        if (addedFirst) Spacer(modifier = GlanceModifier.width(6.dp))
                        if (expand) {
                            SoloGymTile(gymRing, scale, modifier = tileWidth, appPackage = config.gymAppPackage)
                        } else {
                            GymRingIndicator(gymRing, scale)
                        }
                        true
                    }
                    state.gymLast7d != null && state.gymTarget != null -> {
                        if (addedFirst) Spacer(modifier = GlanceModifier.width(6.dp))
                        if (expand) {
                            SoloGymFallbackTile(state.gymLast7d, state.gymTarget, scale, modifier = tileWidth,
                                appPackage = config.gymAppPackage)
                        } else {
                            GymBar(state.gymLast7d, state.gymTarget, scale)
                        }
                        true
                    }
                    else -> false
                }
                WidgetSection.MONEY_TILE -> if (state.discretionaryDollars != null || state.discretionaryCurrentDollars != null) {
                    if (addedFirst) Spacer(modifier = GlanceModifier.width(6.dp))
                    val projectedDollars = state.discretionaryDollars ?: state.discretionaryCurrentDollars ?: 0
                    val selectedCategory = selectedYnabCategory(state, config)
                    MoneyTile(projectedDollars, moneySeverity, scale, modifier = tileWidth,
                        horizontalAlignment = tileAlignment, verticalAlignment = tileVerticalAlignment,
                        fontSp = if (expand) MONEY_SOLO_FONT_SP else BASE_MONEY_FONT_SP,
                        tilePadding = if (expand) 4.dp else 6.dp,
                        soloStyle = expand, todayDollars = state.discretionaryTodayDollars,
                        currentDollars = selectedCategory?.dollars ?: state.discretionaryCurrentDollars,
                        currentLabel = selectedCategory?.name ?: "YNAB",
                        moneyDisplayMode = config.moneyDisplayMode,
                        appPackage = config.moneyAppPackage)
                    true
                } else false
                WidgetSection.COURSEWORK_TILE -> if (state.courseworkHoursNext7d != null) {
                    if (addedFirst) Spacer(modifier = GlanceModifier.width(6.dp))
                    if (expand) {
                        SoloCourseworkTile(state.courseworkHoursNext7d, courseworkSeverity, scale, modifier = tileWidth)
                    } else {
                        StatTile("📚", "${state.courseworkHoursNext7d}h", scale,
                            modifier = tileWidth, horizontalAlignment = tileAlignment, severity = courseworkSeverity)
                    }
                    true
                } else false
                WidgetSection.SLEEP_TILE -> if (state.sleepMinutes != null) {
                    if (addedFirst) Spacer(modifier = GlanceModifier.width(6.dp))
                    if (expand) {
                        SoloSleepTile(state.sleepMinutes, scale, modifier = tileWidth)
                    } else {
                        StatTile("😴", formatSleepDuration(state.sleepMinutes), scale,
                            modifier = tileWidth, horizontalAlignment = tileAlignment, severity = sleepSev)
                    }
                    true
                } else false
                else -> false
            }
            if (rendered) addedFirst = true
        }
    }
}

/** The "LifeOps Combo" preset (see [WidgetDisplayConfig.comboGrid]): one
 * resizable, gapless Glance surface that chooses how many configured cells
 * to show from LocalSize.current. Placing separate single-stat widgets side
 * by side on the launcher always leaves launcher grid gaps between them;
 * this keeps the cells inside one continuous rounded card. Tight placements
 * show the first configured cells only, while wider/taller placements reveal
 * more cells instead of shrinking text until it clips. */
// Used by ComboEventsTile's list rows/empty-state text, not the stat tiles
// (those switched to SoloStatCard's own plain defaults -- see
// ComboGridContent's docstring) -- the events quadrant is still a genuinely
// dense agenda list (day + time + title on one line), so it keeps its own
// smaller size independent of the stat tiles' sizing.
internal const val COMBO_TILE_VALUE_SP = 14f

/** [SocialMetric.severity], translated to the same "ok"/"watch"/"risk"
 * vocabulary state.reasons uses for money/coursework -- social has no
 * attention.compute() domain of its own (see DOT_DOMAIN_ORDER), so this is
 * the closest equivalent derived from data already on hand rather than
 * inventing a new backend severity concept just for one tile's color. Reads
 * [SocialMetric.severity] rather than re-deriving the daysUntil/daysSince
 * thresholds here, so this can't disagree with [SocialMetric.bgColor]'s own
 * judgment about the same metric. */
internal fun socialSeverity(metric: SocialMetric): String = metric.severity

internal const val COMBO_EVENTS_SHOWN = 3

// Sized down from an unstyled default Text (what NotableEventsSection's own
// "Coming up" header uses, fine at a solo widget's full width) -- at the
// combo grid's ~1/4-widget quadrant, an unstyled default-size header
// competed too much with COMBO_TILE_VALUE_SP's row text for the same small
// footprint.
internal const val COMBO_EVENTS_HEADER_SP = 11f
internal enum class ComboLayout { COMPACT_2X2, MEDIUM_3X2, WIDE_4X2, TALL_4X3 }

internal fun comboLayoutFor(size: DpSize): ComboLayout = when {
    // Samsung can report a visually 2x2 placement at the 180dp formula
    // boundary, so keep compact inclusive with a little tolerance. Without
    // this the live widget takes the 3x2 two-column branch and renders
    // weather/gym in the left column with money as a full-height right cell.
    size.width.value <= 190f -> ComboLayout.COMPACT_2X2
    size.width.value < 250f -> ComboLayout.MEDIUM_3X2
    size.height.value < 180f -> ComboLayout.WIDE_4X2
    else -> ComboLayout.TALL_4X3
}

private data class ComboCell(
    val section: WidgetSection,
    val stat: SoloStatPresentation? = null,
    val gymRing: GymRing? = null,
    val gymCompleted: Int? = null,
    val gymTarget: Int? = null,
    val appPackage: String = "",
    val compact: Boolean = false,
)

/** Agenda-style combo cell. A notable event is a list item (day/time/title),
 * not a single stat -- it can't take the label/value/status-bar
 * [SoloStatCard] shape the left half's three tiles now use (see
 * ComboGridContent), so this stays its own flat agenda-list card instead,
 * same as the standalone "LifeOps Events" preset's list path
 * ([NotableEventsSection]) rather than its solo/compact
 * [SoloNotableEventTile] card. Background is the same neutral [MONEY_SOLO_BG]
 * every stat tile in this grid now uses, NOT [MONEY_TILE_OK_BG] (a former
 * version of this tile used that "ok"-severity green as a whole-card tint) --
 * a notable event has no severity dimension at all (informational, not "at
 * risk"), and painting the entire quadrant in the same green this app uses
 * to mean "money/coursework is fine" reads as a false status signal, not a
 * neutral one, exactly the "color as accent, not a full-bleed surface"
 * mistake android/CLAUDE.md's money-widget section already warns against
 * (confirmed 2026-07-15: "why is the upcoming events green"). Leads with a
 * "Coming up" header -- SAME text/weight/color NotableEventsSection's own
 * list-view header uses, just resized for this smaller footprint -- rather
 * than the old header-less version, whose single centered "Nothing
 * upcoming" line had nothing to visually anchor it and read as adrift in a
 * mostly-empty quadrant with no indication of what it even was (confirmed
 * 2026-07-15 UI audit: this was the one piece of the combo grid with no
 * label at all, when every OTHER quadrant -- SPEND/FRIENDS/CLASS -- has
 * one). Every state (empty or populated) now stacks top-down under that
 * header instead of centering, matching NotableEventsSection's own
 * Column(verticalAlignment = Top) shape for the identical data. Each row is
 * [NotableEventLine] -- see its doc for why day/time are their own
 * fixed-width columns, not just leading text. */
@Composable
private fun ComboEventsTile(events: List<NotableEvent>, scale: Float, modifier: GlanceModifier = GlanceModifier) {
    Column(
        // Flat, not rounded -- see ComboGridContent's docstring: this tile
        // is an internal cell of ONE outer rounded/opaque card, not its own
        // floating card, so it must butt seamlessly against its neighbors.
        // Start-aligned, not centered -- a list of lines reads as a list
        // when their left edges line up (how every other list in this app,
        // and every real agenda widget, does it); centering multi-line text
        // is the one thing that made this look like an odd one out
        // (confirmed 2026-07-15).
        modifier = modifier
            .background(ColorProvider(MONEY_SOLO_BG))
            .padding(8.dp),
        horizontalAlignment = Alignment.Start,
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = "Coming up",
            maxLines = 1,
            style = TextStyle(fontWeight = FontWeight.Bold, fontSize = (COMBO_EVENTS_HEADER_SP * scale).sp,
                color = GlanceTheme.colors.onSurface),
        )
        // Always renders the card (background/padding above), even when
        // empty -- a genuinely empty upcoming-events list is a normal,
        // common result (most weeks have zero one-off events), not a
        // loading/error state, so this quadrant should say so rather than
        // leave a blank hole in an otherwise-solid combo card (confirmed
        // 2026-07-15: "what if we don't have any events there? ... this
        // widget should be dynamic").
        if (events.isEmpty()) {
            Text(
                text = "Nothing scheduled",
                maxLines = 1,
                modifier = GlanceModifier.padding(top = 4.dp),
                style = TextStyle(fontSize = (COMBO_TILE_VALUE_SP * scale).sp,
                    color = GlanceTheme.colors.onSurfaceVariant),
            )
        } else {
            val toShow = events.take(COMBO_EVENTS_SHOWN)
            toShow.forEach { event ->
                NotableEventLine(event, scale, COMBO_TILE_VALUE_SP, modifier = GlanceModifier.padding(top = 4.dp))
            }
            OverflowIndicator(events.size - toShow.size)
        }
    }
}

// Same solid dark backdrop the family's SoloStatCard already uses
// (MONEY_SOLO_BG) -- reused here as the ONE opaque background behind the
// entire combo grid, not a new color, so this reads as the same visual
// family rather than an arbitrary new dark.
internal val COMBO_BG = MONEY_SOLO_BG
internal val COMBO_OUTER_RADIUS = 16.dp

/** Size-aware Combo renderer. COMPACT_2X2 uses one full-width top cell and
 * two half-width bottom cells, prioritizing weather over money + gym when
 * those configured cells have data. MEDIUM_3X2 shows three, WIDE_4X2 shows
 * four, and TALL_4X3 keeps the richer left-stack plus events shape. No
 * Spacer between any of the pieces,
 * AND every
 * individual tile inside is flat (cornerRadiusDp = 0.dp) -- only the OUTER
 * edge of this whole Row is rounded/opaque ([COMBO_BG]/[COMBO_OUTER_RADIUS]),
 * and Glance clips its children to that shape. Each tile independently
 * rounding its own corners (the family's normal look when tiles have a real
 * gap between them, e.g. the shared TileRow on the full widget) would leave
 * a small lens-shaped gap wherever two rounded corners meet edge-to-edge,
 * showing whatever's behind the widget through it -- and with no background
 * at all on the old version of this Row, that gap was the transparent
 * widget canvas itself, not just a seam (confirmed 2026-07-15: "there
 * aren't fuckass transparency parts, right? it looks like a single solid
 * block with sections?" -- it didn't, until this fix). This is what
 * actually makes it read as one solid card divided into sections, not five
 * floating chips.
 *
 * Until 2026-07-15 all three stats (money/social/coursework) shared ONE row
 * across the left half's full width -- each tile got only ~1/3 of ~140dp
 * (~47dp), forcing COMBO_LABEL_SP/COMBO_TILE_VALUE_SP/COMBO_STATUS_SP down
 * to 6f/14f/6f just to avoid clipping, well below [SoloStatCard]'s own
 * 8f/22f/9f defaults every solo widget uses at a comparable or smaller
 * footprint. That's the opposite of how real per-metric widgets handle
 * space pressure -- Shopify's own postmortems on both platforms describe
 * reducing metric count per row as space shrinks, not shrinking text to
 * force more into the same row. Combo cells use [SoloStatCard]'s normal
 * typography; the current implementation preserves legibility across
 * launcher spans by reducing visible cell count at smaller sizes. */
@Composable
private fun ComboGridContent(
    state: BriefingState, nextTasks: NextTasksState, config: WidgetDisplayConfig, phoneWeather: WeatherInfo? = null,
) {
    // Same "phoneWeather > nextTasks.weather > state" priority as
    // BriefingContent's own WEATHER branch -- see WeatherCard's docstring.
    val w = phoneWeather ?: nextTasks.weather
    val temperatureF = w?.temperatureF ?: state.temperatureF
    val highF = w?.highF ?: state.weatherHighF
    val lowF = w?.lowF ?: state.weatherLowF
    val condition = w?.condition ?: state.weatherCondition
    val placedSize = LocalSize.current
    val layout = comboLayoutFor(placedSize)
    val scale = effectiveWidgetScale(placedSize, config, solo = false, comboGrid = true)
    val compactCells = layout != ComboLayout.TALL_4X3
    val moneySeverity = state.reasons.firstOrNull { it.domain == "money" }?.severity
    val courseworkSeverity = state.reasons.firstOrNull { it.domain == "coursework" }?.severity
    val socialItems = listOfNotNull(
        state.partnerDaysSince?.let { SocialItem("💜", "PARTNER", SocialMetric(it, state.partnerDaysUntil)) },
        state.friendDaysSince?.let { SocialItem("👥", "FRIENDS", SocialMetric(it, state.friendDaysUntil)) },
    )
    val cells = buildList {
        for (section in config.sectionOrder.filter {
            it !in config.hiddenSections && it in WidgetDisplayConfig.COMBO_GRID_SUPPORTED_SECTIONS
        }) {
            when (section) {
                WidgetSection.GYM_RING -> when {
                    layout == ComboLayout.COMPACT_2X2 && nextTasks.gymRing != null ->
                        add(ComboCell(section, stat = gymStatPresentation(nextTasks.gymRing),
                            appPackage = config.gymAppPackage, compact = true))
                    nextTasks.gymRing != null -> add(ComboCell(section, gymRing = nextTasks.gymRing,
                        appPackage = config.gymAppPackage))
                    layout == ComboLayout.COMPACT_2X2 && state.gymLast7d != null && state.gymTarget != null ->
                        add(ComboCell(section, stat = gymFallbackStatPresentation(state.gymLast7d, state.gymTarget),
                            appPackage = config.gymAppPackage, compact = true))
                    state.gymLast7d != null && state.gymTarget != null ->
                        add(ComboCell(section, gymCompleted = state.gymLast7d, gymTarget = state.gymTarget,
                            appPackage = config.gymAppPackage))
                }
                WidgetSection.MONEY_TILE -> (state.discretionaryCurrentDollars ?: state.discretionaryDollars)?.let {
                    val selectedCategory = selectedYnabCategory(state, config)
                    add(ComboCell(section, moneyStatPresentation(
                        it, moneySeverity, state.discretionaryTodayDollars,
                        currentDollars = selectedCategory?.dollars ?: state.discretionaryCurrentDollars,
                        compact = compactCells,
                        displayMode = config.moneyDisplayMode,
                        currentLabel = selectedCategory?.name ?: "YNAB"),
                        appPackage = config.moneyAppPackage))
                }
                WidgetSection.COURSEWORK_TILE -> state.courseworkHoursNext7d?.let {
                    add(ComboCell(section, courseworkStatPresentation(it, courseworkSeverity)))
                }
                WidgetSection.SLEEP_TILE -> state.sleepMinutes?.let {
                    add(ComboCell(section, sleepStatPresentation(it)))
                }
                WidgetSection.SOCIAL -> socialStatPresentation(socialItems, compact = compactCells)?.let {
                    add(ComboCell(section, it))
                }
                WidgetSection.WEATHER -> if (temperatureF != null) add(ComboCell(section,
                    appPackage = config.weatherAppPackage))
                WidgetSection.NOTABLE_EVENTS -> if (state.notableEvents.isNotEmpty()) add(ComboCell(section))
                else -> Unit
            }
        }
    }
    if (cells.isEmpty()) return
    Row(
        modifier = GlanceModifier
            .fillMaxSize()
            .cornerRadius(COMBO_OUTER_RADIUS)
            .background(ColorProvider(COMBO_BG)),
    ) {
        when (layout) {
            ComboLayout.COMPACT_2X2 ->
                ComboCompactTwoByTwo(cells, state, temperatureF, highF, lowF, condition, scale)
            ComboLayout.MEDIUM_3X2 -> ComboTwoColumn(cells.take(3), state, temperatureF, highF, lowF, condition, scale,
                leftCount = 2)
            ComboLayout.WIDE_4X2 -> ComboTwoColumn(cells.take(4), state, temperatureF, highF, lowF, condition, scale,
                leftCount = 2)
            ComboLayout.TALL_4X3 -> {
                val eventCell = cells.firstOrNull { it.section == WidgetSection.NOTABLE_EVENTS }
                val priorityCells = cells.filter { it.section != WidgetSection.NOTABLE_EVENTS }
                ComboTallGrid(priorityCells, eventCell, state, temperatureF, highF, lowF, condition, scale)
            }
        }
    }
}

private fun compactTwoByTwoCells(cells: List<ComboCell>): List<ComboCell> {
    val preferred = listOfNotNull(
        cells.firstOrNull { it.section == WidgetSection.WEATHER },
        cells.firstOrNull { it.section == WidgetSection.MONEY_TILE },
        cells.firstOrNull { it.section == WidgetSection.GYM_RING },
    )
    return (preferred + cells.filter { it !in preferred && it.section != WidgetSection.NOTABLE_EVENTS })
        .distinct()
        .take(3)
}

@Composable
private fun RowScope.ComboCompactTwoByTwo(
    cells: List<ComboCell>, state: BriefingState, temperatureF: Int?, highF: Int?, lowF: Int?, condition: String?,
    scale: Float,
) {
    val compactCells = compactTwoByTwoCells(cells)
    val top = compactCells.firstOrNull() ?: return
    val bottom = compactCells.drop(1)

    Column(modifier = GlanceModifier.fillMaxSize().defaultWeight()) {
        ComboRenderCell(top, state, temperatureF, highF, lowF, condition, scale,
            GlanceModifier.fillMaxWidth().defaultWeight())
        if (bottom.isNotEmpty()) {
            ComboTileDividerHorizontal()
            Row(modifier = GlanceModifier.fillMaxWidth().defaultWeight()) {
                bottom.forEachIndexed { index, cell ->
                    if (index > 0) ComboTileDivider()
                    ComboRenderCell(cell, state, temperatureF, highF, lowF, condition, scale,
                        GlanceModifier.fillMaxSize().defaultWeight())
                }
            }
        }
    }
}

@Composable
private fun RowScope.ComboTwoColumn(
    cells: List<ComboCell>, state: BriefingState, temperatureF: Int?, highF: Int?, lowF: Int?, condition: String?,
    scale: Float, leftCount: Int,
) {
    val left = cells.take(leftCount)
    val right = cells.drop(leftCount)
    ComboColumn(left, state, temperatureF, highF, lowF, condition, scale, GlanceModifier.fillMaxSize().defaultWeight())
    if (right.isNotEmpty()) {
        ComboTileDivider()
        ComboColumn(right, state, temperatureF, highF, lowF, condition, scale, GlanceModifier.fillMaxSize().defaultWeight())
    }
}

@Composable
private fun RowScope.ComboTallGrid(
    cells: List<ComboCell>, state: BriefingState, temperatureF: Int?, highF: Int?, lowF: Int?, condition: String?,
    scale: Float, modifier: GlanceModifier = GlanceModifier.fillMaxSize().defaultWeight(),
) {
    val rows = cells.take(6).chunked(2)
    Column(modifier = modifier) {
        rows.forEachIndexed { index, rowCells ->
            if (index > 0) ComboTileDividerHorizontal()
            ComboRow(rowCells, state, temperatureF, highF, lowF, condition, scale)
        }
    }
}

@Composable
private fun RowScope.ComboTallGrid(
    cells: List<ComboCell>, eventCell: ComboCell?, state: BriefingState, temperatureF: Int?, highF: Int?,
    lowF: Int?, condition: String?,
    scale: Float,
) {
    if (eventCell == null) {
        ComboTallGrid(cells, state, temperatureF, highF, lowF, condition, scale)
        return
    }

    val topCells = cells.take(2)
    val lowerCells = cells.drop(2).take(2)
    Column(modifier = GlanceModifier.fillMaxSize().defaultWeight()) {
        if (topCells.isNotEmpty()) {
            ComboRow(topCells, state, temperatureF, highF, lowF, condition, scale)
            ComboTileDividerHorizontal()
        }
        Row(modifier = GlanceModifier.fillMaxWidth().defaultWeight()) {
            if (lowerCells.isNotEmpty()) {
                ComboColumn(lowerCells, state, temperatureF, highF, lowF, condition, scale,
                    modifier = GlanceModifier.fillMaxSize().defaultWeight())
                ComboTileDivider()
            }
            ComboRenderCell(eventCell, state, temperatureF, highF, lowF, condition, scale,
                GlanceModifier.fillMaxSize().defaultWeight())
        }
    }
}

@Composable
private fun ColumnScope.ComboRow(
    cells: List<ComboCell>, state: BriefingState, temperatureF: Int?, highF: Int?, lowF: Int?, condition: String?,
    scale: Float, modifier: GlanceModifier = GlanceModifier.fillMaxWidth().defaultWeight(),
) {
    Row(modifier = modifier) {
        cells.forEachIndexed { index, cell ->
            if (index > 0) ComboTileDivider()
            ComboRenderCell(cell, state, temperatureF, highF, lowF, condition, scale,
                GlanceModifier.fillMaxSize().defaultWeight())
        }
    }
}

@Composable
private fun RowScope.ComboColumn(
    cells: List<ComboCell>, state: BriefingState, temperatureF: Int?, highF: Int?, lowF: Int?, condition: String?,
    scale: Float, modifier: GlanceModifier = GlanceModifier.fillMaxSize().defaultWeight(),
) {
    ComboColumnContent(cells, state, temperatureF, highF, lowF, condition, scale, modifier)
}

@Composable
private fun ColumnScope.ComboColumn(
    cells: List<ComboCell>, state: BriefingState, temperatureF: Int?, highF: Int?, lowF: Int?, condition: String?,
    scale: Float, modifier: GlanceModifier = GlanceModifier.fillMaxWidth().defaultWeight(),
) {
    ComboColumnContent(cells, state, temperatureF, highF, lowF, condition, scale, modifier)
}

@Composable
private fun ComboColumnContent(
    cells: List<ComboCell>, state: BriefingState, temperatureF: Int?, highF: Int?, lowF: Int?, condition: String?,
    scale: Float, modifier: GlanceModifier,
) {
    Column(modifier = modifier) {
        cells.forEachIndexed { index, cell ->
            if (index > 0) ComboTileDividerHorizontal()
            ComboRenderCell(cell, state, temperatureF, highF, lowF, condition, scale,
                GlanceModifier.fillMaxWidth().defaultWeight())
        }
    }
}

@Composable
private fun ComboRenderCell(
    cell: ComboCell, state: BriefingState, temperatureF: Int?, highF: Int?, lowF: Int?, condition: String?,
    scale: Float, modifier: GlanceModifier,
) {
    when (cell.section) {
        WidgetSection.GYM_RING -> when {
            cell.stat != null -> ComboStatTile(cell.stat, scale,
                externalAppClick(modifier, OpenExternalAppAction.TARGET_GYM, cell.appPackage))
            cell.gymRing != null -> GymProgressCard(cell.gymRing, scale, modifier, cornerRadiusDp = 0.dp,
                statusVerticalPaddingDp = 4.dp, appPackage = cell.appPackage, compact = cell.compact)
            cell.gymCompleted != null && cell.gymTarget != null ->
                GymFallbackProgressCard(cell.gymCompleted, cell.gymTarget, scale, modifier,
                cornerRadiusDp = 0.dp, statusVerticalPaddingDp = 4.dp, appPackage = cell.appPackage,
                    compact = cell.compact)
        }
        WidgetSection.WEATHER -> temperatureF?.let {
            WeatherCard(it, highF, lowF, condition, scale, modifier = modifier, cornerRadiusDp = 0.dp,
                appPackage = cell.appPackage)
        }
        WidgetSection.NOTABLE_EVENTS -> ComboEventsTile(state.notableEvents, scale, modifier = modifier)
        else -> cell.stat?.let { stat ->
            val target = if (cell.section == WidgetSection.MONEY_TILE) OpenExternalAppAction.TARGET_MONEY else null
            ComboStatTile(stat, scale,
                if (target != null) externalAppClick(modifier, target, cell.appPackage) else modifier)
        }
    }
}

// No sizing overrides -- SoloStatCard's own defaults (8f/22f/9f/5.dp),
// literally identical typography to every solo widget's own tile, now that
// a combo stat tile gets a genuine ~1 or ~2-column-wide share of the left
// half instead of a forced 3rd-of-a-row (see ComboGridContent's docstring).
@Composable
private fun ComboStatTile(stat: SoloStatPresentation, scale: Float, modifier: GlanceModifier) {
    SoloStatCard(
        label = stat.label,
        value = stat.value,
        status = stat.status,
        accent = stat.accent,
        scale = scale,
        modifier = modifier,
        secondary = stat.secondary,
        cornerRadiusDp = 0.dp,
    )
}

// A hairline seam between adjacent combo tiles -- NOT a transparent Spacer
// gap (see ComboGridContent's own docstring on why those were banned: they
// exposed the widget's transparent canvas before COMBO_BG existed). This is
// itself opaque/colored, so it reads as a deliberate divider between two
// SoloStatCard-shaped tiles that now share the same solid MONEY_SOLO_BG
// background as their card body (unlike the old SeverityValueTile tiles,
// which each carried their own tinted background and so didn't need a seam
// to read as separate pieces).
internal val COMBO_DIVIDER_COLOR = Color(0x33FFFFFF)

@Composable
private fun ComboTileDividerHorizontal() {
    Spacer(
        modifier = GlanceModifier
            .fillMaxWidth()
            .height(1.dp)
            .background(ColorProvider(COMBO_DIVIDER_COLOR)),
    )
}

@Composable
private fun ComboTileDivider() {
    Spacer(
        modifier = GlanceModifier
            .fillMaxHeight()
            .width(1.dp)
            .background(ColorProvider(COMBO_DIVIDER_COLOR)),
    )
}

/** "6h42m" / "6h" -- matches the compact, no-decimal style every other
 * stat tile uses (e.g. courseworkHoursNext7d's "6.5h"), sized to fit the
 * same ~100dp stat tile width. */
internal fun formatSleepDuration(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return if (m == 0) "${h}h" else "${h}h${m}m"
}

internal data class SocialMetric(val daysSince: Int, val daysUntil: Int?) {
    val fullLabel: String =
        daysUntil?.let { "${daysSince}d ago · ${it}d next" } ?: "${daysSince}d ago"
    val compactLabel: String =
        daysUntil?.let { "${daysSince}d/${it}d" } ?: "${daysSince}d"
    // Single source of truth for social's planned/overdue/neutral judgment --
    // bgColor and ComboGridContent's socialSeverity() (via [severity]) both
    // read this instead of each re-deriving the daysUntil/daysSince>=7
    // thresholds independently, so the two can't silently drift apart.
    val severity: String
        get() = when {
            daysUntil != null -> "ok"
            daysSince >= 7 -> "watch"
            else -> "ok"
        }
    val bgColor: Color
        get() = when (severity) {
            "watch" -> Color(0x4DA8641F)
            else -> if (daysUntil != null) Color(0x4D276B5E) else Color(0x00000000)
        }
}

internal data class SocialItem(val emoji: String, val label: String, val metric: SocialMetric)

/** "Xd ago" / "Yd next" social cadence cards -- two independent
 * figures (social_input tracks them separately, same as PARTNER_TASK vs.
 * FRIENDS_TASK elsewhere in the app), so unlike the other stats this is not
 * just a raw number. Renders
 * standalone (not part of [TILE_SECTIONS]) for the same reason [WeatherCard]
 * does -- two paired numbers read better as their own row than squeezed
 * into the ~100dp gym/money/coursework/sleep tile width. Either figure can
 * be missing independently (e.g. no FRIEND_NAMES configured) without
 * hiding the other. Reuses [StatTile] itself (used to have its own
 * near-identical SocialStat composable with its own, mismatched font
 * sizes -- the icon read BIGGER than the value, inverted from every other
 * tile's "the number is the point" hierarchy; confirmed 2026-07-13 as an
 * oversight, not a deliberate difference, since the two composables were
 * structurally identical).
 *
 * When a plan already exists (social_input's has_partner/has_friend), the
 * full-size value says "next" instead of relying on a symbolic arrow. The
 * compact 1x1 preset keeps only the paired day counts because the explicit
 * copy would clip at that footprint.
 *
 * [stacked] renders the two chips as a vertical Column instead of a side-by-
 * side Row -- only used for the standalone single-stat "LifeOps Social"
 * preset (see the SOCIAL branch in BriefingContent), letting that widget's
 * default footprint be a single square cell instead of needing extra width
 * for two chips side by side (confirmed 2026-07-14: "the social widget to
 * stack the partner and friend values ... fit in a single 1x1 widget").
 * Uses shrunk font sizes/padding, not StatTile's defaults -- two full-size
 * stacked chips need ~100dp of height (confirmed 2026-07-13), but
 * social_widget_info.xml declares the literal sizing-formula floor for n=1
 * (40dp), the one value guaranteed to actually render as 1x1 rather than
 * rounding up to 2x2 (confirmed 2026-07-14, live device: 100dp still
 * showed "2x2" in the picker). Default-size chips would clip badly at 40dp. */
@Composable
private fun SocialSection(state: BriefingState, scale: Float, stacked: Boolean = false) {
    fun metric(daysSince: Int?, daysUntil: Int?): SocialMetric? =
        daysSince?.let { SocialMetric(it, daysUntil) }
    val partner = metric(state.partnerDaysSince, state.partnerDaysUntil)
    val friends = metric(state.friendDaysSince, state.friendDaysUntil)
    if (stacked) {
        socialFocusItem(
            listOfNotNull(
                partner?.let { SocialItem("💜", "PARTNER", it) },
                friends?.let { SocialItem("👥", "FRIENDS", it) },
            )
        )?.let { SocialFocusTile(it, scale, modifier = GlanceModifier.fillMaxSize()) }
    } else {
        Row(modifier = GlanceModifier.fillMaxWidth().padding(top = 4.dp)) {
            partner?.let { SocialTile("💜", it, scale, modifier = GlanceModifier.defaultWeight()) }
            if (partner != null && friends != null) {
                Spacer(modifier = GlanceModifier.width(6.dp))
            }
            friends?.let { SocialTile("👥", it, scale, modifier = GlanceModifier.defaultWeight()) }
        }
    }
}

internal fun socialFocusItem(items: List<SocialItem>): SocialItem? {
    if (items.isEmpty()) return null
    val unplanned = items.filter { it.metric.daysUntil == null }
    if (unplanned.isNotEmpty()) {
        return unplanned.maxByOrNull { it.metric.daysSince }
    }
    return items.minByOrNull { it.metric.daysUntil ?: Int.MAX_VALUE }
}

internal fun socialStatPresentation(items: List<SocialItem>, compact: Boolean): SoloStatPresentation? {
    if (items.isEmpty()) return null
    if (compact || items.size == 1) return socialFocusItem(items)?.let(::socialStatPresentation)
    val first = items[0]
    val second = items[1]
    val focus = socialFocusItem(items) ?: first
    val planned = focus.metric.daysUntil != null
    val accent = when {
        planned -> MONEY_SOLO_OK_ACCENT
        focus.metric.daysSince >= 7 -> MONEY_SOLO_WATCH_ACCENT
        else -> MONEY_SOLO_OK_ACCENT
    }
    return SoloStatPresentation(
        label = "SOCIAL",
        value = socialCompactValue(first),
        status = if (planned) "NEXT" else "AGO",
        accent = accent,
        secondary = socialCompactValue(second),
    )
}

private fun socialCompactValue(item: SocialItem): String {
    val metric = item.metric
    val days = metric.daysUntil ?: metric.daysSince
    val suffix = if (metric.daysUntil != null) "next" else "ago"
    return "${item.label.take(1)} ${days}d $suffix"
}

@Composable
private fun SocialTile(
    emoji: String,
    metric: SocialMetric,
    scale: Float,
    modifier: GlanceModifier = GlanceModifier,
    compact: Boolean = false,
) {
    val tileBg = if (metric.bgColor == Color(0x00000000)) {
        GlanceTheme.colors.surfaceVariant
    } else {
        ColorProvider(metric.bgColor)
    }
    Row(
        modifier = modifier
            .cornerRadius(10.dp)
            .background(tileBg)
            .padding(if (compact) 2.dp else 6.dp),
        horizontalAlignment = if (compact) Alignment.CenterHorizontally else Alignment.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = emoji, style = TextStyle(fontSize = ((if (compact) 17f else 17f) * scale).sp))
        Spacer(modifier = GlanceModifier.width(if (compact) 2.dp else 4.dp))
        Text(
            text = if (compact) metric.compactLabel else metric.fullLabel,
            style = TextStyle(
                fontWeight = FontWeight.Bold,
                fontSize = ((if (compact) 17f else 15f) * scale).sp,
                color = GlanceTheme.colors.onSurface,
            ),
        )
    }
}

@Composable
private fun SocialFocusTile(
    item: SocialItem, scale: Float, modifier: GlanceModifier = GlanceModifier,
    labelSp: Float = 8f, valueSp: Float = 22f, statusSp: Float = 9f,
    cornerRadiusDp: Dp = 14.dp, statusVerticalPaddingDp: Dp = 5.dp,
) {
    val stat = socialStatPresentation(item)
    SoloStatCard(label = stat.label, value = stat.value, status = stat.status,
        accent = stat.accent, scale = scale, modifier = modifier,
        labelSp = labelSp, valueSp = valueSp, statusSp = statusSp,
        cornerRadiusDp = cornerRadiusDp, statusVerticalPaddingDp = statusVerticalPaddingDp)
}

internal fun socialStatPresentation(item: SocialItem): SoloStatPresentation {
    val metric = item.metric
    val planned = metric.daysUntil != null
    val accent = when {
        planned -> MONEY_SOLO_OK_ACCENT
        metric.daysSince >= 7 -> MONEY_SOLO_WATCH_ACCENT
        else -> MONEY_SOLO_OK_ACCENT
    }
    val value = if (planned) "${metric.daysUntil}d" else "${metric.daysSince}d"
    val status = if (planned) "NEXT" else "AGO"
    return SoloStatPresentation(item.label, value, status, accent)
}

// Matches the app's one established accent blue (the web panel's
// --md-primary-container / WidgetConfigActivity's LifeOpsDarkColors
// primaryContainer, both #2F4D80) instead of an unrelated blue lifted
// straight from the original reference mockup image -- confirmed
// 2026-07-13 that the old #3B4A78 didn't tie into anything else in the
// app's palette, it just happened to be close in hue.
private val WEATHER_BG = Color(0xFF2F4D80)
private const val BASE_WEATHER_TEMP_SP = 40f
private const val BASE_WEATHER_UNIT_SP = 16f
private const val BASE_WEATHER_HILO_SP = 14f
private const val BASE_WEATHER_ICON_SP = 34f
private const val BASE_WEATHER_CONDITION_SP = 15f

// Scaled down from the full card's constants above -- CompactWeatherTile
// packs the same content (temp+unit, hi/lo, icon, condition label) into the
// true 2x1 footprint (110dp wide, matching every other single-stat preset --
// see weather_widget_info.xml). These sizes were already confirmed to fit
// without clipping at 110dp; only CONDITION_SP needed nudging down one more
// point, since the condition text label was the one piece still brushing
// up against the edge (confirmed 2026-07-13, live device: "it was
// literally fine except for that").
private const val COMPACT_WEATHER_TEMP_SP = 24f
private const val COMPACT_WEATHER_UNIT_SP = 11f
private const val COMPACT_WEATHER_HILO_SP = 10f
// 1.3x the previous 20f/8f -- the icon+condition column read too small
// relative to the temp/hi-lo side once that side got its own weighted
// half of the card (confirmed 2026-07-13: "the sun icon and sunny need to
// be like 1.3x bigger").
private const val COMPACT_WEATHER_ICON_SP = 26f
private const val COMPACT_WEATHER_CONDITION_SP = 10f

/** Maps NWS's free-text shortForecast (e.g. "Partly Cloudy", "Chance
 * Showers", "Sunny") to one glyph via keyword match -- NWS also serves an
 * icon image per period, but this app has no image-download/caching
 * infrastructure and every other stat tile (gym/money/coursework) is
 * already emoji-based, so this keeps weather visually consistent with them
 * rather than introducing the only network-image dependency in the widget. */
internal fun weatherEmoji(condition: String?): String {
    val c = (condition ?: "").lowercase()
    return when {
        "thunder" in c -> "⛈️"
        "snow" in c || "flurr" in c || "sleet" in c -> "❄️"
        "rain" in c || "shower" in c || "drizzle" in c -> "🌧️"
        "fog" in c || "haze" in c || "mist" in c -> "🌫️"
        "wind" in c -> "💨"
        "cloud" in c || "overcast" in c -> "☁️"
        "clear" in c || "sunny" in c -> "☀️"
        else -> "🌤️"
    }
}

/** Current conditions card: big current temp + unit + today's high/low on
 * the left, condition glyph + label on the right -- own dark-blue card
 * (distinct from the neutral GlanceTheme surface every other section uses)
 * since weather is meant to read as its own weather-app-style glanceable
 * unit, matching the reference design. Renders as a full-width standalone
 * row (NOT part of [TILE_SECTIONS]'s shared gym/money/coursework strip --
 * this card is wider than the ~100dp stat-tile width and reads better on
 * its own row, especially as the single-stat "LifeOps Weather" preset's
 * only content).
 *
 * ONE composable for every bucket, reading [LocalSize] directly to pick
 * BASE_WEATHER_* (roomy) vs COMPACT_WEATHER_* (sized to fit a true 2x1
 * without clipping) -- not two separately-maintained copies. That used to
 * be two near-identical functions (WeatherSection + CompactWeatherTile)
 * with independently hand-tuned magic numbers, which is precisely the
 * anti-pattern Google's own Glance docs warn against (developer.android.com/
 * develop/ui/compose/glance/build-ui: "a single composable that checks
 * LocalSize.current" over per-size duplicates) -- and the direct cause of
 * this session's repeated bugs, where a fix (hi/lo alignment, maxLines,
 * textAlign) landed in one copy and was forgotten in the other (confirmed
 * 2026-07-13: "research what is idiomatic" / "fix all that shit"). The
 * threshold matches bucketFor's own SMALL cutoff (MEDIUM_SIZE.height) so
 * this never disagrees with the rest of BriefingContent about what counts
 * as small.
 *
 * Takes plain values, not a BriefingState, so the caller can feed it from
 * whichever source is actually fresh -- see BriefingContent's WEATHER
 * branch: NextTasksState.weather (refreshed ~every 15 min, same pull as
 * gym_ring) is preferred over BriefingState's own weather fields (stale
 * until the once-daily briefing runs again). Before 2026-07-15 this only
 * ever read BriefingState, so a widget checked mid-afternoon still showed
 * whatever NOAA said that morning. */
@Composable
private fun WeatherCard(
    temperatureF: Int?, weatherHighF: Int?, weatherLowF: Int?, weatherCondition: String?,
    scale: Float, modifier: GlanceModifier = GlanceModifier,
    // 0.dp from ComboGridContent -- see SeverityValueTile's cornerRadiusDp
    // doc for why an internal cell boundary must stay flat, not rounded.
    cornerRadiusDp: Dp = 12.dp,
    appPackage: String = "",
) {
    val temp = temperatureF ?: return
    val compact = LocalSize.current.height < MEDIUM_SIZE.height
    val tempSp = if (compact) COMPACT_WEATHER_TEMP_SP else BASE_WEATHER_TEMP_SP
    val unitSp = if (compact) COMPACT_WEATHER_UNIT_SP else BASE_WEATHER_UNIT_SP
    val hiloSp = if (compact) COMPACT_WEATHER_HILO_SP else BASE_WEATHER_HILO_SP
    val iconSp = if (compact) COMPACT_WEATHER_ICON_SP else BASE_WEATHER_ICON_SP
    val conditionSp = if (compact) COMPACT_WEATHER_CONDITION_SP else BASE_WEATHER_CONDITION_SP
    val cardPadding = if (compact) 10.dp else 12.dp
    val hiloGap = if (compact) 4.dp else 6.dp
    val iconGap = if (compact) 6.dp else 8.dp

    Row(
        modifier = externalAppClick(modifier, OpenExternalAppAction.TARGET_WEATHER, appPackage)
            .fillMaxWidth()
            .cornerRadius(cornerRadiusDp)
            .background(ColorProvider(WEATHER_BG))
            .padding(cardPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Two flat rows (temp+unit, then high/low side by side), NOT a Row
        // with a multi-line Column nested beside the big number -- that
        // shape looked right in WidgetConfigActivity's plain-Compose
        // preview but broke badly on the real Glance-rendered widget
        // (RemoteViews doesn't align mixed-height nested Row/Column
        // children the way Compose UI's preview does; confirmed
        // 2026-07-13, live device: "↑85°" floated in its own detached row
        // above "82°F↓65°" instead of stacking beside the number). Two
        // simple same-height rows is the reliable shape.
        //
        // At compact size this Column gets defaultWeight() (see below) so
        // it and the icon/condition column split the card roughly 50/50 --
        // at full size it stays unweighted and the icon column gets pushed
        // to the far right by its own trailing Spacer(defaultWeight())
        // instead, since the full card has room to spare either way.
        Column(modifier = if (compact) GlanceModifier.defaultWeight() else GlanceModifier) {
            // Glance's TextStyle has no baselineShift/superscript span --
            // there's no way to get a real raised-and-shrunk unit through
            // this API; Alignment.Top (aligning line-box tops, not
            // baselines) is the closest available approximation of a
            // top-right superscript (confirmed 2026-07-13, user's call
            // after trying Bottom).
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    text = "$temp",
                    style = TextStyle(fontWeight = FontWeight.Bold, fontSize = (tempSp * scale).sp,
                        color = ColorProvider(Color.White)),
                )
                Text(
                    text = "°F",
                    style = TextStyle(fontSize = (unitSp * scale).sp, color = ColorProvider(Color.White)),
                )
            }
            // Both on the same font size -- an earlier version had high
            // share a line with "°F" (so it used the unit's size) and low
            // sit alone below at its own smaller size; now that both are
            // side by side on one row, there's no reason for them to
            // differ (confirmed 2026-07-13, live device: high/low visibly
            // mismatched size).
            Row {
                weatherHighF?.let {
                    Text(text = "↑$it°", style = TextStyle(fontSize = (hiloSp * scale).sp,
                        color = ColorProvider(Color.White)))
                }
                if (weatherHighF != null && weatherLowF != null) {
                    Spacer(modifier = GlanceModifier.width(hiloGap))
                }
                weatherLowF?.let {
                    Text(text = "↓$it°", style = TextStyle(fontSize = (hiloSp * scale).sp,
                        color = ColorProvider(Color.White)))
                }
            }
        }
        // A fixed gap, not just defaultWeight()'s Spacer -- at a narrow
        // placed width (e.g. 2x1) there's little room left for
        // defaultWeight() to claim, so the icon/condition column ended up
        // pushed flush against the high/low text with no breathing room
        // (confirmed 2026-07-13, live device: "Mostly Sunny" nearly
        // touching "↓65°"). maxLines=1 + textAlign=Center on the condition
        // text stops a wrapped second line from reading as off-center --
        // Glance's Column only centers each Text's own bounding box, and an
        // unwrapped Text's bounding box is exactly as wide as its one line,
        // so a wrapped two-line label could look shifted relative to the
        // centered emoji above it.
        Spacer(modifier = GlanceModifier.width(iconGap))
        // Only push-right via an unweighted trailing Spacer at full size --
        // at compact size the icon/condition column instead gets its own
        // defaultWeight() below, splitting the card ~50/50 with the temp
        // side instead of just wrapping its own small content width
        // (confirmed 2026-07-13: "fill sunny to approximately half of the
        // 2x1 widget").
        if (!compact) {
            Spacer(modifier = GlanceModifier.defaultWeight())
        }
        Column(
            modifier = if (compact) GlanceModifier.defaultWeight() else GlanceModifier,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(text = weatherEmoji(weatherCondition),
                style = TextStyle(fontSize = (iconSp * scale).sp))
            weatherCondition?.let {
                Text(text = it, maxLines = 1, style = TextStyle(fontSize = (conditionSp * scale).sp,
                    color = ColorProvider(Color.White), textAlign = TextAlign.Center))
            }
        }
    }
}

/** Trailing "+N more" line for a truncated list -- so a cap (whether from
 * allocateDynamicListCounts's height budget or a server-side hard cap like
 * EVENTS_HARD_CEILING) is always VISIBLE as a cap, not indistinguishable
 * from "that's everything." A silent cut, even a correctly height-aware
 * one, still looks like a complete list unless it says otherwise. */
@Composable
private fun OverflowIndicator(hiddenCount: Int) {
    if (hiddenCount <= 0) return
    Text(
        text = "+$hiddenCount more",
        style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant),
    )
}

/** Today's real calendar events -- shown above "Up next" and independent of
 * the LLM-generated briefing text, since a $0 family event or anything not
 * framed as "at risk" would otherwise never surface (confirmed 2026-07-12:
 * a same-day BBQ went unmentioned because it wasn't a risk/deadline). This
 * is the deterministic "don't forget you have an obligation" line, not
 * advisory.
 *
 * [maxShown] comes from allocateDynamicListCounts (shared across every
 * dynamic list enabled in this instance) rather than showing every event
 * unconditionally -- until 2026-07-15 this rendered the full list with NO
 * height-awareness at all, meaning enough events could already silently
 * push the freshness line off the bottom on their own, independent of
 * whatever fix UP_NEXT had received. */
@Composable
private fun EventsSection(events: List<TodayEvent>, maxShown: Int) {
    val toShow = events.take(maxShown)
    Column(modifier = GlanceModifier.padding(top = 8.dp)) {
        for (event in toShow) {
            Text(
                text = formatEventLine(event),
                style = TextStyle(
                    fontWeight = FontWeight.Bold,
                    color = GlanceTheme.colors.onSurface,
                ),
            )
        }
        OverflowIndicator(events.size - toShow.size)
    }
}

@Composable
private fun UpNextSection(tasks: List<NextTask>, maxTasks: Int) {
    val tasksToShow = tasks.take(maxTasks)
    Column(modifier = GlanceModifier.padding(top = 8.dp)) {
        Text(
            text = "Up next",
            style = TextStyle(
                fontWeight = FontWeight.Bold,
                color = GlanceTheme.colors.onSurface,
            ),
        )
        for (task in tasksToShow) {
            NextTaskRow(task)
        }
        OverflowIndicator(tasks.size - tasksToShow.size)
    }
}

/** Upcoming, deterministic, infrequent/one-off calendar events (see
 * server-side notable_events.py's rolling-next-7-days window, NOT a fixed
 * calendar week) -- a haircut every 5 weeks, a doctor's appointment, a BBQ,
 * as opposed to [EventsSection]'s "every timed event TODAY regardless of
 * recurrence." Single composable branching on [LocalSize.current], matching
 * [WeatherCard]'s pattern (see the Android conventions doc: one composable
 * per section, not duplicated per-bucket copies) -- collapses to just the
 * soonest event at small sizes (the "LifeOps Events" solo preset's
 * 1x1/2x1 footprint), shows the fuller (height-budgeted) list otherwise.
 * [maxShown] is only consulted in the list path; it comes from
 * allocateDynamicListCounts, shared with TODAY_EVENTS/UP_NEXT so all three
 * can't jointly overflow the widget.
 *
 * Renders an explicit empty state, rather than nothing at all, when
 * [events] is empty -- unlike money/weather/social (whose null fields only
 * ever mean "hasn't fetched yet," a transient bootstrap state), a genuinely
 * empty upcoming-events list is a normal, common, VALID result (most weeks
 * have zero one-off events), so the standalone "LifeOps Events" widget
 * previously just rendered as a permanent blank box whenever that was true
 * -- not a bug in the data, but a real gap in the widget (confirmed
 * 2026-07-15: "what if we don't have any events there? ... this widget
 * should be dynamic"). */
@Composable
private fun NotableEventsSection(events: List<NotableEvent>, maxShown: Int, scale: Float) {
    val compact = LocalSize.current.height < MEDIUM_SIZE.height
    if (events.isEmpty()) {
        if (compact) {
            SoloStatCard(label = "EVENTS", value = "None", status = "CLEAR",
                accent = MONEY_SOLO_OK_ACCENT, scale = scale, modifier = GlanceModifier.fillMaxSize())
        } else {
            Column(modifier = GlanceModifier.padding(top = 8.dp)) {
                Text(
                    text = "Coming up",
                    style = TextStyle(fontWeight = FontWeight.Bold, color = GlanceTheme.colors.onSurface),
                )
                Text(
                    text = "Nothing scheduled",
                    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant),
                )
            }
        }
        return
    }
    if (compact) {
        // Same dark card/label/value/status-bar shape every other solo
        // single-stat preset now uses (SoloMoneyTile, SoloCourseworkTile,
        // SoloSleepTile, SocialFocusTile) -- until this change, this was
        // the one solo preset still rendering as plain unstyled Text,
        // inconsistent with the rest of the family (2026-07-15: "make the
        // ux of the unusual events consistent with the vibe for the other
        // widgets").
        SoloNotableEventTile(events.first(), scale, modifier = GlanceModifier.fillMaxSize())
        return
    }
    val toShow = events.take(maxShown)
    Column(modifier = GlanceModifier.padding(top = 8.dp)) {
        Text(
            text = "Coming up",
            style = TextStyle(fontWeight = FontWeight.Bold, color = GlanceTheme.colors.onSurface),
        )
        for (event in toShow) {
            NotableEventChip(event, scale, modifier = GlanceModifier.fillMaxWidth().padding(top = 4.dp))
        }
        OverflowIndicator(events.size - toShow.size)
    }
}

// No severity dimension for a notable event (it's informational, not "at
// risk" like money/coursework) -- always the neutral/OK accent, same as
// SocialFocusTile's own "planned" (days-until known) case.
@Composable
private fun SoloNotableEventTile(event: NotableEvent, scale: Float, modifier: GlanceModifier) {
    SoloStatCard(
        label = "EVENTS",
        value = event.title,
        status = event.weekday.take(3).uppercase(),
        accent = MONEY_SOLO_OK_ACCENT,
        scale = scale,
        modifier = modifier,
    )
}

// Same rounded, tinted-background chip shape SocialTile/StatTile already
// use for the full combo widget's shared-row content, so a notable event
// reads as one consistent family of tile with the rest of the widget
// rather than a plain unstyled text line sitting next to them.
@Composable
private fun NotableEventChip(event: NotableEvent, scale: Float, modifier: GlanceModifier = GlanceModifier) {
    Row(
        modifier = modifier
            .cornerRadius(10.dp)
            .background(GlanceTheme.colors.surfaceVariant)
            .padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = "📅", style = TextStyle(fontSize = (17f * scale).sp))
        Spacer(modifier = GlanceModifier.width(4.dp))
        // NotableEventLine (day/time in their own fixed-width columns, then
        // title) -- same reasoning as ComboEventsTile: this is also a
        // stacked list (NotableEventsSection renders several of these), so
        // it has the identical alignment need.
        NotableEventLine(event, scale, 15f)
    }
}

/** "as of" info line -- when this snapshot was received, if known. A stale
 * snapshot is worse than a missing one (it looks current but isn't), so
 * anything past STALE_THRESHOLD_MINUTES gets a warning glyph ahead of the
 * age, not just the age itself. Not a user-configurable [WidgetSection] --
 * it's a freshness indicator, not content -- so it's pinned to render right
 * after the section loop finishes, regardless of section order. */
@Composable
private fun StaleIndicator(fetchedAtEpochMillis: Long?) {
    val fetchedAt = fetchedAtEpochMillis ?: return
    val ageMinutes = (System.currentTimeMillis() - fetchedAt) / 60_000L
    val label = if (ageMinutes >= STALE_THRESHOLD_MINUTES) {
        "⚠ stale, as of ${relativeTime(fetchedAt)}"
    } else {
        "as of ${relativeTime(fetchedAt)}"
    }
    Text(
        text = label,
        style = TextStyle(
            color = if (ageMinutes >= STALE_THRESHOLD_MINUTES) {
                ColorProvider(COLOR_WARN)
            } else {
                GlanceTheme.colors.onSurfaceVariant
            },
        ),
    )
}

private const val STALE_THRESHOLD_MINUTES = 120L
private const val BASE_GYM_BAR_WIDTH_DP = 60
private const val STAT_TILE_WIDTH_DP = 100

/** Compact proportional meter: filled portion in teal once at/above target,
 * amber while short of it -- same two colors the stat text already used, now
 * a shape instead of just a fraction. Glance's RowScope only exposes an
 * equal (1x) weight modifier, not an arbitrary numeric one, so the fill
 * ratio is expressed as two explicit dp widths instead of weights. */
@Composable
private fun GymBar(completed: Int, target: Int, scale: Float) {
    val barWidthDp = BASE_GYM_BAR_WIDTH_DP * scale
    val ratio = if (target > 0) (completed.toFloat() / target.toFloat()).coerceIn(0f, 1f) else 0f
    val filledDp = (barWidthDp * ratio).toInt()
    val barColor = if (completed >= target) COLOR_OK else COLOR_WARN

    // Wrapped in a Column so this whole fallback counts as ONE child of
    // whatever row/column calls it (see the container-child-limit note on
    // AttentionHeader) -- rare path, only used before nextTasks.gymRing has
    // loaded for the first time.
    Column {
        Text(
            text = "Gym ${completed}/${target} (7d)",
            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant),
        )
        Row(modifier = GlanceModifier.height(6.dp).padding(top = 2.dp)) {
            if (filledDp > 0) {
                Box(modifier = GlanceModifier.width(filledDp.dp).height(6.dp)
                    .background(ColorProvider(barColor))) {}
            }
            if (filledDp < barWidthDp.toInt()) {
                Box(modifier = GlanceModifier.width((barWidthDp.toInt() - filledDp).dp).height(6.dp)
                    .background(GlanceTheme.colors.surfaceVariant)) {}
            }
        }
    }
}

// 48dp matches Material's large-icon / minimum-touch-target size (the ring
// sits inside BriefingContent's clickable OpenPanelAction area, so this also
// gives it its own reasonably-sized tap target). Stroke is ~10% of the
// diameter -- in line with how fitness-ring widgets (Google Fit, Apple
// Fitness) weight their rings so the fill is legible at a glance instead of
// reading as a thin hairline. Emoji size is scaled up with it to keep the
// same visual proportion the previous 44dp/20sp pairing had.
private const val BASE_GYM_RING_SIZE_DP = 48
private const val BASE_GYM_RING_STROKE_DP = 5
private const val BASE_GYM_RING_EMOJI_SP = 22f
private const val GYM_PROGRESS_RING_BITMAP_PX = 96
private const val GYM_PROGRESS_RING_STROKE_PX = 10f
private const val GYM_PROGRESS_ICON = "🏋"
private val GYM_RING_RED = Color(0xFFB3261E)

@Composable
private fun SoloGymTile(gymRing: GymRing, scale: Float, modifier: GlanceModifier, appPackage: String = "") {
    GymProgressCard(gymRing, scale, modifier, appPackage = appPackage)
}

@Composable
private fun SoloGymFallbackTile(
    completed: Int, target: Int, scale: Float, modifier: GlanceModifier, appPackage: String = "",
) {
    GymFallbackProgressCard(completed, target, scale, modifier, appPackage = appPackage)
}

internal fun gymStatPresentation(gymRing: GymRing): SoloStatPresentation {
    val status = gymStatus(gymRing)
    return SoloStatPresentation("GYM", "${gymRing.gymLast7d}/${gymRing.gymTarget}", status,
        gymAccent(gymRing.color))
}

internal fun gymFallbackStatPresentation(completed: Int, target: Int): SoloStatPresentation {
    val color = gymFallbackColor(completed, target)
    return SoloStatPresentation("GYM", "$completed/$target", "7 DAYS", gymAccent(color))
}

private fun gymStatus(gymRing: GymRing): String = when {
    gymRing.todayDone -> "DONE"
    gymRing.color == "yellow" -> "TODAY"
    gymRing.color == "red" -> "START"
    else -> "OK TODAY"
}

private fun gymFallbackColor(completed: Int, target: Int): String = when {
    completed <= 0 -> "red"
    completed >= target -> "green"
    else -> "yellow"
}

@Composable
private fun GymProgressCard(
    gymRing: GymRing,
    scale: Float,
    modifier: GlanceModifier,
    cornerRadiusDp: Dp = 14.dp,
    statusVerticalPaddingDp: Dp = 5.dp,
    appPackage: String = "",
    compact: Boolean = false,
) {
    GymProgressCard(
        completed = gymRing.gymLast7d,
        target = gymRing.gymTarget,
        fill = gymRing.fill,
        color = gymRing.color,
        needDescription = gymNeedDescription(gymRing.color),
        scale = scale,
        modifier = modifier,
        cornerRadiusDp = cornerRadiusDp,
        statusVerticalPaddingDp = statusVerticalPaddingDp,
        appPackage = appPackage,
        compact = compact,
    )
}

@Composable
private fun GymFallbackProgressCard(
    completed: Int,
    target: Int,
    scale: Float,
    modifier: GlanceModifier,
    cornerRadiusDp: Dp = 14.dp,
    statusVerticalPaddingDp: Dp = 5.dp,
    appPackage: String = "",
    compact: Boolean = false,
) {
    val fill = if (target > 0) (completed.toFloat() / target.toFloat()).coerceIn(0f, 1f) else 0f
    GymProgressCard(
        completed = completed,
        target = target,
        fill = fill,
        color = "neutral",
        needDescription = "today status unavailable",
        scale = scale,
        modifier = modifier,
        cornerRadiusDp = cornerRadiusDp,
        statusVerticalPaddingDp = statusVerticalPaddingDp,
        appPackage = appPackage,
        compact = compact,
    )
}

@Composable
private fun GymProgressCard(
    completed: Int,
    target: Int,
    fill: Float,
    color: String,
    needDescription: String,
    scale: Float,
    modifier: GlanceModifier,
    cornerRadiusDp: Dp,
    statusVerticalPaddingDp: Dp,
    appPackage: String = "",
    compact: Boolean = false,
) {
    val ringSizeDp = if (compact) 36f * scale else 56f * scale
    val labelSp = if (compact) 0f else 8f
    val iconSp = if (compact) 14f else 20f
    val verticalPadding = if (compact) 1.dp else statusVerticalPaddingDp
    val ringColor = gymRingColor(color)
    val healthPercent = (fill.coerceIn(0f, 1f) * 100).roundToInt()
    val bitmap = renderGymRingBitmap(
        sizePx = GYM_PROGRESS_RING_BITMAP_PX,
        strokeWidthPx = GYM_PROGRESS_RING_STROKE_PX,
        fill = fill,
        trackColor = Color(0x33FFFFFF).toArgb(),
        fillColor = ringColor.toArgb(),
    )
    Column(
        modifier = externalAppClick(modifier, OpenExternalAppAction.TARGET_GYM, appPackage)
            .cornerRadius(cornerRadiusDp)
            .background(ColorProvider(MONEY_SOLO_BG))
            .padding(vertical = verticalPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalAlignment = Alignment.Top,
    ) {
        Spacer(modifier = GlanceModifier.defaultWeight())
        if (!compact) {
            Text(
                text = "GYM",
                maxLines = 1,
                style = TextStyle(
                    fontWeight = FontWeight.Bold,
                    fontSize = (labelSp * scale).sp,
                    color = GlanceTheme.colors.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                ),
                modifier = GlanceModifier.fillMaxWidth(),
            )
        }
        Box(
            modifier = GlanceModifier.size(ringSizeDp.dp).padding(top = if (compact) 0.dp else 2.dp),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                provider = ImageProvider(bitmap),
                contentDescription = "Gym health $healthPercent%, $needDescription",
                modifier = GlanceModifier.fillMaxSize(),
            )
            Text(
                text = GYM_PROGRESS_ICON,
                maxLines = 1,
                style = TextStyle(
                    fontWeight = FontWeight.Bold,
                    fontSize = (iconSp * scale).sp,
                    color = GlanceTheme.colors.onSurface,
                    textAlign = TextAlign.Center,
                ),
            )
        }
        Spacer(modifier = GlanceModifier.defaultWeight())
    }
}

private fun gymAccent(color: String): Color = when (color) {
    "green" -> MONEY_SOLO_OK_ACCENT
    "yellow" -> MONEY_SOLO_WATCH_ACCENT
    else -> MONEY_SOLO_RISK_ACCENT
}

private fun gymRingColor(color: String): Color = when (color) {
    "green" -> COLOR_OK
    "yellow" -> COLOR_WARN
    "neutral" -> Color(0xFF9A9A9A)
    else -> GYM_RING_RED
}

private fun gymNeedDescription(color: String): String = when (color) {
    "yellow" -> "needs gym today"
    "green" -> "no gym needed today"
    else -> "gym drought"
}

private fun externalAppClick(modifier: GlanceModifier, target: String, packageName: String): GlanceModifier {
    if (WidgetDisplayConfig.isPanelTarget(packageName)) {
        return modifier.clickable(actionRunCallback<OpenPanelAction>())
    }
    return if (packageName.isBlank()) {
        modifier.clickable(actionRunCallback<OpenExternalAppAction>(
            actionParametersOf(OpenExternalAppAction.TARGET_KEY to target),
        ))
    } else {
        modifier.clickable(actionRunCallback<OpenExternalAppAction>(
            actionParametersOf(
                OpenExternalAppAction.TARGET_KEY to target,
                OpenExternalAppAction.PACKAGE_KEY to packageName,
            ),
        ))
    }
}

/** Draws the ring as a bitmap via plain android.graphics.Canvas/Paint --
 * Glance has no native arc/canvas composable, so this is the standard
 * workaround: render once per recomposition, then display through Glance's
 * Image(ImageProvider(bitmap)). Sweeps clockwise from 12 o'clock (-90deg),
 * matching the conventional progress-ring orientation. */
private fun renderGymRingBitmap(sizePx: Int, strokeWidthPx: Float, fill: Float, trackColor: Int, fillColor: Int): Bitmap {
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val inset = strokeWidthPx / 2f
    val rect = RectF(inset, inset, sizePx - inset, sizePx - inset)
    val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokeWidthPx
        strokeCap = Paint.Cap.ROUND
        color = trackColor
    }
    canvas.drawArc(rect, 0f, 360f, false, trackPaint)
    val clampedFill = fill.coerceIn(0f, 1f)
    if (clampedFill > 0f) {
        val fillPaint = Paint(trackPaint).apply { color = fillColor }
        canvas.drawArc(rect, -90f, 360f * clampedFill, false, fillPaint)
    }
    return bitmap
}

// Only applied when the ring is a widget's SOLE content (the standalone
// "LifeOps Gym" preset) -- inside the shared combo-widget row it sits
// beside money/coursework/sleep tiles sized for BASE_GYM_RING_SIZE_DP, so
// scaling it up there would throw that row out of alignment (confirmed
// 2026-07-14: "I want it, by default, to be 1.5x the size" was about the
// standalone gym widget's icon specifically). Bumped 1.5f -> 1.875f, both
// confirmed via live-device feedback. 2f is NOT yet confirmed the same
// way -- it's an untested "just try doubling it, wanna see what happens"
// value; unlike the two before it, treat this one as provisional until
// checked on a real device. SOLO_GYM_RING_OVERHEAD_DP's clamp against the
// widget's real placed size means this is a ceiling the ring reaches for,
// not a guaranteed final size, which bounds how wrong an unvalidated value
// here can go (it'll just clamp down, not overflow), but doesn't make it
// correct.
private const val SOLO_GYM_RING_SCALE = 2f
private const val SOLO_GYM_RING_EMOJI_SCALE = 0.82f

// TileRow's own 4dp top padding on the Row wrapping this ring -- subtracted
// from the widget's REAL placed size (not the declared minWidth/minHeight in
// gym_widget_info.xml) before sizing the ring at SOLO_GYM_RING_SCALE.
// Samsung's launcher (confirmed via live-device testing, see
// android/CLAUDE.md) doesn't always honor a widget-info XML's declared
// size for the actual placed footprint, so a ring sized only off the XML
// constant clipped against the real, smaller rendered bounds (confirmed
// 2026-07-14: "that's a fantastic size, but it's clipping again" after
// bumping the XML alone). Reading LocalSize.current here instead makes
// this self-correcting regardless of what the launcher actually grants.
private const val SOLO_GYM_RING_OVERHEAD_DP = 4f
private const val SOLO_GYM_RING_MIN_SIZE_DP = 24f

/** Gym ring: fill = trailing-7-day adherence ratio (grows only as real
 * sessions accumulate), color = same-day action signal, intentionally
 * decoupled -- see GymRing's docstring. Rendered as a bitmap ring (see
 * renderGymRingBitmap) with the gym emoji layered on top via a centered Box.
 * [solo] (this ring is the widget's only content, e.g. the standalone
 * "LifeOps Gym" preset) applies [SOLO_GYM_RING_SCALE] on top of [scale],
 * clamped to the widget's actual placed size (see
 * [SOLO_GYM_RING_OVERHEAD_DP]) so it can never clip. */
@Composable
private fun GymRingIndicator(gymRing: GymRing, scale: Float, solo: Boolean = false) {
    val context = LocalContext.current
    val density = context.resources.displayMetrics.density
    val effectiveScale = scale * (if (solo) SOLO_GYM_RING_SCALE else 1f)
    val desiredSizeDp = BASE_GYM_RING_SIZE_DP * effectiveScale
    val ringSizeDp = if (solo) {
        val placedSize = LocalSize.current
        val availableDp = minOf(placedSize.width.value, placedSize.height.value) - SOLO_GYM_RING_OVERHEAD_DP
        // maxOf(MIN, minOf(desired, available)), not coerceIn(MIN, available)
        // -- coerceIn throws if its max ends up below its min, which a
        // widget placed smaller than SOLO_GYM_RING_MIN_SIZE_DP would trigger
        // (availableDp could be < MIN). maxOf/minOf need no such precondition
        // and read directly as "never below MIN, never above available".
        maxOf(SOLO_GYM_RING_MIN_SIZE_DP, minOf(desiredSizeDp, availableDp))
    } else {
        desiredSizeDp
    }
    // Derived from the ring's actual (possibly clamped) size, not
    // effectiveScale directly -- keeps the stroke/emoji proportional even
    // when SOLO_GYM_RING_OVERHEAD_DP clamping shrunk the ring below its
    // desired size.
    val actualRingScale = ringSizeDp / BASE_GYM_RING_SIZE_DP
    val sizePx = (ringSizeDp * density).roundToInt()
    val strokePx = BASE_GYM_RING_STROKE_DP * actualRingScale * density
    val ringColor = when (gymRing.color) {
        "green" -> COLOR_OK
        "yellow" -> COLOR_WARN
        else -> GYM_RING_RED
    }
    val bitmap = renderGymRingBitmap(
        sizePx = sizePx,
        strokeWidthPx = strokePx,
        fill = gymRing.fill,
        trackColor = Color(0x33FFFFFF).toArgb(),
        fillColor = ringColor.toArgb(),
    )
    Box(
        modifier = GlanceModifier.size(ringSizeDp.dp),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            provider = ImageProvider(bitmap),
            contentDescription = "Gym ${gymRing.gymLast7d}/${gymRing.gymTarget}, ${gymRing.color}",
            modifier = GlanceModifier.fillMaxSize(),
        )
        val emojiScale = if (solo) SOLO_GYM_RING_EMOJI_SCALE else 1f
        Text(text = "🏋", style = TextStyle(fontSize = (BASE_GYM_RING_EMOJI_SP * actualRingScale * emojiScale).sp))
    }
}

@Composable
private fun NextTaskRow(task: NextTask) {
    CheckBox(
        checked = false, // a completed task drops off the list on the next refresh rather than rendering checked-then-vanishing
        onCheckedChange = actionRunCallback<CompleteTaskAction>(
            actionParametersOf(CompleteTaskAction.TASK_ID_KEY to task.id),
        ),
        text = task.title,
        style = TextStyle(color = GlanceTheme.colors.onSurface),
        modifier = GlanceModifier.fillMaxWidth(),
    )
}

/**
 * Splits [line] into (text, isBold) runs on `**...**` pairs.
 *
 * Examples:
 *  - "Hello **world**!" -> [("Hello ", false), ("world", true), ("!", false)]
 *  - "**Bold start** then normal" -> [("Bold start", true), (" then normal", false)]
 *  - "Just plain text" -> [("Just plain text", false)]
 *  - "" -> [("", false)]
 */
private fun parseMarkupLine(line: String): List<Pair<String, Boolean>> {
    val boldRegex = Regex("\\*\\*(.+?)\\*\\*")
    val result = mutableListOf<Pair<String, Boolean>>()
    var lastIndex = 0
    for (match in boldRegex.findAll(line)) {
        if (match.range.first > lastIndex) {
            result.add(line.substring(lastIndex, match.range.first) to false)
        }
        result.add(match.groupValues[1] to true)
        lastIndex = match.range.last + 1
    }
    if (lastIndex < line.length) {
        result.add(line.substring(lastIndex) to false)
    }
    if (result.isEmpty()) {
        result.add(line to false)
    }
    return result
}

private val EVENT_TIME_FORMAT = DateTimeFormatter.ofPattern("h:mm a")

/** "Jane BBQ at Papa's @ 6:00 PM", or just the title if [event]'s start
 * couldn't be parsed (malformed/missing -- show the obligation anyway
 * rather than dropping it). */
private fun formatEventLine(event: TodayEvent): String {
    val time = event.start?.let {
        try {
            LocalDateTime.parse(it).format(EVENT_TIME_FORMAT)
        } catch (e: DateTimeParseException) {
            null
        }
    }
    return if (time != null) "${event.title} @ $time" else event.title
}

/** 3-letter day abbreviation, the first of the two "when" pieces -- kept
 * separate from [notableEventTime] (rather than one combined "Thu 6:00 PM"
 * string) so callers render each in its OWN fixed-width column. That's the
 * only way to get real pixel alignment down a scrollable list with a
 * proportional font: matching character COUNT at the same string index
 * ("Thu "/"Fri "/"Sat " are all 4 characters) still doesn't guarantee the
 * same x-coordinate once rendered, since a proportional font's glyphs
 * aren't equal-width (confirmed 2026-07-15 research: real agenda/calendar
 * widgets, and tabular/monospaced-figure typography generally, solve
 * exactly this by giving numeric/date columns their own fixed width
 * rather than relying on character count within one string). */
internal fun notableEventDay(event: NotableEvent): String = event.weekday.take(3)

/** "6:00 PM", or null if [event]'s start is missing/unparseable -- same
 * "show it anyway rather than drop it" reasoning as formatEventLine. */
internal fun notableEventTime(event: NotableEvent): String? = event.start?.let {
    try {
        LocalDateTime.parse(it).format(EVENT_TIME_FORMAT)
    } catch (e: DateTimeParseException) {
        null
    }
}

// Fixed sub-column widths for [NotableEventLine] -- see notableEventDay's
// doc for why these need to be real layout columns, not just consistent
// character counts. Not a real measurement (Glance/RemoteViews has no
// layout-measurement API, same caveat as maxTasksForHeight elsewhere in
// this file) -- just wide enough for "Sat"/"12:00 PM" at each caller's
// font size without starving the title column.
private const val NOTABLE_EVENT_DAY_WIDTH_DP = 26
private const val NOTABLE_EVENT_TIME_WIDTH_DP = 54

/** Shared by [ComboEventsTile] and [NotableEventChip]: day column, then
 * time column (both fixed-width, [FontFamily.Monospace] so the digits
 * themselves are equal-width too -- the same tabular-figure technique real
 * UIs use for numeric columns, since Glance's TextStyle has no
 * font-feature-settings/tabular-nums support to reach for directly), then
 * the title filling the rest of the row. Time-leading (day+time before
 * title) matches how real agenda/calendar widgets convey a scannable "when"
 * list -- not [formatEventLine]'s trailing "@ time" (that convention fits
 * TODAY_EVENTS, a same-day list where a bare time is enough; NOTABLE_EVENTS
 * spans this whole week, so it needs a day too). */
@Composable
private fun NotableEventLine(event: NotableEvent, scale: Float, fontSp: Float, modifier: GlanceModifier = GlanceModifier) {
    val monospaceStyle = TextStyle(fontWeight = FontWeight.Bold, fontSize = (fontSp * scale).sp,
        fontFamily = FontFamily.Monospace, color = GlanceTheme.colors.onSurface)
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(text = notableEventDay(event), maxLines = 1, style = monospaceStyle,
            modifier = GlanceModifier.width((NOTABLE_EVENT_DAY_WIDTH_DP * scale).dp))
        notableEventTime(event)?.let { time ->
            Text(text = time, maxLines = 1, style = monospaceStyle,
                modifier = GlanceModifier.width((NOTABLE_EVENT_TIME_WIDTH_DP * scale).dp))
        }
        Text(
            text = event.title,
            maxLines = 1,
            style = TextStyle(fontWeight = FontWeight.Bold, fontSize = (fontSp * scale).sp,
                color = GlanceTheme.colors.onSurface),
        )
    }
}

/** Coarse "Xm/Xh/Xd ago" label from an epoch-millis timestamp to now. */
private fun relativeTime(fetchedAtEpochMillis: Long): String {
    val diffMinutes = (System.currentTimeMillis() - fetchedAtEpochMillis) / 60_000L
    return when {
        diffMinutes < 1 -> "just now"
        diffMinutes < 60 -> "${diffMinutes}m ago"
        diffMinutes < 60 * 24 -> "${diffMinutes / 60}h ago"
        else -> "${diffMinutes / (60 * 24)}d ago"
    }
}
