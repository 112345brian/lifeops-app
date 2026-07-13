package com.lifeops.briefing.data

import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** org.json.JSONObject is the real Android stub jar on the unit-test
 * classpath (throws "not mocked" on every call) unless run under
 * Robolectric, same as BriefingStateTest/NextTasksStateTest. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WidgetDisplayConfigTest {

    @Test
    fun defaultRoundTripsThroughJson() {
        val config = WidgetDisplayConfig.default()

        // Must match the widget's pre-customization hardcoded layout exactly
        // (paragraph before the tiles, dots pinned first) -- NOT raw
        // WidgetSection.entries declaration order, which would silently
        // reshuffle every never-configured widget's default layout.
        assertEquals(
            listOf(
                WidgetSection.SEVERITY_DOTS, WidgetSection.BRIEFING_PARAGRAPH,
                WidgetSection.GYM_RING, WidgetSection.MONEY_TILE, WidgetSection.COURSEWORK_TILE,
                WidgetSection.SLEEP_TILE, WidgetSection.WEATHER, WidgetSection.SOCIAL,
                WidgetSection.TODAY_EVENTS, WidgetSection.UP_NEXT,
            ),
            config.sectionOrder,
        )
        assertEquals(WidgetSection.entries.toSet(), config.sectionOrder.toSet())
        assertEquals(emptySet<WidgetSection>(), config.hiddenSections)
        assertEquals(1.0f, config.scale)
        assertEquals(null, config.maxTasksOverride)
        assertEquals(config, WidgetDisplayConfig.fromJson(config.toJson()))
    }

    @Test
    fun fullyPopulatedConfigRoundTripsThroughJson() {
        val config = WidgetDisplayConfig(
            sectionOrder = listOf(
                WidgetSection.UP_NEXT, WidgetSection.SEVERITY_DOTS, WidgetSection.GYM_RING,
                WidgetSection.MONEY_TILE, WidgetSection.COURSEWORK_TILE, WidgetSection.WEATHER,
                WidgetSection.SLEEP_TILE, WidgetSection.SOCIAL,
                WidgetSection.BRIEFING_PARAGRAPH, WidgetSection.TODAY_EVENTS,
            ),
            hiddenSections = setOf(WidgetSection.SEVERITY_DOTS, WidgetSection.TODAY_EVENTS),
            scale = 1.2f,
            maxTasksOverride = 5,
        )

        assertEquals(config, WidgetDisplayConfig.fromJson(config.toJson()))
    }

    @Test(expected = JSONException::class)
    fun malformedJsonPropagatesForTheCallerToHandle() {
        // fromJson doesn't swallow parse errors internally -- same contract
        // as BriefingState/NextTasksState.fromJson, whose callers (e.g.
        // BriefingWidget.provideGlance) wrap the call in their own
        // try/catch and fall back to default() rather than this class
        // silently masking a corrupt persisted blob.
        WidgetDisplayConfig.fromJson("not json")
    }

    @Test
    fun unknownSectionNameIsDroppedNotThrown() {
        // Simulates an older/newer app version's section this build doesn't
        // recognize (e.g. a renamed or removed enum value) -- must degrade
        // gracefully, not blank the whole widget.
        val raw = """{"sectionOrder": ["GYM_RING", "SOME_FUTURE_SECTION", "UP_NEXT"],
                       "hiddenSections": [], "scale": 1.0}"""

        val config = WidgetDisplayConfig.fromJson(raw)

        assertEquals(listOf(WidgetSection.GYM_RING, WidgetSection.UP_NEXT),
            config.sectionOrder.filter { it == WidgetSection.GYM_RING || it == WidgetSection.UP_NEXT })
        assertEquals(true, config.sectionOrder.none { it.name == "SOME_FUTURE_SECTION" })
    }

    @Test
    fun sectionMissingFromPersistedOrderIsAppendedAtEnd() {
        // A section added to WidgetSection after this config was saved
        // shouldn't vanish -- it should just show up at the end, not require
        // re-configuring every existing widget instance.
        val raw = """{"sectionOrder": ["UP_NEXT"], "hiddenSections": [], "scale": 1.0}"""

        val config = WidgetDisplayConfig.fromJson(raw)

        assertEquals(WidgetSection.UP_NEXT, config.sectionOrder.first())
        assertEquals(WidgetSection.entries.toSet(), config.sectionOrder.toSet())
    }

    @Test
    fun maxTasksOverrideIsNullWhenAbsent() {
        val config = WidgetDisplayConfig.fromJson("""{"sectionOrder": [], "hiddenSections": [], "scale": 1.0}""")

        assertEquals(null, config.maxTasksOverride)
    }

    @Test
    fun scaleIsClampedToTheConfigScreensSliderRange() {
        // A corrupted or hand-edited persisted blob must not flow an
        // out-of-range scale unclamped into every font/icon size
        // calculation in BriefingWidget.kt.
        val tooLarge = WidgetDisplayConfig.fromJson(
            """{"sectionOrder": [], "hiddenSections": [], "scale": 50.0}""")
        val tooSmall = WidgetDisplayConfig.fromJson(
            """{"sectionOrder": [], "hiddenSections": [], "scale": 0.01}""")

        assertEquals(WidgetDisplayConfig.MAX_SCALE, tooLarge.scale)
        assertEquals(WidgetDisplayConfig.MIN_SCALE, tooSmall.scale)
    }
}
