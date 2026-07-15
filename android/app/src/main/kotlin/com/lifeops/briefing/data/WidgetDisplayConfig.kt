package com.lifeops.briefing.data

import org.json.JSONArray
import org.json.JSONObject

/** Every independently show/hide-able, reorderable piece of the widget.
 * The attention badge itself isn't here -- it's pinned first, always shown,
 * never reordered (see BriefingContent's docstring: "the one thing that must
 * never get squeezed out"). SEVERITY_DOTS is the one section with a special
 * rendering rule: it renders inline on the badge's own row ONLY when it's
 * left in its default (first) position in [WidgetDisplayConfig.sectionOrder];
 * moved anywhere else, it renders as its own standalone row at that new
 * position. */
enum class WidgetSection {
    SEVERITY_DOTS, GYM_RING, MONEY_TILE, COURSEWORK_TILE,
    BRIEFING_PARAGRAPH, TODAY_EVENTS, UP_NEXT, WEATHER, SLEEP_TILE, SOCIAL,
    // Upcoming (rolling next-7-days, NOT a fixed calendar week), deterministic,
    // infrequent/one-off calendar events (see server-side notable_events.py) --
    // distinct from TODAY_EVENTS, which is every timed event happening TODAY
    // regardless of how often it recurs.
    NOTABLE_EVENTS,
}

/** Per-widget-instance display customization -- which sections show, in what
 * order, at what font/icon scale, and how many "Up next" tasks to show.
 * Configured via WidgetConfigActivity's native Android widget-configure
 * screen (one instance per placed widget, not a global setting), persisted
 * under WidgetKeys.DISPLAY_CONFIG_JSON in the same per-GlanceId Glance
 * Preferences DataStore as BriefingState/NextTasksState -- so it's cleaned
 * up automatically when a widget instance is removed, same as those. */
data class WidgetDisplayConfig(
    // Matches the widget's pre-customization hardcoded layout exactly
    // (paragraph before the tiles, dots pinned first) -- NOT raw
    // WidgetSection.entries order, which would silently reshuffle every
    // never-configured widget instance's default layout (confirmed via
    // code review: entries declaration order puts the tiles before the
    // paragraph, reversing the original default).
    val sectionOrder: List<WidgetSection> = listOf(
        WidgetSection.SEVERITY_DOTS,
        WidgetSection.BRIEFING_PARAGRAPH,
        WidgetSection.GYM_RING,
        WidgetSection.MONEY_TILE,
        WidgetSection.COURSEWORK_TILE,
        WidgetSection.SLEEP_TILE,
        WidgetSection.WEATHER,
        WidgetSection.SOCIAL,
        WidgetSection.TODAY_EVENTS,
        WidgetSection.NOTABLE_EVENTS,
        WidgetSection.UP_NEXT,
    ),
    val hiddenSections: Set<WidgetSection> = emptySet(),
    val scale: Float = 1.0f,
    val maxTasksOverride: Int? = null,
    // True only for the "LifeOps Combo" preset (see [comboGrid]) -- tells
    // BriefingContent to skip the normal section-order/TileRow rendering
    // entirely and render the dedicated gapless 2x2 ComboGridContent
    // instead. hiddenSections/sectionOrder are still populated by
    // [comboGrid] for consistency with WidgetConfigActivity's toggles, but
    // ComboGridContent ignores them -- this flag is checked first.
    val comboGrid: Boolean = false,
) {
    fun toJson(): String = JSONObject().apply {
        put("sectionOrder", JSONArray().apply {
            sectionOrder.forEach { put(it.name) }
        })
        put("hiddenSections", JSONArray().apply {
            hiddenSections.forEach { put(it.name) }
        })
        put("scale", scale.toDouble())
        put("maxTasksOverride", maxTasksOverride)
        put("comboGrid", comboGrid)
    }.toString()

    companion object {
        // Matches WidgetConfigActivity's Slider range -- a corrupted or
        // hand-edited persisted value must not flow unclamped into every
        // font/icon size calculation in BriefingWidget.kt.
        const val MIN_SCALE = 0.85f
        const val MAX_SCALE = 1.3f

        fun default() = WidgetDisplayConfig()

        /** Starting config for a single-stat preset widget (its own pickable
         * entry in the OS widget tray, e.g. "LifeOps Gym") -- everything
         * except the one named section is hidden. sectionOrder is left at
         * its default since order among hidden sections is irrelevant; the
         * user can still reveal others later via the same configure screen
         * the full widget uses. */
        fun singleStat(section: WidgetSection): WidgetDisplayConfig = WidgetDisplayConfig(
            hiddenSections = WidgetSection.entries.filter { it != section }.toSet(),
        )

        /** Starting config for the "LifeOps Combo" preset: a single 4x2
         * widget instance whose left 2x2 half merges money/social/coursework
         * (equispaced, gapless top row) and weather (gapless bottom row),
         * and whose right 2x2 half is notable events, all as one continuous
         * surface instead of five separate single-stat widgets placed side
         * by side (which always have the launcher's own grid gaps between
         * them). hiddenSections mirrors what's actually shown so the
         * configure screen's toggles don't look inconsistent with this
         * preset, even though rendering itself branches on [comboGrid]
         * before ever consulting sectionOrder/hiddenSections. */
        private val COMBO_GRID_SECTIONS = setOf(
            WidgetSection.MONEY_TILE, WidgetSection.SOCIAL, WidgetSection.COURSEWORK_TILE,
            WidgetSection.WEATHER, WidgetSection.NOTABLE_EVENTS,
        )

        fun comboGrid(): WidgetDisplayConfig = WidgetDisplayConfig(
            hiddenSections = WidgetSection.entries.filter { it !in COMBO_GRID_SECTIONS }.toSet(),
            comboGrid = true,
        )

        /** Unknown/removed enum values (e.g. an older or newer app version's
         * section that this build doesn't recognize) are silently dropped
         * rather than throwing -- forward/backward compatibility across app
         * updates matters more here than catching a typo, since a corrupt
         * section name should degrade to "just don't show that one," not
         * blank the whole widget. */
        private fun parseSections(arr: JSONArray?): List<WidgetSection> {
            if (arr == null) return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                try {
                    WidgetSection.valueOf(arr.getString(i))
                } catch (e: IllegalArgumentException) {
                    null
                }
            }
        }

        fun fromJson(raw: String): WidgetDisplayConfig {
            val o = JSONObject(raw)
            val order = parseSections(o.optJSONArray("sectionOrder"))
            return WidgetDisplayConfig(
                // A section missing from a persisted-but-stale order (e.g.
                // this app version added a new section after the config was
                // saved) still gets appended at the end rather than
                // vanishing silently.
                sectionOrder = order + WidgetSection.entries.filter { it !in order },
                hiddenSections = parseSections(o.optJSONArray("hiddenSections")).toSet(),
                scale = o.optDouble("scale", 1.0).toFloat().coerceIn(MIN_SCALE, MAX_SCALE),
                maxTasksOverride = if (o.isNull("maxTasksOverride") || !o.has("maxTasksOverride")) {
                    null
                } else {
                    o.optInt("maxTasksOverride", -1).takeIf { it > 0 }
                },
                comboGrid = o.optBoolean("comboGrid", false),
            )
        }
    }
}
