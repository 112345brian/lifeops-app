package com.lifeops.briefing

import androidx.glance.GlanceTheme
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasText
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
}
