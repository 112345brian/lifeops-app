package com.lifeops.briefing

import androidx.glance.GlanceTheme
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.assertHasText
import androidx.glance.testing.unit.hasTestTag
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BriefingWidgetTest {

    @Test
    fun briefingText_plainText_isPresentInWidgetTree() = runGlanceAppWidgetUnitTest {
        provideComposable {
            GlanceTheme {
                BriefingText("Nothing is on fire today.")
            }
        }

        onNode(hasTestTag("briefing-text-0-0"))
            .assertHasText("Nothing is on fire today.")
    }

    @Test
    fun briefingText_boldMarkup_preservesAllTextSegments() = runGlanceAppWidgetUnitTest {
        provideComposable {
            GlanceTheme {
                BriefingText("**Deadline at risk.** Move the study block.")
            }
        }

        onNode(hasTestTag("briefing-text-0-0")).assertHasText("Deadline at risk.")
        onNode(hasTestTag("briefing-text-0-1")).assertHasText(" Move the study block.")
    }

    @Test
    fun briefingText_blankLines_doNotRenderParagraphRows() = runGlanceAppWidgetUnitTest {
        provideComposable {
            GlanceTheme {
                BriefingText("First paragraph.\n\nSecond paragraph.")
            }
        }

        onNode(hasTestTag("briefing-text-0-0")).assertHasText("First paragraph.")
        onNode(hasTestTag("briefing-text-1-0")).assertHasText("Second paragraph.")
    }
}
