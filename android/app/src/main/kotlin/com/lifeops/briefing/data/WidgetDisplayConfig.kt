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
    BRIEFING_PARAGRAPH, TODAY_EVENTS, UP_NEXT,
}

/** Per-widget-instance display customization -- which sections show, in what
 * order, at what font/icon scale, and how many "Up next" tasks to show.
 * Configured via WidgetConfigActivity's native Android widget-configure
 * screen (one instance per placed widget, not a global setting), persisted
 * under WidgetKeys.DISPLAY_CONFIG_JSON in the same per-GlanceId Glance
 * Preferences DataStore as BriefingState/NextTasksState -- so it's cleaned
 * up automatically when a widget instance is removed, same as those. */
data class WidgetDisplayConfig(
    val sectionOrder: List<WidgetSection> = WidgetSection.entries.toList(),
    val hiddenSections: Set<WidgetSection> = emptySet(),
    val scale: Float = 1.0f,
    val maxTasksOverride: Int? = null,
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
    }.toString()

    companion object {
        fun default() = WidgetDisplayConfig()

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
                scale = o.optDouble("scale", 1.0).toFloat(),
                maxTasksOverride = if (o.isNull("maxTasksOverride") || !o.has("maxTasksOverride")) {
                    null
                } else {
                    o.optInt("maxTasksOverride", -1).takeIf { it > 0 }
                },
            )
        }
    }
}
