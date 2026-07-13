package com.lifeops.briefing

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasContentDescription
import androidx.glance.testing.unit.hasText
import com.lifeops.briefing.data.AttentionReason
import com.lifeops.briefing.data.BriefingState
import com.lifeops.briefing.data.NextTask
import com.lifeops.briefing.data.NextTasksState
import com.lifeops.briefing.data.WidgetDisplayConfig
import com.lifeops.briefing.data.WidgetSection
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BriefingWidgetTest {

    @Test
    fun gymBar_showsFractionLabel() = runGlanceAppWidgetUnitTest {
        provideComposable {
            GlanceTheme {
                BriefingContent(
                    state = BriefingState(date = "2026-07-12", text = "All clear.",
                        gymLast7d = 2, gymTarget = 3),
                    nextTasks = NextTasksState.empty(),
                )
            }
        }

        onNode(hasText("Gym 2/3 (7d)", true)).assertExists()
    }

    @Test
    fun freshSnapshot_showsPlainAsOfLine_noStaleGlyph() = runGlanceAppWidgetUnitTest {
        provideComposable {
            GlanceTheme {
                BriefingContent(
                    state = BriefingState(date = "2026-07-12", text = "All clear.",
                        fetchedAtEpochMillis = System.currentTimeMillis()),
                    nextTasks = NextTasksState.empty(),
                )
            }
        }

        onNode(hasText("just now", true)).assertExists()
        onNode(hasText("⚠", true)).assertDoesNotExist()
    }

    @Test
    fun staleSnapshot_showsWarningGlyph() = runGlanceAppWidgetUnitTest {
        provideComposable {
            GlanceTheme {
                BriefingContent(
                    state = BriefingState(date = "2026-07-12", text = "All clear.",
                        fetchedAtEpochMillis = System.currentTimeMillis() - (3 * 60 * 60 * 1000L)),
                    nextTasks = NextTasksState.empty(),
                )
            }
        }

        onNode(hasText("⚠ stale, as of 3h ago", true)).assertExists()
    }

    private val fullState = BriefingState(
        date = "2026-07-12", text = "All clear.",
        gymLast7d = 2, gymTarget = 3,
        fetchedAtEpochMillis = System.currentTimeMillis(),
    )

    @Test
    fun smallSize_showsOnlyTheBadge() = runGlanceAppWidgetUnitTest {
        setAppWidgetSize(DpSize(120.dp, 90.dp))
        provideComposable {
            GlanceTheme {
                BriefingContent(
                    state = fullState.copy(attentionState = "risk", attentionSymbol = "◆",
                        attentionLabel = "RISK", attentionHeadline = "Finish the reading."),
                    nextTasks = NextTasksState.empty(),
                )
            }
        }

        onNode(hasText("◆ RISK", true)).assertExists()
        // The recommended_action headline is implied by the badge itself and
        // deliberately not rendered (2026-07-12) -- attentionHeadline is
        // still parsed/carried on BriefingState, just not shown here.
        onNode(hasText("Finish the reading.", true)).assertDoesNotExist()
        onNode(hasText("Gym 2/3", true)).assertDoesNotExist()
        onNode(hasText("All clear.", true)).assertDoesNotExist()
    }

    @Test
    fun mediumSize_showsStatsButNotFullBriefingText() = runGlanceAppWidgetUnitTest {
        setAppWidgetSize(DpSize(250.dp, 200.dp))
        provideComposable {
            GlanceTheme {
                BriefingContent(state = fullState, nextTasks = NextTasksState.empty())
            }
        }

        onNode(hasText("Gym 2/3 (7d)", true)).assertExists()
        onNode(hasText("just now", true)).assertExists()
        onNode(hasText("All clear.", true)).assertDoesNotExist()
    }

    @Test
    fun largeSize_showsFullBriefingText() = runGlanceAppWidgetUnitTest {
        setAppWidgetSize(DpSize(250.dp, 250.dp))
        provideComposable {
            GlanceTheme {
                BriefingContent(state = fullState, nextTasks = NextTasksState.empty())
            }
        }

        onNode(hasText("Gym 2/3 (7d)", true)).assertExists()
        onNode(hasText("All clear.", true)).assertExists()
    }

    @Test
    fun statTiles_moneyIsJustTheNumber_courseworkKeepsItsIcon() = runGlanceAppWidgetUnitTest {
        setAppWidgetSize(DpSize(250.dp, 200.dp))
        provideComposable {
            GlanceTheme {
                BriefingContent(
                    state = fullState.copy(discretionaryDollars = 340, courseworkHoursNext7d = 6.5),
                    nextTasks = NextTasksState.empty(),
                )
            }
        }

        // 2026-07-12: money reads as a big bold figure with no glyph -- the
        // amount itself is the message; only coursework keeps an icon tile.
        onNode(hasText("$340", true)).assertExists()
        onNode(hasText("💰", true)).assertDoesNotExist()
        onNode(hasText("📚", true)).assertExists()
        onNode(hasText("6.5h", true)).assertExists()
    }

    @Test
    fun severityDots_renderOneGlyphPerDomain() = runGlanceAppWidgetUnitTest {
        setAppWidgetSize(DpSize(120.dp, 90.dp))
        provideComposable {
            GlanceTheme {
                BriefingContent(
                    state = fullState.copy(
                        attentionState = "risk", attentionSymbol = "◆",
                        attentionLabel = "RISK", attentionHeadline = "Finish the reading.",
                        reasons = listOf(
                            AttentionReason("coursework", "risk"),
                            AttentionReason("money", "watch"),
                        ),
                    ),
                    nextTasks = NextTasksState.empty(),
                )
            }
        }

        onNode(hasContentDescription("coursework: risk")).assertExists()
        onNode(hasContentDescription("money: watch")).assertExists()
        onNode(hasContentDescription("system: ok")).assertExists()
        onNode(hasContentDescription("gym: ok")).assertExists()
    }

    private val eightTasks = (1..8).map { NextTask(id = "$it", title = "Task $it", start = null) }

    @Test
    fun upNext_showsOnlyThreeTasksAtBaseLargeHeight() = runGlanceAppWidgetUnitTest {
        setAppWidgetSize(DpSize(250.dp, 250.dp))
        provideComposable {
            GlanceTheme {
                BriefingContent(state = fullState, nextTasks = NextTasksState(tasks = eightTasks))
            }
        }

        onNode(hasText("Task 1", true)).assertExists()
        onNode(hasText("Task 3", true)).assertExists()
        onNode(hasText("Task 4", true)).assertDoesNotExist()
    }

    @Test
    fun upNext_showsMoreTasksWhenWidgetIsPlacedTaller() = runGlanceAppWidgetUnitTest {
        // A widget resized well past the ~4x4 target (e.g. 4x6+) has real
        // extra room -- 2026-07-13: "why is there so much padding... all
        // that empty space" was a fixed 3-task list not using it.
        setAppWidgetSize(DpSize(250.dp, 600.dp))
        provideComposable {
            GlanceTheme {
                BriefingContent(state = fullState, nextTasks = NextTasksState(tasks = eightTasks))
            }
        }

        onNode(hasText("Task 1", true)).assertExists()
        onNode(hasText("Task 8", true)).assertExists()
    }

    @Test
    fun hiddenSection_doesNotRenderEvenAtLargeBucket() = runGlanceAppWidgetUnitTest {
        setAppWidgetSize(DpSize(250.dp, 250.dp))
        provideComposable {
            GlanceTheme {
                BriefingContent(
                    state = fullState.copy(courseworkHoursNext7d = 6.5),
                    nextTasks = NextTasksState.empty(),
                    config = WidgetDisplayConfig.default().copy(hiddenSections = setOf(WidgetSection.COURSEWORK_TILE)),
                )
            }
        }

        onNode(hasText("6.5h", true)).assertDoesNotExist()
        // Gym stays visible -- only coursework was hidden.
        onNode(hasText("Gym 2/3 (7d)", true)).assertExists()
    }

    @Test
    fun severityDots_renderInlineOnBadgeRow_whenLeftInDefaultPosition() = runGlanceAppWidgetUnitTest {
        setAppWidgetSize(DpSize(120.dp, 90.dp))
        provideComposable {
            GlanceTheme {
                BriefingContent(
                    state = fullState.copy(
                        attentionState = "risk", attentionSymbol = "◆", attentionLabel = "RISK",
                        reasons = listOf(AttentionReason("coursework", "risk")),
                    ),
                    nextTasks = NextTasksState.empty(),
                    // default sectionOrder has SEVERITY_DOTS first -> inline with badge, shown even at SMALL.
                )
            }
        }

        onNode(hasContentDescription("coursework: risk")).assertExists()
    }

    @Test
    fun severityDots_becomeStandaloneRow_whenMovedOutOfDefaultPosition() = runGlanceAppWidgetUnitTest {
        setAppWidgetSize(DpSize(250.dp, 250.dp))
        provideComposable {
            GlanceTheme {
                BriefingContent(
                    state = fullState.copy(
                        attentionState = "risk", attentionSymbol = "◆", attentionLabel = "RISK",
                        reasons = listOf(AttentionReason("coursework", "risk")),
                    ),
                    nextTasks = NextTasksState.empty(),
                    config = WidgetDisplayConfig.default().copy(
                        sectionOrder = WidgetSection.entries.filter { it != WidgetSection.SEVERITY_DOTS } +
                            WidgetSection.SEVERITY_DOTS,
                    ),
                )
            }
        }

        // Still renders somewhere (as its own row now, not inline) -- moved
        // to LARGE bucket since a standalone (non-badge-attached) section
        // only renders past the SMALL-bucket gate, same as every other
        // section.
        onNode(hasContentDescription("coursework: risk")).assertExists()
    }

    @Test
    fun sectionOrder_reordersUpNextAboveBriefingText() = runGlanceAppWidgetUnitTest {
        setAppWidgetSize(DpSize(250.dp, 250.dp))
        provideComposable {
            GlanceTheme {
                BriefingContent(
                    state = fullState,
                    nextTasks = NextTasksState(tasks = listOf(NextTask("1", "Only task", null))),
                    config = WidgetDisplayConfig.default().copy(
                        sectionOrder = listOf(WidgetSection.UP_NEXT, WidgetSection.BRIEFING_PARAGRAPH) +
                            WidgetSection.entries.filter {
                                it != WidgetSection.UP_NEXT && it != WidgetSection.BRIEFING_PARAGRAPH
                            },
                    ),
                )
            }
        }

        // Both still render regardless of order -- this asserts the reorder
        // doesn't silently drop either section (a real ordering assertion
        // would need node-position comparison, which this testing API
        // doesn't expose; presence-after-reorder is the meaningful check
        // available here).
        onNode(hasText("Only task", true)).assertExists()
        onNode(hasText("All clear.", true)).assertExists()
    }

    @Test
    fun maxTasksOverride_belowHeuristicWins() = runGlanceAppWidgetUnitTest {
        setAppWidgetSize(DpSize(250.dp, 250.dp))
        provideComposable {
            GlanceTheme {
                BriefingContent(
                    state = fullState,
                    nextTasks = NextTasksState(tasks = eightTasks),
                    config = WidgetDisplayConfig.default().copy(maxTasksOverride = 1),
                )
            }
        }

        onNode(hasText("Task 1", true)).assertExists()
        onNode(hasText("Task 2", true)).assertDoesNotExist()
    }

    @Test
    fun maxTasksOverride_aboveHardCeilingGetsClamped() = runGlanceAppWidgetUnitTest {
        // Even a widget tall enough for the height heuristic to allow more,
        // and a user override asking for way more than that, must never
        // exceed Glance's hard 10-direct-children-per-container limit (the
        // "Up next" Column holds a header + N rows -- see MAX_TASKS_HARD_CEILING).
        setAppWidgetSize(DpSize(250.dp, 2000.dp))
        val fifteenTasks = (1..15).map { NextTask(id = "$it", title = "Task $it", start = null) }
        provideComposable {
            GlanceTheme {
                BriefingContent(
                    state = fullState,
                    nextTasks = NextTasksState(tasks = fifteenTasks),
                    config = WidgetDisplayConfig.default().copy(maxTasksOverride = 15),
                )
            }
        }

        onNode(hasText("Task 9", true)).assertExists()
        onNode(hasText("Task 10", true)).assertDoesNotExist()
    }

    @Test
    fun scaleAboveOne_stillRendersExpectedText() = runGlanceAppWidgetUnitTest {
        // Font/icon scaling shouldn't affect text matching -- a smoke test
        // that bumping scale doesn't silently break rendering.
        setAppWidgetSize(DpSize(250.dp, 250.dp))
        provideComposable {
            GlanceTheme {
                BriefingContent(
                    state = fullState.copy(discretionaryDollars = 340),
                    nextTasks = NextTasksState.empty(),
                    config = WidgetDisplayConfig.default().copy(scale = 1.3f),
                )
            }
        }

        onNode(hasText("$340", true)).assertExists()
        onNode(hasText("All clear.", true)).assertExists()
    }
}
