package com.lifeops.briefing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.runtime.Composable
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
import androidx.glance.appwidget.CheckBox
import androidx.glance.appwidget.GlanceAppWidget
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
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.lifeops.briefing.data.AttentionReason
import com.lifeops.briefing.data.BriefingState
import com.lifeops.briefing.data.GymRing
import com.lifeops.briefing.data.NextTask
import com.lifeops.briefing.data.NextTasksState
import com.lifeops.briefing.data.TodayEvent
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
 * [WidgetKeys.BRIEFING_JSON]) and [NextTasksState] (under
 * [WidgetKeys.NEXT_TASKS_JSON]) and renders: markdown-ish bold/plain briefing
 * text, a small stat row, a "received" timestamp, and up to a few upcoming
 * tasks with real checkboxes. Tapping the body (outside a checkbox) opens the
 * control panel.
 *
 * Briefing state is written by [BriefingReceiver] whenever ntfy delivers a
 * "briefing-data" push (push-only, no polling). Next-tasks state is written
 * by [NextTasksRefreshWorker]'s periodic pull and by [CompleteTaskAction]'s
 * immediate update after a checkbox tap.
 */
class BriefingWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Responsive(setOf(SMALL_SIZE, MEDIUM_SIZE, LARGE_SIZE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
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

            GlanceTheme {
                BriefingContent(briefing, nextTasks)
            }
        }
    }
}

/** Renders progressively more content as the placed widget size grows --
 * SMALL shows only the status badge + one-line headline (the minimum "am I
 * OK, and what's my next move" signal), MEDIUM adds the gym meter/stats/
 * freshness line, LARGE (the 4x3 target size) adds the full briefing
 * paragraph, today's events, and the up-next task list. Every bucket still
 * gets the attention badge -- that's the one thing that must never get
 * squeezed out, no matter how small the widget is resized. */
@Composable
internal fun BriefingContent(state: BriefingState, nextTasks: NextTasksState) {
    val bucket = bucketFor(LocalSize.current)
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .padding(12.dp)
            .clickable(actionRunCallback<OpenPanelAction>()),
    ) {
        AttentionHeader(state, compact = bucket == WidgetSizeBucket.SMALL)

        if (bucket == WidgetSizeBucket.SMALL) {
            return@Column
        }

        if (state.text == null) {
            Text(
                text = "No briefing yet — tap to configure",
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant),
            )
        } else {
            if (bucket == WidgetSizeBucket.LARGE) {
                BriefingParagraph(state.text)
            }

            // Gym ring, money, and coursework all share one row now -- one
            // instrument-panel strip instead of a ring on its own line
            // followed by a separate stat-tile row underneath.
            StatsRow(state, nextTasks.gymRing)
            StaleIndicator(state.fetchedAtEpochMillis)
        }

        if (bucket != WidgetSizeBucket.LARGE) {
            return@Column
        }

        // Today's real calendar events -- shown above "Up next" and
        // independent of the LLM-generated briefing text, since a $0 family
        // event or anything not framed as "at risk" would otherwise never
        // surface (confirmed 2026-07-12: a same-day BBQ went unmentioned
        // because it wasn't a risk/deadline). This is the deterministic
        // "don't forget you have an obligation" line, not advisory.
        if (nextTasks.events.isNotEmpty()) {
            Column(modifier = GlanceModifier.padding(top = 8.dp)) {
                for (event in nextTasks.events) {
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

        if (nextTasks.tasks.isNotEmpty()) {
            Column(modifier = GlanceModifier.padding(top = 8.dp)) {
                Text(
                    text = "Up next",
                    style = TextStyle(
                        fontWeight = FontWeight.Bold,
                        color = GlanceTheme.colors.onSurface,
                    ),
                )
                for (task in nextTasks.tasks) {
                    NextTaskRow(task)
                }
            }
        }
    }
}

/** Shared with GymBar's on/off-target color and StaleIndicator's warning
 * color -- all three signal "this is fine" vs. "this needs attention" with
 * the same two colors, so they're defined once here rather than as three
 * independently-typed hex literals that could silently drift apart. */
private val COLOR_OK = Color(0xFF276B5E)
private val COLOR_WARN = Color(0xFFA8641F)

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
// BriefingContent's outer Column under that limit as more sections
// (severity dots, stat tiles, gym ring) get added over time.
@Composable
private fun AttentionHeader(state: BriefingState, compact: Boolean) {
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
                style = TextStyle(fontWeight = FontWeight.Bold, color = ColorProvider(statusColor)),
            )
            if (state.reasons.isNotEmpty()) {
                Spacer(modifier = GlanceModifier.width(8.dp))
                SeverityDots(state.reasons)
            }
        }
        if (!compact) {
            Spacer(modifier = GlanceModifier.height(6.dp))
        }
    }
}

/** Per-domain glyph row -- one dot per tracked domain (coursework, system,
 * money, gym, matching attention.py's _DOMAIN_PRIORITY order), colored by
 * the worst severity attention.compute() found for that domain, green when
 * a domain has no open reason at all. Lets a glance answer "which of my
 * four areas needs something" without reading any text -- the instrument-
 * panel glyph language the rest of the widget is moving toward, rather
 * than one linear headline trying to speak for every domain at once. */
private val DOT_DOMAIN_ORDER = listOf("coursework", "system", "money", "gym")
private const val DOT_SIZE_DP = 7
private val SEVERITY_RANK = mapOf("ok" to 0, "watch" to 1, "risk" to 2, "fucked" to 3)

private fun severityDotColor(severity: String): Color = when (severity) {
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
// bitmap to the GlanceModifier.size(DOT_SIZE_DP.dp) box regardless of the
// source's raw pixel dimensions, so (unlike the ring, whose stroke width
// must scale with its dp size) a plain filled circle needs no density
// lookup at all.
private const val DOT_BITMAP_PX = 32

@Composable
private fun SeverityDots(reasons: List<AttentionReason>) {
    val worstByDomain = mutableMapOf<String, String>()
    for (r in reasons) {
        val rank = SEVERITY_RANK[r.severity] ?: continue
        val currentRank = SEVERITY_RANK[worstByDomain[r.domain]] ?: -1
        if (rank > currentRank) {
            worstByDomain[r.domain] = r.severity
        }
    }
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
                modifier = GlanceModifier.size(DOT_SIZE_DP.dp),
            )
        }
    }
}

/** Full LLM-generated briefing text -- each source line becomes a Row of
 * bold/non-bold Text runs, stacked in a Column to preserve line breaks. Only
 * shown at the LARGE bucket: it's the least essential content relative to
 * the deterministic status/stats above it, and the first thing that should
 * drop away as the widget shrinks. */
@Composable
private fun BriefingParagraph(text: String) {
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
                                fontSize = 15.sp,
                                color = GlanceTheme.colors.onSurface,
                            ),
                        )
                    }
                }
            }
        }
    }
}

/** Icon+monospace-number card -- glyph replaces the label, number is the
 * one thing that needs to read at a glance, matching the gym ring's
 * icon-first visual language instead of a plain "$340" text run. Rounded
 * corners match MoneyTile's shape so the two read as one family of tile. */
@Composable
private fun StatTile(emoji: String, value: String, modifier: GlanceModifier = GlanceModifier) {
    Column(
        modifier = modifier
            .cornerRadius(10.dp)
            .background(GlanceTheme.colors.surfaceVariant)
            .padding(6.dp),
    ) {
        Text(text = emoji, style = TextStyle(fontSize = 16.sp))
        Text(
            text = value,
            style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 18.sp,
                color = GlanceTheme.colors.onSurface),
        )
    }
}

private val MONEY_TILE_OK_BG = Color(0x4D276B5E)
private val MONEY_TILE_WATCH_BG = Color(0x4DA8641F)
private val MONEY_TILE_RISK_BG = Color(0x4DB3261E)

/** No glyph, no label -- just the dollar figure, big and bold, since it's
 * the one number where the amount itself IS the message. Background color
 * carries the same money severity attention.compute() already decided --
 * green when fine, transparent yellow when the buffer's thin, red when
 * discretionary has gone negative -- looked up from state.reasons rather
 * than re-deriving the <0/<100 thresholds here, so the widget can never
 * disagree with the engine that owns severity. */
@Composable
private fun MoneyTile(dollars: Int, severity: String?, modifier: GlanceModifier = GlanceModifier) {
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
    ) {
        Text(
            text = "$${dollars}",
            style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 22.sp,
                color = GlanceTheme.colors.onSurface),
        )
    }
}

/** Gym ring, money, and coursework hours all in one row -- one instrument-
 * panel strip instead of a ring on its own line above a separate stat-tile
 * row underneath. */
@Composable
private fun StatsRow(state: BriefingState, gymRing: GymRing?) {
    val hasGym = gymRing != null || (state.gymLast7d != null && state.gymTarget != null)
    if (!hasGym && state.discretionaryDollars == null && state.courseworkHoursNext7d == null) return
    val moneySeverity = state.reasons.firstOrNull { it.domain == "money" }?.severity
    Row(modifier = GlanceModifier.fillMaxWidth().padding(top = 4.dp)) {
        var addedFirst = false
        if (gymRing != null) {
            GymRingIndicator(gymRing)
            addedFirst = true
        } else if (state.gymLast7d != null && state.gymTarget != null) {
            GymBar(state.gymLast7d, state.gymTarget)
            addedFirst = true
        }
        if (state.discretionaryDollars != null) {
            if (addedFirst) {
                Spacer(modifier = GlanceModifier.width(6.dp))
            }
            MoneyTile(state.discretionaryDollars, moneySeverity, modifier = GlanceModifier.width(STAT_TILE_WIDTH_DP.dp))
            addedFirst = true
        }
        if (state.courseworkHoursNext7d != null) {
            if (addedFirst) {
                Spacer(modifier = GlanceModifier.width(6.dp))
            }
            StatTile("📚", "${state.courseworkHoursNext7d}h", modifier = GlanceModifier.width(STAT_TILE_WIDTH_DP.dp))
        }
    }
}

/** "as of" info line -- when this snapshot was received, if known. A stale
 * snapshot is worse than a missing one (it looks current but isn't), so
 * anything past STALE_THRESHOLD_MINUTES gets a warning glyph ahead of the
 * age, not just the age itself. */
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
private const val GYM_BAR_WIDTH_DP = 60
private const val STAT_TILE_WIDTH_DP = 100

/** Compact proportional meter: filled portion in teal once at/above target,
 * amber while short of it -- same two colors the stat text already used, now
 * a shape instead of just a fraction. Glance's RowScope only exposes an
 * equal (1x) weight modifier, not an arbitrary numeric one, so the fill
 * ratio is expressed as two explicit dp widths instead of weights. */
@Composable
private fun GymBar(completed: Int, target: Int) {
    val ratio = if (target > 0) (completed.toFloat() / target.toFloat()).coerceIn(0f, 1f) else 0f
    val filledDp = (GYM_BAR_WIDTH_DP * ratio).toInt()
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
            if (filledDp < GYM_BAR_WIDTH_DP) {
                Box(modifier = GlanceModifier.width((GYM_BAR_WIDTH_DP - filledDp).dp).height(6.dp)
                    .background(GlanceTheme.colors.surfaceVariant)) {}
            }
        }
    }
}

private const val GYM_RING_SIZE_DP = 44
private const val GYM_RING_STROKE_DP = 4
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
private fun GymRingIndicator(gymRing: GymRing) {
    val context = LocalContext.current
    val density = context.resources.displayMetrics.density
    val sizePx = (GYM_RING_SIZE_DP * density).roundToInt()
    val strokePx = GYM_RING_STROKE_DP * density
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
        modifier = GlanceModifier.size(GYM_RING_SIZE_DP.dp),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            provider = ImageProvider(bitmap),
            contentDescription = "Gym ${gymRing.gymLast7d}/${gymRing.gymTarget}, ${gymRing.color}",
            modifier = GlanceModifier.fillMaxSize(),
        )
        Text(text = "🏋", style = TextStyle(fontSize = 20.sp))
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
