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

enum class MoneyDisplayMode {
    YNAB_CURRENT,
    TODAY,
    PROJECTED,
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
    val moneyAppPackage: String = DEFAULT_MONEY_APP_PACKAGE,
    val gymAppPackage: String = DEFAULT_GYM_APP_PACKAGE,
    val weatherAppPackage: String = "",
    val moneyDisplayMode: MoneyDisplayMode = MoneyDisplayMode.YNAB_CURRENT,
    val ynabCategoryName: String = DEFAULT_YNAB_CATEGORY_NAME,
    // True only for the "LifeOps Combo" preset (see [comboGrid]) -- tells
    // BriefingContent to render the dedicated size-aware gapless combo
    // surface instead of the normal section-by-section briefing layout.
    // sectionOrder/hiddenSections still matter: ComboGridContent uses them
    // to decide which compact-compatible cells to show first as the placed
    // size grows from 2x2 to 3x2, 4x2, and taller variants.
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
        put("moneyAppPackage", moneyAppPackage)
        put("gymAppPackage", gymAppPackage)
        put("weatherAppPackage", weatherAppPackage)
        put("moneyDisplayMode", moneyDisplayMode.name)
        put("ynabCategoryName", ynabCategoryName)
        put("comboGrid", comboGrid)
    }.toString()

    companion object {
        // Matches WidgetConfigActivity's Slider range -- a corrupted or
        // hand-edited persisted value must not flow unclamped into every
        // font/icon size calculation in BriefingWidget.kt.
        const val MIN_SCALE = 0.85f
        const val MAX_SCALE = 1.3f
        const val DEFAULT_MONEY_APP_PACKAGE = "com.youneedabudget.evergreen.app"
        const val DEFAULT_GYM_APP_PACKAGE = "io.a24go.android.dev"
        const val DEFAULT_YNAB_CATEGORY_NAME = "Fun"

        // Sentinel stored in moneyAppPackage/gymAppPackage/weatherAppPackage
        // to mean "open the in-app panel instead of an app" -- these fields
        // stay typed String (not a sealed TapTarget) to avoid a JSON schema
        // migration, but any code choosing between the two meanings MUST go
        // through [isPanelTarget] rather than comparing against this
        // constant directly, so a real package name is never mistaken for
        // the sentinel or vice versa.
        const val TAP_TARGET_PANEL = "__lifeops_panel__"

        /** The only correct way to test whether a moneyAppPackage/
         * gymAppPackage/weatherAppPackage value means "open the panel" --
         * code that treats these fields as literal package names (passing
         * to PackageManager, logging, listing in a UI) must check this
         * first and branch away, not fall through with the sentinel. */
        fun isPanelTarget(packageName: String): Boolean = packageName == TAP_TARGET_PANEL

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

        /** Sections the "LifeOps Combo" renderer knows how to show as
         * compact cells. Default placement starts with the subset below,
         * but the configure screen may reveal any supported section and
         * reorder them; the renderer takes as many cells as the current
         * placed size can fit. */
        val COMBO_GRID_SUPPORTED_SECTIONS = setOf(
            WidgetSection.GYM_RING,
            WidgetSection.MONEY_TILE, WidgetSection.SOCIAL, WidgetSection.COURSEWORK_TILE,
            WidgetSection.SLEEP_TILE, WidgetSection.WEATHER, WidgetSection.NOTABLE_EVENTS,
        )

        /** Starting config for the "LifeOps Combo" preset. Order is the
         * salience priority the renderer consumes when space is constrained:
         * weather, gym, notable events, discretionary spending, social.
         * Coursework remains supported/customizable, but is not a default
         * priority cell. */
        private val COMBO_GRID_DEFAULT_SECTIONS = listOf(
            WidgetSection.WEATHER,
            WidgetSection.GYM_RING,
            WidgetSection.NOTABLE_EVENTS,
            WidgetSection.MONEY_TILE,
            WidgetSection.SOCIAL,
        )

        fun comboGrid(): WidgetDisplayConfig = WidgetDisplayConfig(
            sectionOrder = COMBO_GRID_DEFAULT_SECTIONS +
                WidgetSection.entries.filter { it !in COMBO_GRID_DEFAULT_SECTIONS },
            hiddenSections = WidgetSection.entries.filter { it !in COMBO_GRID_DEFAULT_SECTIONS }.toSet(),
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
                moneyAppPackage = o.optString("moneyAppPackage", DEFAULT_MONEY_APP_PACKAGE),
                gymAppPackage = o.optString("gymAppPackage", DEFAULT_GYM_APP_PACKAGE),
                weatherAppPackage = o.optString("weatherAppPackage", ""),
                moneyDisplayMode = runCatching {
                    MoneyDisplayMode.valueOf(o.optString("moneyDisplayMode", MoneyDisplayMode.YNAB_CURRENT.name))
                }.getOrDefault(MoneyDisplayMode.YNAB_CURRENT),
                ynabCategoryName = o.optString("ynabCategoryName", DEFAULT_YNAB_CATEGORY_NAME)
                    .takeIf { it.isNotBlank() } ?: DEFAULT_YNAB_CATEGORY_NAME,
                comboGrid = o.optBoolean("comboGrid", false),
            )
        }
    }
}
