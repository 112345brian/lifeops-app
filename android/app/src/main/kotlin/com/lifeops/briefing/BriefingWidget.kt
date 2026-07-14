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
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.lifeops.briefing.data.AttentionReason
import com.lifeops.briefing.data.BriefingState
import com.lifeops.briefing.data.GymRing
import com.lifeops.briefing.data.NextTask
import com.lifeops.briefing.data.NextTasksState
import com.lifeops.briefing.data.TodayEvent
import com.lifeops.briefing.data.WidgetDisplayConfig
import com.lifeops.briefing.data.WidgetSection
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlin.math.roundToInt

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

            GlanceTheme {
                BriefingContent(briefing, nextTasks, config)
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
 * row (see SocialSection) so it fits SMALL as-is. WEATHER's full card
 * (temp+hi/lo+condition text) doesn't fit a single compact row, so it
 * renders as a plain StatTile (icon + temp only) at SMALL instead of the
 * full WeatherSection -- see the WEATHER branch below. Lets a
 * single-preset Weather/Social widget actually shrink to Android's real
 * minimum footprint instead of being stuck at a 150dp MEDIUM floor
 * (confirmed 2026-07-13: user wanted these resizable down like every
 * other single-stat preset). */
private val SMALL_BUCKET_ALLOWED = TILE_SECTIONS + WidgetSection.SOCIAL + WidgetSection.WEATHER

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
) {
    val bucket = bucketFor(LocalSize.current)
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
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .padding(12.dp)
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
                showInlineDots = dotsInline, scale = config.scale)
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
        // sharing a row with 2-3 siblings on the full combo widget.
        val solo = !showBadge && renderableOrder.size == 1
        for (group in groupSectionsForRendering(renderableOrder)) {
            if (group.size > 1) {
                TileRow(group, state, nextTasks.gymRing, config.scale)
                continue
            }
            when (val section = group.first()) {
                WidgetSection.SEVERITY_DOTS -> StandaloneSeverityDots(state.reasons, config.scale)
                WidgetSection.GYM_RING, WidgetSection.MONEY_TILE, WidgetSection.COURSEWORK_TILE,
                WidgetSection.SLEEP_TILE ->
                    SoloableTileRow(section, state, nextTasks.gymRing, config.scale, solo)
                WidgetSection.BRIEFING_PARAGRAPH -> {
                    val text = state.text
                    if (bucket == WidgetSizeBucket.LARGE && text != null) BriefingParagraph(text, config.scale)
                }
                WidgetSection.TODAY_EVENTS ->
                    if (bucket == WidgetSizeBucket.LARGE && nextTasks.events.isNotEmpty()) {
                        EventsSection(nextTasks.events)
                    }
                WidgetSection.UP_NEXT ->
                    if (bucket == WidgetSizeBucket.LARGE && nextTasks.tasks.isNotEmpty()) {
                        val heightDp = LocalSize.current.height.value.toInt()
                        UpNextSection(nextTasks.tasks,
                            effectiveMaxTasks(heightDp, config.maxTasksOverride, reserveForStaleIndicator = state.text != null))
                    }
                WidgetSection.WEATHER ->
                    if (state.temperatureF != null) {
                        if (bucket == WidgetSizeBucket.SMALL) {
                            if (solo) {
                                // fillMaxSize() directly on the card, not
                                // just a fillMaxSize Box centering a
                                // content-sized card inside it -- the blue
                                // background only stretched to cover its
                                // own compact content height, leaving plain
                                // background above/below it instead of
                                // actually filling the widget's real 2x1
                                // area (confirmed 2026-07-13, live device:
                                // "keep the blue shit as filled out to 2x1
                                // as you can").
                                CompactWeatherTile(state, config.scale, modifier = GlanceModifier.fillMaxSize())
                            } else {
                                CompactWeatherTile(state, config.scale, modifier = GlanceModifier.padding(top = 4.dp))
                            }
                        } else {
                            // Same fillMaxSize() treatment as
                            // CompactWeatherTile above, for whichever bucket
                            // this real device's "2x1" placement actually
                            // measures as -- bucketFor is height-only, and
                            // this device's real per-cell height for a
                            // declared 1-row widget may not match the SMALL
                            // threshold assumed elsewhere, in which case
                            // WeatherSection (not CompactWeatherTile) is
                            // what's actually rendering, and it never got
                            // this fix at all (confirmed 2026-07-13, live
                            // device: fillMaxSize on CompactWeatherTile alone
                            // produced zero visible change).
                            WeatherSection(state, config.scale,
                                modifier = if (solo) GlanceModifier.fillMaxSize() else GlanceModifier)
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
                        SocialSection(state, config.scale, stacked = solo)
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
private const val TASK_ROW_HEIGHT_DP = 34
private const val MIN_TASKS_SHOWN = 3

// Glance/RemoteViews has no scrolling Column -- content past the widget's
// actual placed height just gets silently clipped, not scrolled. Reserved
// so the freshness line ("as of 2m ago") always has room below the task
// list instead of being the first thing clipped off as maxTasksForHeight
// greedily fills every extra dp with more task rows (confirmed 2026-07-13:
// resizing to fit more tasks pushed the freshness line off the bottom).
private const val STALE_INDICATOR_RESERVED_DP = 20

// The "Up next" container holds a header Text plus N NextTaskRows as direct
// children (N+1 total) -- Glance's hard 10-direct-children-per-container
// limit (see the note above AttentionHeader) means an unclamped user
// override could reproduce the exact silent-crash bug already fixed once
// this session. 9 leaves room for the header.
private const val MAX_TASKS_HARD_CEILING = 9

/** Rough estimate of how many "Up next" rows fit past the base ~250dp LARGE
 * layout -- not a real measurement (Glance/RemoteViews has no layout-
 * measurement API), just enough to make a widget placed taller than 4x4
 * actually use the extra room instead of sitting on a fixed 3-task list
 * with dead space below it. STALE_INDICATOR_RESERVED_DP is subtracted
 * before dividing into rows so growth never spends 100% of the extra
 * height on tasks alone -- but only when [reserveForStaleIndicator] is
 * true; StaleIndicator itself never draws when state.text is null, so
 * reserving room for it unconditionally would cost a task row's worth of
 * height for a line that was never going to render (confirmed
 * 2026-07-13). */
private fun maxTasksForHeight(heightDp: Int, reserveForStaleIndicator: Boolean): Int {
    val reserved = if (reserveForStaleIndicator) STALE_INDICATOR_RESERVED_DP else 0
    val extraDp = (heightDp - LARGE_BASE_HEIGHT_DP - reserved).coerceAtLeast(0)
    return MIN_TASKS_SHOWN + extraDp / TASK_ROW_HEIGHT_DP
}

/** Combines the placed-size heuristic with the user's explicit override
 * (if any) -- clamp, don't replace: a user ceiling still can't exceed what
 * actually fits height-wise, nor Glance's hard container-child limit. */
private fun effectiveMaxTasks(heightDp: Int, maxTasksOverride: Int?, reserveForStaleIndicator: Boolean): Int {
    return listOfNotNull(maxTasksForHeight(heightDp, reserveForStaleIndicator), maxTasksOverride, MAX_TASKS_HARD_CEILING).min()
}

/** Shared with GymBar's on/off-target color and StaleIndicator's warning
 * color -- all three signal "this is fine" vs. "this needs attention" with
 * the same two colors, so they're defined once here rather than as three
 * independently-typed hex literals that could silently drift apart. */
private val COLOR_OK = Color(0xFF276B5E)
private val COLOR_WARN = Color(0xFFA8641F)

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
 * line, matching MoneyTile's single-line height in the shared TileRow. */
@Composable
private fun StatTile(
    emoji: String, value: String, scale: Float, modifier: GlanceModifier = GlanceModifier,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    emojiSp: Float = BASE_TILE_EMOJI_SP, valueSp: Float = BASE_TILE_VALUE_SP, tilePadding: Dp = 6.dp,
) {
    Row(
        modifier = modifier
            .cornerRadius(10.dp)
            .background(GlanceTheme.colors.surfaceVariant)
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

private val MONEY_TILE_OK_BG = Color(0x4D276B5E)
private val MONEY_TILE_WATCH_BG = Color(0x4DA8641F)
private val MONEY_TILE_RISK_BG = Color(0x4DB3261E)
private const val BASE_MONEY_FONT_SP = 22f

/** No glyph, no label -- just the dollar figure, big and bold, since it's
 * the one number where the amount itself IS the message. Background color
 * carries the same money severity attention.compute() already decided --
 * green when fine, transparent yellow when the buffer's thin, red when
 * discretionary has gone negative -- looked up from state.reasons rather
 * than re-deriving the <0/<100 thresholds here, so the widget can never
 * disagree with the engine that owns severity. */
@Composable
private fun MoneyTile(
    dollars: Int, severity: String?, scale: Float, modifier: GlanceModifier = GlanceModifier,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
) {
    val bg = when (severity) {
        "risk", "fucked" -> MONEY_TILE_RISK_BG
        "watch" -> MONEY_TILE_WATCH_BG
        else -> MONEY_TILE_OK_BG
    }
    Column(
        modifier = modifier
            .cornerRadius(10.dp)
            .background(ColorProvider(bg))
            .padding(6.dp),
        horizontalAlignment = horizontalAlignment,
    ) {
        Text(
            text = formatMoney(dollars),
            style = TextStyle(fontWeight = FontWeight.Bold, fontSize = (BASE_MONEY_FONT_SP * scale).sp,
                color = GlanceTheme.colors.onSurface),
        )
    }
}

internal fun formatMoney(dollars: Int): String = "$$dollars"

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
) {
    if (solo) {
        Box(modifier = GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            TileRow(listOf(section), state, gymRing, scale, expand = true)
        }
    } else {
        TileRow(listOf(section), state, gymRing, scale)
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
) {
    val moneySeverity = state.reasons.firstOrNull { it.domain == "money" }?.severity
    val hasGym = WidgetSection.GYM_RING in sections &&
        (gymRing != null || (state.gymLast7d != null && state.gymTarget != null))
    val hasMoney = WidgetSection.MONEY_TILE in sections && state.discretionaryDollars != null
    val hasCoursework = WidgetSection.COURSEWORK_TILE in sections && state.courseworkHoursNext7d != null
    val hasSleep = WidgetSection.SLEEP_TILE in sections && state.sleepMinutes != null
    if (!hasGym && !hasMoney && !hasCoursework && !hasSleep) return
    val tileWidth = if (expand) GlanceModifier.fillMaxWidth() else GlanceModifier.width((STAT_TILE_WIDTH_DP * scale).dp)
    val tileAlignment = if (expand) Alignment.CenterHorizontally else Alignment.Start

    Row(
        modifier = GlanceModifier.fillMaxWidth().padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        var addedFirst = false
        for (section in sections) {
            val rendered = when (section) {
                WidgetSection.GYM_RING -> when {
                    gymRing != null -> {
                        if (addedFirst) Spacer(modifier = GlanceModifier.width(6.dp))
                        GymRingIndicator(gymRing, scale)
                        true
                    }
                    state.gymLast7d != null && state.gymTarget != null -> {
                        if (addedFirst) Spacer(modifier = GlanceModifier.width(6.dp))
                        GymBar(state.gymLast7d, state.gymTarget, scale)
                        true
                    }
                    else -> false
                }
                WidgetSection.MONEY_TILE -> if (state.discretionaryDollars != null) {
                    if (addedFirst) Spacer(modifier = GlanceModifier.width(6.dp))
                    MoneyTile(state.discretionaryDollars, moneySeverity, scale,
                        modifier = tileWidth, horizontalAlignment = tileAlignment)
                    true
                } else false
                WidgetSection.COURSEWORK_TILE -> if (state.courseworkHoursNext7d != null) {
                    if (addedFirst) Spacer(modifier = GlanceModifier.width(6.dp))
                    StatTile("📚", "${state.courseworkHoursNext7d}h", scale,
                        modifier = tileWidth, horizontalAlignment = tileAlignment)
                    true
                } else false
                WidgetSection.SLEEP_TILE -> if (state.sleepMinutes != null) {
                    if (addedFirst) Spacer(modifier = GlanceModifier.width(6.dp))
                    StatTile("😴", formatSleepDuration(state.sleepMinutes), scale,
                        modifier = tileWidth, horizontalAlignment = tileAlignment)
                    true
                } else false
                else -> false
            }
            if (rendered) addedFirst = true
        }
    }
}

/** "6h42m" / "6h" -- matches the compact, no-decimal style every other
 * stat tile uses (e.g. courseworkHoursNext7d's "6.5h"), sized to fit the
 * same ~100dp stat tile width. */
internal fun formatSleepDuration(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return if (m == 0) "${h}h" else "${h}h${m}m"
}

/** "Xd since partner" / "Yd since friends" side by side -- two independent
 * figures (social_input tracks them separately, same as PARTNER_TASK vs.
 * FRIENDS_TASK elsewhere in the app), so unlike the other stats this isn't
 * a single StatTile-shaped emoji+number; it's a small two-up row. Renders
 * standalone (not part of [TILE_SECTIONS]) for the same reason WeatherSection
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
 * value appends "→Nd" for the soonest scheduled/proposed date -- "2d" alone
 * doesn't say whether that's 2 days of silence with nothing on the horizon,
 * or 2 days since with something already booked for Friday; the arrow makes
 * "nothing planned yet" visually distinct (bare "2d") from "already handled"
 * (confirmed 2026-07-13, user couldn't tell which case they were looking at).
 *
 * [stacked] renders the two chips as a vertical Column instead of a side-by-
 * side Row -- only used for the standalone single-stat "LifeOps Social"
 * preset (see the SOCIAL branch in BriefingContent), letting that widget's
 * default footprint go back to the same narrow 2x1 every other single-stat
 * preset uses instead of needing extra width for two chips side by side.
 * Uses tighter font sizes/padding than the default StatTile -- two full-size
 * stacked chips need ~100dp of height (confirmed 2026-07-13), too tall for
 * a true 2x1; shrunk down they fit within gym/money's same 56dp floor. */
@Composable
private fun SocialSection(state: BriefingState, scale: Float, stacked: Boolean = false) {
    fun label(daysSince: Int?, daysUntil: Int?): String? =
        daysSince?.let { s -> daysUntil?.let { u -> "${s}d→${u}d" } ?: "${s}d" }
    val partner = label(state.partnerDaysSince, state.partnerDaysUntil)
    val friends = label(state.friendDaysSince, state.friendDaysUntil)
    if (stacked) {
        // fillMaxSize() on the Column + defaultWeight() on each chip, not
        // just fillMaxWidth() -- each chip otherwise only wrapped its own
        // compact content height instead of splitting the widget's actual
        // available height between them, same gap CompactWeatherTile had
        // (confirmed 2026-07-13: "keep the blue shit as filled out to 2x1
        // as you can" applies here too -- these chips' gray backgrounds
        // were leaving dead space the same way).
        Column(modifier = GlanceModifier.fillMaxSize().padding(top = 2.dp)) {
            partner?.let {
                StatTile("💜", it, scale, modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
                    emojiSp = 11f, valueSp = 12f, tilePadding = 3.dp)
            }
            if (partner != null && friends != null) {
                Spacer(modifier = GlanceModifier.height(2.dp))
            }
            friends?.let {
                StatTile("👥", it, scale, modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
                    emojiSp = 11f, valueSp = 12f, tilePadding = 3.dp)
            }
        }
    } else {
        Row(modifier = GlanceModifier.fillMaxWidth().padding(top = 4.dp)) {
            partner?.let { StatTile("💜", it, scale, modifier = GlanceModifier.defaultWeight()) }
            if (partner != null && friends != null) {
                Spacer(modifier = GlanceModifier.width(6.dp))
            }
            friends?.let { StatTile("👥", it, scale, modifier = GlanceModifier.defaultWeight()) }
        }
    }
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
private const val COMPACT_WEATHER_ICON_SP = 20f
private const val COMPACT_WEATHER_CONDITION_SP = 8f

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
 * only content). */
@Composable
private fun WeatherSection(state: BriefingState, scale: Float, modifier: GlanceModifier = GlanceModifier) {
    val temp = state.temperatureF ?: return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .cornerRadius(12.dp)
            .background(ColorProvider(WEATHER_BG))
            .padding(12.dp),
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
        Column {
            // Glance's TextStyle has no baselineShift/superscript span --
            // there's no way to get a real raised-and-shrunk unit through
            // this API; Alignment.Top (aligning line-box tops, not
            // baselines) is the closest available approximation of a
            // top-right superscript (confirmed 2026-07-13, user's call
            // after trying Bottom).
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    text = "$temp",
                    style = TextStyle(fontWeight = FontWeight.Bold, fontSize = (BASE_WEATHER_TEMP_SP * scale).sp,
                        color = ColorProvider(Color.White)),
                )
                Text(
                    text = "°F",
                    style = TextStyle(fontSize = (BASE_WEATHER_UNIT_SP * scale).sp, color = ColorProvider(Color.White)),
                )
            }
            // Both on the same font size -- BASE_WEATHER_HILO_SP was a
            // leftover from the old layout where high shared a line with
            // "°F" (so it used the unit's size) and low sat alone below at
            // its own smaller size; now that both are side by side on one
            // row, there's no reason for them to differ (confirmed
            // 2026-07-13, live device: high/low visibly mismatched size).
            Row {
                state.weatherHighF?.let {
                    Text(text = "↑$it°", style = TextStyle(fontSize = (BASE_WEATHER_HILO_SP * scale).sp,
                        color = ColorProvider(Color.White)))
                }
                if (state.weatherHighF != null && state.weatherLowF != null) {
                    Spacer(modifier = GlanceModifier.width(6.dp))
                }
                state.weatherLowF?.let {
                    Text(text = "↓$it°", style = TextStyle(fontSize = (BASE_WEATHER_HILO_SP * scale).sp,
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
        Spacer(modifier = GlanceModifier.width(8.dp))
        Spacer(modifier = GlanceModifier.defaultWeight())
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = weatherEmoji(state.weatherCondition),
                style = TextStyle(fontSize = (BASE_WEATHER_ICON_SP * scale).sp))
            state.weatherCondition?.let {
                Text(text = it, maxLines = 1, style = TextStyle(fontSize = (BASE_WEATHER_CONDITION_SP * scale).sp,
                    color = ColorProvider(Color.White), textAlign = TextAlign.Center))
            }
        }
    }
}

/** A skinnier version of [WeatherSection]'s own blue card, same content --
 * temp+unit, hi/lo, icon, AND the condition text label -- just sized down
 * (COMPACT_WEATHER_* constants, not the full card's) to actually fit a
 * narrower 2x1 footprint instead of overflowing it: reusing the full
 * card's larger sizes clipped the hi/lo text (confirmed 2026-07-13, live
 * device). maxLines=1 + textAlign=Center on the condition text, same fix
 * as WeatherSection's own -- a wrapped second line reads as off-center
 * under the icon. NOT the neutral gray StatTile every other compact tile
 * uses -- two earlier versions routed WEATHER through StatTile/TileRow,
 * then dropped hi/lo, then dropped the condition label entirely; all threw
 * away real content the user wanted (confirmed 2026-07-13: "it should look
 * like the 3x1 but just skinnier" / "i want the hi lo" / "why not just make
 * the mostly sunny text slightly smaller"). fillMaxWidth() always --
 * WEATHER never shares a row with sibling tiles (it isn't a TILE_SECTIONS
 * member), so it's either the sole content of a single-stat preset or its
 * own standalone row on the combo widget, same as the full card. */
@Composable
private fun CompactWeatherTile(state: BriefingState, scale: Float, modifier: GlanceModifier = GlanceModifier) {
    val temp = state.temperatureF ?: return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .cornerRadius(12.dp)
            .background(ColorProvider(WEATHER_BG))
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    text = "$temp",
                    style = TextStyle(fontWeight = FontWeight.Bold, fontSize = (COMPACT_WEATHER_TEMP_SP * scale).sp,
                        color = ColorProvider(Color.White)),
                )
                Text(
                    text = "°F",
                    style = TextStyle(fontSize = (COMPACT_WEATHER_UNIT_SP * scale).sp, color = ColorProvider(Color.White)),
                )
            }
            Row {
                state.weatherHighF?.let {
                    Text(text = "↑$it°", style = TextStyle(fontSize = (COMPACT_WEATHER_HILO_SP * scale).sp,
                        color = ColorProvider(Color.White)))
                }
                if (state.weatherHighF != null && state.weatherLowF != null) {
                    Spacer(modifier = GlanceModifier.width(4.dp))
                }
                state.weatherLowF?.let {
                    Text(text = "↓$it°", style = TextStyle(fontSize = (COMPACT_WEATHER_HILO_SP * scale).sp,
                        color = ColorProvider(Color.White)))
                }
            }
        }
        Spacer(modifier = GlanceModifier.width(6.dp))
        Spacer(modifier = GlanceModifier.defaultWeight())
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = weatherEmoji(state.weatherCondition),
                style = TextStyle(fontSize = (COMPACT_WEATHER_ICON_SP * scale).sp))
            state.weatherCondition?.let {
                Text(text = it, maxLines = 1, style = TextStyle(fontSize = (COMPACT_WEATHER_CONDITION_SP * scale).sp,
                    color = ColorProvider(Color.White), textAlign = TextAlign.Center))
            }
        }
    }
}

/** Today's real calendar events -- shown above "Up next" and independent of
 * the LLM-generated briefing text, since a $0 family event or anything not
 * framed as "at risk" would otherwise never surface (confirmed 2026-07-12:
 * a same-day BBQ went unmentioned because it wasn't a risk/deadline). This
 * is the deterministic "don't forget you have an obligation" line, not
 * advisory. */
@Composable
private fun EventsSection(events: List<TodayEvent>) {
    Column(modifier = GlanceModifier.padding(top = 8.dp)) {
        for (event in events) {
            Text(
                text = formatEventLine(event),
                style = TextStyle(
                    fontWeight = FontWeight.Bold,
                    color = GlanceTheme.colors.onSurface,
                ),
            )
        }
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
private val GYM_RING_RED = Color(0xFFB3261E)

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

/** Gym ring: fill = trailing-7-day adherence ratio (grows only as real
 * sessions accumulate), color = same-day action signal, intentionally
 * decoupled -- see GymRing's docstring. Rendered as a bitmap ring (see
 * renderGymRingBitmap) with the gym emoji layered on top via a centered Box. */
@Composable
private fun GymRingIndicator(gymRing: GymRing, scale: Float) {
    val context = LocalContext.current
    val density = context.resources.displayMetrics.density
    val ringSizeDp = (BASE_GYM_RING_SIZE_DP * scale)
    val sizePx = (ringSizeDp * density).roundToInt()
    val strokePx = BASE_GYM_RING_STROKE_DP * scale * density
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
        Text(text = "🏋", style = TextStyle(fontSize = (BASE_GYM_RING_EMOJI_SP * scale).sp))
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
