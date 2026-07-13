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
    fun singleStatGymPreset_stillShowsGymRingWhenResizedToItsSmallestDeclaredSize() = runGlanceAppWidgetUnitTest {
        // Regression test for the exact bug reported 2026-07-13: a
        // single-stat preset widget (WidgetDisplayConfig.singleStat) could
        // be dragged onto the home screen but not actually resized down to
        // its own declared minimum (gym_widget_info.xml's 110x56dp) without
        // its only content vanishing, because BriefingContent's SMALL
        // bucket used to hard-stop right after the attention badge.
        setAppWidgetSize(DpSize(110.dp, 56.dp))
        provideComposable {
            GlanceTheme {
                BriefingContent(
                    state = fullState,
                    nextTasks = NextTasksState.empty(),
                    config = WidgetDisplayConfig.singleStat(WidgetSection.GYM_RING),
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
    fun smallSize_showsBadgeAndCompactTiles_butNotParagraphOrFreshness() = runGlanceAppWidgetUnitTest {
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
        // 2026-07-13: SMALL used to hard-stop right after the badge, which
        // meant a single-stat preset widget (e.g. "LifeOps Gym") couldn't
        // actually be resized down to its own declared minimum without
        // losing its only content. TILE_SECTIONS (gym/money/coursework/
        // sleep) are compact single-row tiles, so they render at every
        // size, including SMALL -- only the wider/richer sections
        // (paragraph, freshness line, events, up-next, weather, social)
        // still require at least MEDIUM.
        onNode(hasText("Gym 2/3", true)).assertExists()
        onNode(hasText("All clear.", true)).assertDoesNotExist()
        onNode(hasText("just now", true)).assertDoesNotExist()
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
    fun weatherSection_showsTempHighLowAndCondition() = runGlanceAppWidgetUnitTest {
        setAppWidgetSize(DpSize(250.dp, 200.dp))
        provideComposable {
            GlanceTheme {
                BriefingContent(
                    state = fullState.copy(temperatureF = 73, weatherHighF = 85, weatherLowF = 67,
                        weatherCondition = "Cloudy"),
                    nextTasks = NextTasksState.empty(),
                )
            }
        }

        onNode(hasText("73", true)).assertExists()
        onNode(hasText("↑85°", true)).assertExists()
        onNode(hasText("↓67°", true)).assertExists()
        onNode(hasText("Cloudy", true)).assertExists()
        onNode(hasText("☁️", true)).assertExists()
    }

    @Test
    fun weatherSection_hiddenUntilFirstBriefingArrives() = runGlanceAppWidgetUnitTest {
        setAppWidgetSize(DpSize(250.dp, 200.dp))
        provideComposable {
            GlanceTheme {
                BriefingContent(
                    // temperatureF wouldn't actually be set with text still
                    // null in practice (both come from the same facts
                    // snapshot -- see TEXT_GATED_SECTIONS' docstring), but
                    // this isolates that the gate itself still applies.
                    state = BriefingState(date = "2026-07-12", text = null, temperatureF = 73),
                    nextTasks = NextTasksState.empty(),
                )
            }
        }

        onNode(hasText("73", true)).assertDoesNotExist()
    }

    @Test
    fun sleepTile_showsFormattedDuration() = runGlanceAppWidgetUnitTest {
        setAppWidgetSize(DpSize(250.dp, 200.dp))
        provideComposable {
            GlanceTheme {
                BriefingContent(
                    state = fullState.copy(sleepMinutes = 402),
                    nextTasks = NextTasksState.empty(),
                )
            }
        }

        onNode(hasText("😴", true)).assertExists()
        onNode(hasText("6h42m", true)).assertExists()
    }

    @Test
    fun socialSection_showsBothPartnerAndFriendDays() = runGlanceAppWidgetUnitTest {
        setAppWidgetSize(DpSize(250.dp, 200.dp))
        provideComposable {
            GlanceTheme {
                BriefingContent(
                    state = fullState.copy(partnerDaysSince = 4, friendDaysSince = 2),
                    nextTasks = NextTasksState.empty(),
                )
            }
        }

        onNode(hasText("💜", true)).assertExists()
        onNode(hasText("👥", true)).assertExists()
        onNode(hasText("4d", true)).assertExists()
        onNode(hasText("2d", true)).assertExists()
    }

    @Test
    fun socialSection_showsOnlyThePartnerFigureWhenFriendsIsMissing() = runGlanceAppWidgetUnitTest {
        setAppWidgetSize(DpSize(250.dp, 200.dp))
        provideComposable {
            GlanceTheme {
                BriefingContent(
                    state = fullState.copy(partnerDaysSince = 4, friendDaysSince = null),
                    nextTasks = NextTasksState.empty(),
                )
            }
        }

        onNode(hasText("4d", true)).assertExists()
        onNode(hasText("👥", true)).assertDoesNotExist()
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
    fun maxTasksForHeight_reservesRoomForTheFreshnessLine() = runGlanceAppWidgetUnitTest {
        // 2026-07-13: maxTasksForHeight used to spend every extra dp on
        // task rows with nothing held back for the "as of ..." freshness
        // line below -- Glance/RemoteViews clips overflow rather than
        // scrolling it, so on a real device that line was the first thing
        // silently clipped off as a resized widget's task count grew to
        // fill the space (not observable via this Robolectric harness,
        // which doesn't simulate real pixel clipping -- see the file's own
        // "Glance enforces a hard 10-child limit... NOT enforced by
        // Robolectric" note for the same class of gap). What IS directly
        // testable here: at 386dp tall, the unreserved calculation would
        // fit a 7th task ((386-250)/34 + 3 = 7); reserving
        // STALE_INDICATOR_RESERVED_DP first drops that to 6, trading one
        // task row for headroom the freshness line actually needs.
        setAppWidgetSize(DpSize(250.dp, 386.dp))
        provideComposable {
            GlanceTheme {
                BriefingContent(state = fullState, nextTasks = NextTasksState(tasks = eightTasks))
            }
        }

        onNode(hasText("Task 6", true)).assertExists()
        onNode(hasText("Task 7", true)).assertDoesNotExist()
        onNode(hasText("just now", true)).assertExists()
    }

    @Test
    fun eventsAndTasksStillShow_evenWhenNoBriefingTextHasEverArrived() = runGlanceAppWidgetUnitTest {
        // 2026-07-13: a fresh install (or any moment before the first
        // briefing push lands) must not swallow real calendar events/tasks
        // that have already arrived via the separate next-tasks channel --
        // EventsSection/UpNextSection are deliberately independent of
        // briefing text, matching the pre-customization behavior.
        setAppWidgetSize(DpSize(250.dp, 250.dp))
        provideComposable {
            GlanceTheme {
                BriefingContent(
                    state = BriefingState(date = "2026-07-13", text = null),
                    nextTasks = NextTasksState(
                        tasks = listOf(NextTask("1", "Only task", null)),
                        events = listOf(com.lifeops.briefing.data.TodayEvent("Papa's BBQ", null)),
                    ),
                )
            }
        }

        onNode(hasText("No briefing yet", true)).assertExists()
        onNode(hasText("Only task", true)).assertExists()
        onNode(hasText("Papa's BBQ", true)).assertExists()
    }

    @Test
    fun severityDots_stayInline_whenAHiddenSectionSitsAheadOfThemInRawOrder() = runGlanceAppWidgetUnitTest {
        // 2026-07-13: dotsInline must be computed from the HIDE-FILTERED
        // visible order, not the raw persisted sectionOrder -- a hidden
        // section ahead of SEVERITY_DOTS must not defeat the
        // inline-with-badge rule just because it still occupies an earlier
        // index in the unfiltered list.
        setAppWidgetSize(DpSize(120.dp, 90.dp))
        provideComposable {
            GlanceTheme {
                BriefingContent(
                    state = fullState.copy(
                        attentionState = "risk", attentionSymbol = "◆", attentionLabel = "RISK",
                        reasons = listOf(AttentionReason("coursework", "risk")),
                    ),
                    nextTasks = NextTasksState.empty(),
                    config = WidgetDisplayConfig.default().copy(
                        sectionOrder = listOf(WidgetSection.GYM_RING, WidgetSection.SEVERITY_DOTS) +
                            WidgetSection.entries.filter {
                                it != WidgetSection.GYM_RING && it != WidgetSection.SEVERITY_DOTS
                            },
                        hiddenSections = setOf(WidgetSection.GYM_RING),
                    ),
                )
            }
        }

        // Dots must still exist -- whether inline or standalone, they must
        // not be lost -- but since GYM_RING (the only thing ahead of them)
        // is hidden, they should render even at the SMALL bucket (i.e.
        // inline with the badge, which is the only path reachable at SMALL).
        onNode(hasContentDescription("coursework: risk")).assertExists()
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
