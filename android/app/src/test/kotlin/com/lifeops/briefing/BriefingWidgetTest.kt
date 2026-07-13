package com.lifeops.briefing

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasContentDescription
import androidx.glance.testing.unit.hasText
import com.lifeops.briefing.data.AttentionReason
import com.lifeops.briefing.data.BriefingState
import com.lifeops.briefing.data.NextTasksState
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
    fun smallSize_showsOnlyBadgeAndHeadline() = runGlanceAppWidgetUnitTest {
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
        onNode(hasText("Finish the reading.", true)).assertExists()
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
    fun statTiles_renderMoneyAndCourseworkAsIconTiles() = runGlanceAppWidgetUnitTest {
        setAppWidgetSize(DpSize(250.dp, 200.dp))
        provideComposable {
            GlanceTheme {
                BriefingContent(
                    state = fullState.copy(discretionaryDollars = 340, courseworkHoursNext7d = 6.5),
                    nextTasks = NextTasksState.empty(),
                )
            }
        }

        onNode(hasText("$340", true)).assertExists()
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
}
