package com.lifeops.briefing

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasContentDescription
import androidx.glance.testing.unit.hasText
import com.lifeops.briefing.data.AttentionReason
import com.lifeops.briefing.data.BriefingState
import com.lifeops.briefing.data.GymRing
import com.lifeops.briefing.data.NextTask
import com.lifeops.briefing.data.NextTasksState
import com.lifeops.briefing.data.NotableEvent
import com.lifeops.briefing.data.TodayEvent
import com.lifeops.briefing.data.WeatherInfo
import com.lifeops.briefing.data.WidgetDisplayConfig
import com.lifeops.briefing.data.WidgetSection
import org.junit.Assert.assertEquals
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
    fun singleStatGymPreset_stillShowsGymCardWhenResizedToItsSmallestDeclaredSize() = runGlanceAppWidgetUnitTest {
        // Regression test for the exact bug reported 2026-07-13: a
        // single-stat preset widget (WidgetDisplayConfig.singleStat) could
        // be dragged onto the home screen but not actually resized down to
        // its own declared minimum (gym_widget_info.xml's 120x120dp) without
        // its only content vanishing, because BriefingContent's SMALL
        // bucket used to hard-stop right after the attention badge. Updated
        // from the original 110x56dp to match gym_widget_info.xml's later
        // bump to 120x120dp (2026-07-14, to stop gym content clipping against
        // its own edge) -- this must track that XML's declared minimum, not
        // an arbitrary small size, or a real clipping regression at the
        // widget's true floor would pass silently.
        setAppWidgetSize(DpSize(120.dp, 120.dp))
        provideComposable {
            GlanceTheme {
                BriefingContent(
                    state = fullState,
                    nextTasks = NextTasksState.empty(),
                    config = WidgetDisplayConfig.singleStat(WidgetSection.GYM_RING),
                )
            }
        }

        onNode(hasText("GYM", true)).assertExists()
        onNode(hasText("2/3", true)).assertExists()
        onNode(hasText("7 DAYS", true)).assertDoesNotExist()
        onNode(hasContentDescription("Gym 2/3, today status unavailable")).assertExists()
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
    fun soloMoneyPreset_showsBudgetStatusAndConventionalNegativeAmount() = runGlanceAppWidgetUnitTest {
        setAppWidgetSize(DpSize(120.dp, 120.dp))
        provideComposable {
            GlanceTheme {
                BriefingContent(
                    state = fullState.copy(
                        discretionaryDollars = -160,
                        reasons = listOf(AttentionReason("money", "risk")),
                    ),
                    nextTasks = NextTasksState.empty(),
                    config = WidgetDisplayConfig.singleStat(WidgetSection.MONEY_TILE),
                )
            }
        }

        onNode(hasText("OVER", true)).assertExists()
        onNode(hasText("-$160", true)).assertExists()
        onNode(hasText("$-160", true)).assertDoesNotExist()
    }

    @Test
    fun soloMoneyPreset_headlinesTodaysBudgetWhenSomethingIsEarmarkedForToday() = runGlanceAppWidgetUnitTest {
        // 2026-07-15: checking the widget mid-outing must not read as
        // "broke" just because discretionaryDollars nets in NEXT week's
        // plans too -- discretionaryTodayDollars headlines instead, labeled
        // "TODAY", while the future/net balance still remains visible.
        setAppWidgetSize(DpSize(120.dp, 120.dp))
        provideComposable {
            GlanceTheme {
                BriefingContent(
                    state = fullState.copy(
                        discretionaryDollars = -125, discretionaryTodayDollars = 40,
                        reasons = listOf(AttentionReason("money", "risk")),
                    ),
                    nextTasks = NextTasksState.empty(),
                    config = WidgetDisplayConfig.singleStat(WidgetSection.MONEY_TILE),
                )
            }
        }

        onNode(hasText("TODAY", true)).assertExists()
        onNode(hasText("$40", true)).assertExists()
        onNode(hasText("FUTURE -$125", true)).assertExists()
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
    fun weatherSection_prefersPhoneWeatherOverEverythingElse() = runGlanceAppWidgetUnitTest {
        // 2026-07-15: weather now has three sources of decreasing freshness
        // AND decreasing server-dependence -- phoneWeather (fetched
        // directly from NOAA by this phone, see PhoneWeather.kt, ZERO
        // server dependency) must win over both nextTasks.weather (server,
        // ~15-min) and state's own weather fields (server, once/day), so
        // the widget keeps updating even if the LifeOps server is down
        // entirely ("if the server goes down, we'd want the widget to
        // update regardless").
        setAppWidgetSize(DpSize(250.dp, 200.dp))
        provideComposable {
            GlanceTheme {
                BriefingContent(
                    state = fullState.copy(temperatureF = 64, weatherHighF = 70, weatherLowF = 58,
                        weatherCondition = "Sunny"),
                    nextTasks = NextTasksState(tasks = emptyList(), weather = WeatherInfo(
                        temperatureF = 71, highF = 80, lowF = 60, condition = "Cloudy")),
                    phoneWeather = WeatherInfo(temperatureF = 68, highF = 75, lowF = 55, condition = "Clear"),
                )
            }
        }

        onNode(hasText("68", true)).assertExists()
        onNode(hasText("↑75°", true)).assertExists()
        onNode(hasText("↓55°", true)).assertExists()
        onNode(hasText("Clear", true)).assertExists()
        onNode(hasText("71", true)).assertDoesNotExist()
        onNode(hasText("64", true)).assertDoesNotExist()
    }

    @Test
    fun weatherSection_prefersLiveNextTasksWeatherOverStaleBriefingState() = runGlanceAppWidgetUnitTest {
        // 2026-07-15: weather used to come ONLY from BriefingState, which
        // only refreshes once/day inside run_briefing -- a widget checked
        // mid-afternoon kept showing whatever NOAA said that morning.
        // nextTasks.weather (refreshed ~every 15 min via NextTasksRefreshWorker,
        // same pull as gym_ring) must win when both are present.
        setAppWidgetSize(DpSize(250.dp, 200.dp))
        provideComposable {
            GlanceTheme {
                BriefingContent(
                    state = fullState.copy(temperatureF = 64, weatherHighF = 70, weatherLowF = 58,
                        weatherCondition = "Sunny"),
                    nextTasks = NextTasksState(tasks = emptyList(), weather = WeatherInfo(
                        temperatureF = 71, highF = 80, lowF = 60, condition = "Cloudy")),
                )
            }
        }

        onNode(hasText("71", true)).assertExists()
        onNode(hasText("↑80°", true)).assertExists()
        onNode(hasText("↓60°", true)).assertExists()
        onNode(hasText("Cloudy", true)).assertExists()
        onNode(hasText("64", true)).assertDoesNotExist()
    }

    @Test
    fun weatherSection_fallsBackToBriefingStateWhenNextTasksHasNoWeatherYet() = runGlanceAppWidgetUnitTest {
        // A freshly-installed widget (or one whose next-tasks pull hasn't
        // landed yet) must still show the once-daily briefing's weather
        // rather than nothing at all.
        setAppWidgetSize(DpSize(250.dp, 200.dp))
        provideComposable {
            GlanceTheme {
                BriefingContent(
                    state = fullState.copy(temperatureF = 64, weatherHighF = 70, weatherLowF = 58,
                        weatherCondition = "Sunny"),
                    nextTasks = NextTasksState.empty(),
                )
            }
        }

        onNode(hasText("64", true)).assertExists()
        onNode(hasText("Sunny", true)).assertExists()
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
        onNode(hasText("4d ago", true)).assertExists()
        onNode(hasText("2d ago", true)).assertExists()
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

        onNode(hasText("4d ago", true)).assertExists()
        onNode(hasText("👥", true)).assertDoesNotExist()
    }

    @Test
    fun singleStatSocialPreset_focusesMostActionableCadence() = runGlanceAppWidgetUnitTest {
        setAppWidgetSize(DpSize(120.dp, 120.dp))
        provideComposable {
            GlanceTheme {
                BriefingContent(
                    state = fullState.copy(
                        partnerDaysSince = 4,
                        partnerDaysUntil = 2,
                        friendDaysSince = 8,
                        friendDaysUntil = null,
                    ),
                    nextTasks = NextTasksState.empty(),
                    config = WidgetDisplayConfig.singleStat(WidgetSection.SOCIAL),
                )
            }
        }

        onNode(hasText("FRIENDS", true)).assertExists()
        onNode(hasText("8d", true)).assertExists()
        onNode(hasText("AGO", true)).assertExists()
        onNode(hasText("💜", true)).assertDoesNotExist()
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
                        events = listOf(TodayEvent("Papa's BBQ", null)),
                    ),
                )
            }
        }

        onNode(hasText("No briefing yet", true)).assertExists()
        onNode(hasText("Only task", true)).assertExists()
        onNode(hasText("Papa's BBQ", true)).assertExists()
    }

    private val sixEvents = (1..6).map { TodayEvent("Event $it", null) }
    private val sixNotable = (1..6).map { NotableEvent("Notable $it", "2026-07-1$it", "Saturday") }

    @Test
    fun todayEvents_showsOverflowIndicatorWhenTruncated() = runGlanceAppWidgetUnitTest {
        // 2026-07-15: EventsSection used to render every event unconditionally
        // with NO height-awareness at all -- confirmed as the exact gap
        // UP_NEXT's earlier fix (2026-07-13) never covered. Now capped at
        // EVENTS_HARD_CEILING (5) even with plenty of height, same as
        // today_events_input's own server-side n=5 cap, and the cut is
        // labeled rather than silent.
        setAppWidgetSize(DpSize(250.dp, 400.dp))
        provideComposable {
            GlanceTheme {
                BriefingContent(state = fullState, nextTasks = NextTasksState(tasks = emptyList(), events = sixEvents))
            }
        }

        onNode(hasText("Event 5", true)).assertExists()
        onNode(hasText("Event 6", true)).assertDoesNotExist()
        onNode(hasText("+1 more", true)).assertExists()
    }

    @Test
    fun multipleDynamicListsTogether_stillLeaveRoomForTheFreshnessLine() = runGlanceAppWidgetUnitTest {
        // The actual regression this whole allocator exists to fix: TODAY_EVENTS,
        // NOTABLE_EVENTS, and UP_NEXT enabled TOGETHER at a height that's
        // comfortable for any ONE of them alone must not let them jointly
        // assume they each own the full extra height -- that would overflow
        // past the widget's real placed size and clip the freshness line,
        // even though the pre-2026-07-15 code (TODAY_EVENTS had no
        // height-awareness, UP_NEXT computed its own budget in isolation)
        // would have let exactly that happen.
        setAppWidgetSize(DpSize(250.dp, 250.dp))
        provideComposable {
            GlanceTheme {
                BriefingContent(
                    state = fullState.copy(notableEvents = sixNotable),
                    nextTasks = NextTasksState(tasks = eightTasks, events = sixEvents),
                )
            }
        }

        // Every enabled list still shows its guaranteed minimum...
        onNode(hasText("Event 1", true)).assertExists()
        onNode(hasText("Notable 1", true)).assertExists()
        onNode(hasText("Task 1", true)).assertExists()
        // ...but the freshness line -- the thing that silently vanished
        // before this fix -- is still there too, at the base LARGE height
        // where there's no real extra room to share around at all.
        onNode(hasText("just now", true)).assertExists()
    }

    @Test
    fun notableEvents_soloCompact_showsOnlyTheSoonestEventAsASoloStatCard() = runGlanceAppWidgetUnitTest {
        // The "LifeOps Events" single-stat preset at its true small footprint:
        // NotableEventsSection collapses to just the soonest event, mirroring
        // WeatherCard's own compact-at-small pattern, rather than trying to
        // cram a list into a 1x1 tile -- and (2026-07-15) renders it via the
        // same SoloStatCard shape every other solo preset (Money/Coursework/
        // Sleep/Social) already uses: label "EVENTS", the title as the big
        // value, a 3-letter weekday as the status-bar word.
        setAppWidgetSize(DpSize(120.dp, 90.dp))
        provideComposable {
            GlanceTheme {
                BriefingContent(
                    state = fullState.copy(notableEvents = listOf(
                        NotableEvent("Haircut", "2026-07-16", "Thursday"),
                        NotableEvent("Dentist", "2026-07-20", "Monday"),
                    )),
                    nextTasks = NextTasksState.empty(),
                    config = WidgetDisplayConfig.singleStat(WidgetSection.NOTABLE_EVENTS),
                )
            }
        }

        onNode(hasText("EVENTS", true)).assertExists()
        onNode(hasText("Haircut", true)).assertExists()
        onNode(hasText("THU", true)).assertExists()
        onNode(hasText("Dentist", true)).assertDoesNotExist()
    }

    @Test
    fun notableEvents_soloAtLargerSize_showsFullListAsChips() = runGlanceAppWidgetUnitTest {
        // At MEDIUM/LARGE the solo preset switches to a capped list of
        // chips -- same rounded, tinted-background shape SocialTile/StatTile
        // already use in the full combo widget, not plain unstyled text.
        setAppWidgetSize(DpSize(250.dp, 250.dp))
        provideComposable {
            GlanceTheme {
                BriefingContent(
                    state = fullState.copy(notableEvents = listOf(
                        NotableEvent("Haircut", "2026-07-16", "Thursday", start = "2026-07-16T10:00:00"),
                        NotableEvent("Dentist", "2026-07-20", "Monday", start = "2026-07-20T14:30:00"),
                    )),
                    nextTasks = NextTasksState.empty(),
                    config = WidgetDisplayConfig.singleStat(WidgetSection.NOTABLE_EVENTS),
                )
            }
        }

        // Day, time, and title are three separate Text nodes now (see
        // NotableEventLine's doc -- each needs its own fixed-width column
        // for real cross-row alignment, which one combined string can't
        // give them), not one combined "Thu 10:00 AM Haircut" node.
        onNode(hasText("Thu", true)).assertExists()
        onNode(hasText("10:00 AM", true)).assertExists()
        onNode(hasText("Haircut", true)).assertExists()
        onNode(hasText("Mon", true)).assertExists()
        onNode(hasText("2:30 PM", true)).assertExists()
        onNode(hasText("Dentist", true)).assertExists()
    }

    @Test
    fun notableEvents_soloCompact_emptyList_showsExplicitEmptyState() = runGlanceAppWidgetUnitTest {
        // A genuinely empty upcoming-events list (most weeks have zero
        // one-off events) is a normal result, not a loading/error state --
        // the standalone "LifeOps Events" widget must say so instead of
        // rendering as a permanent blank box (confirmed 2026-07-15: "what
        // if we don't have any events there? ... this widget should be
        // dynamic").
        setAppWidgetSize(DpSize(120.dp, 90.dp))
        provideComposable {
            GlanceTheme {
                BriefingContent(
                    state = fullState.copy(notableEvents = emptyList()),
                    nextTasks = NextTasksState.empty(),
                    config = WidgetDisplayConfig.singleStat(WidgetSection.NOTABLE_EVENTS),
                )
            }
        }

        onNode(hasText("EVENTS", true)).assertExists()
        onNode(hasText("None", true)).assertExists()
        onNode(hasText("CLEAR", true)).assertExists()
    }

    @Test
    fun notableEvents_soloAtLargerSize_emptyList_showsExplicitEmptyState() = runGlanceAppWidgetUnitTest {
        setAppWidgetSize(DpSize(250.dp, 250.dp))
        provideComposable {
            GlanceTheme {
                BriefingContent(
                    state = fullState.copy(notableEvents = emptyList()),
                    nextTasks = NextTasksState.empty(),
                    config = WidgetDisplayConfig.singleStat(WidgetSection.NOTABLE_EVENTS),
                )
            }
        }

        onNode(hasText("Coming up", true)).assertExists()
        onNode(hasText("Nothing scheduled", true)).assertExists()
    }

    @Test
    fun comboGrid_emptyNotableEvents_omitsEventsCellAndUsesSpaceForPriorityCells() = runGlanceAppWidgetUnitTest {
        // 280x220dp exercises the richer/taller combo variant; the provider
        // itself can now shrink as far as the 2x2 tier.
        setAppWidgetSize(DpSize(280.dp, 220.dp))
        provideComposable {
            GlanceTheme {
                BriefingContent(
                    state = fullState.copy(
                        temperatureF = 64,
                        notableEvents = emptyList(),
                        discretionaryDollars = 250,
                        partnerDaysSince = 6,
                    ),
                    nextTasks = NextTasksState.empty(),
                    config = WidgetDisplayConfig.comboGrid(),
                )
            }
        }

        onNode(hasText("Nothing scheduled", true)).assertDoesNotExist()
        onNode(hasText("64", true)).assertExists()
        onNode(hasText("GYM", true)).assertExists()
        onNode(hasText("SPEND", true)).assertExists()
        onNode(hasText("PARTNER", true)).assertExists()
    }

    @Test
    fun comboGrid_tallModeShowsPriorityCellsAndBothSocialRelationships() = runGlanceAppWidgetUnitTest {
        setAppWidgetSize(DpSize(280.dp, 220.dp))
        provideComposable {
            GlanceTheme {
                BriefingContent(
                    state = fullState.copy(
                        discretionaryDollars = -125,
                        partnerDaysSince = 8,
                        partnerDaysUntil = null,
                        friendDaysSince = 2,
                        friendDaysUntil = 3,
                        notableEvents = sixNotable,
                        // High/low deliberately distinct from the temp AND
                        // each other -- "64" as a hasText(substring=true)
                        // needle would otherwise ambiguously match more than
                        // one node (e.g. a low of 64 inside "↓64°") and
                        // onNode() throws on more than one match.
                        temperatureF = 64, weatherHighF = 92, weatherLowF = 58, weatherCondition = "Sunny",
                    ),
                    nextTasks = NextTasksState.empty(),
                    config = WidgetDisplayConfig.comboGrid(),
                )
            }
        }

        onNode(hasText("64", true)).assertExists()
        onNode(hasText("GYM", true)).assertExists()
        onNode(hasText("Coming up", true)).assertExists()
        onNode(hasText("TODAY", true)).assertDoesNotExist()
        onNode(hasText("SPEND", true)).assertExists()
        onNode(hasText("-$125", true)).assertExists()
        onNode(hasText("SOCIAL", true)).assertExists()
        onNode(hasText("P 8d ago", true)).assertExists()
        onNode(hasText("F 3d next", true)).assertExists()
    }

    @Test
    fun comboLayoutFor_mapsCommonLauncherSpansToExplicitVariants() {
        assertEquals(ComboLayout.COMPACT_2X2, comboLayoutFor(DpSize(120.dp, 120.dp)))
        assertEquals(ComboLayout.MEDIUM_3X2, comboLayoutFor(DpSize(200.dp, 150.dp)))
        assertEquals(ComboLayout.WIDE_4X2, comboLayoutFor(DpSize(280.dp, 150.dp)))
        assertEquals(ComboLayout.TALL_4X3, comboLayoutFor(DpSize(280.dp, 220.dp)))
    }

    @Test
    fun comboGrid_2x2_showsWeatherAndGymOnly() = runGlanceAppWidgetUnitTest {
        setAppWidgetSize(DpSize(120.dp, 120.dp))
        provideComposable {
            GlanceTheme {
                BriefingContent(
                    state = fullState.copy(
                        discretionaryDollars = 250,
                        partnerDaysSince = 6,
                        courseworkHoursNext7d = 4.1,
                        temperatureF = 64,
                        notableEvents = sixNotable,
                    ),
                    nextTasks = NextTasksState.empty(),
                    config = WidgetDisplayConfig.comboGrid(),
                )
            }
        }

        onNode(hasText("64", true)).assertExists()
        onNode(hasText("GYM", true)).assertExists()
        onNode(hasText("SPEND", true)).assertDoesNotExist()
        onNode(hasText("PARTNER", true)).assertDoesNotExist()
        onNode(hasText("Coming up", true)).assertDoesNotExist()
    }

    @Test
    fun comboGrid_compactMoneyCellShowsTodayWithoutFutureSecondary() = runGlanceAppWidgetUnitTest {
        setAppWidgetSize(DpSize(280.dp, 150.dp))
        provideComposable {
            GlanceTheme {
                BriefingContent(
                    state = fullState.copy(
                        temperatureF = 64,
                        discretionaryDollars = -125,
                        discretionaryTodayDollars = 40,
                        partnerDaysSince = 6,
                    ),
                    nextTasks = NextTasksState.empty(),
                    config = WidgetDisplayConfig.comboGrid(),
                )
            }
        }

        onNode(hasText("TODAY", true)).assertExists()
        onNode(hasText("$40", true)).assertExists()
        onNode(hasText("FUTURE -$125", true)).assertDoesNotExist()
    }

    @Test
    fun comboGrid_tallMoneyCellShowsTodayAndFutureNumbers() = runGlanceAppWidgetUnitTest {
        setAppWidgetSize(DpSize(280.dp, 220.dp))
        provideComposable {
            GlanceTheme {
                BriefingContent(
                    state = fullState.copy(
                        temperatureF = 64,
                        discretionaryDollars = -125,
                        discretionaryTodayDollars = 40,
                        notableEvents = sixNotable,
                    ),
                    nextTasks = NextTasksState.empty(),
                    config = WidgetDisplayConfig.comboGrid(),
                )
            }
        }

        onNode(hasText("TODAY", true)).assertExists()
        onNode(hasText("$40", true)).assertExists()
        onNode(hasText("FUTURE -$125", true)).assertExists()
    }

    @Test
    fun comboGrid_3x2_addsNotableEventsWhenTheyExist() = runGlanceAppWidgetUnitTest {
        setAppWidgetSize(DpSize(200.dp, 150.dp))
        provideComposable {
            GlanceTheme {
                BriefingContent(
                    state = fullState.copy(
                        discretionaryDollars = 250,
                        partnerDaysSince = 6,
                        courseworkHoursNext7d = 4.1,
                        temperatureF = 64,
                        notableEvents = sixNotable,
                    ),
                    nextTasks = NextTasksState.empty(),
                    config = WidgetDisplayConfig.comboGrid(),
                )
            }
        }

        onNode(hasText("64", true)).assertExists()
        onNode(hasText("GYM", true)).assertExists()
        onNode(hasText("Coming up", true)).assertExists()
        onNode(hasText("Notable 1", true)).assertExists()
        onNode(hasText("SPEND", true)).assertDoesNotExist()
    }

    @Test
    fun comboGrid_3x2_skipsEmptyEventsAndAddsMoney() = runGlanceAppWidgetUnitTest {
        setAppWidgetSize(DpSize(200.dp, 150.dp))
        provideComposable {
            GlanceTheme {
                BriefingContent(
                    state = fullState.copy(
                        discretionaryDollars = 250,
                        partnerDaysSince = 6,
                        temperatureF = 64,
                        notableEvents = emptyList(),
                    ),
                    nextTasks = NextTasksState.empty(),
                    config = WidgetDisplayConfig.comboGrid(),
                )
            }
        }

        onNode(hasText("Nothing scheduled", true)).assertDoesNotExist()
        onNode(hasText("64", true)).assertExists()
        onNode(hasText("GYM", true)).assertExists()
        onNode(hasText("SPEND", true)).assertExists()
    }

    @Test
    fun comboGrid_4x2_addsMoneyAfterEvents() = runGlanceAppWidgetUnitTest {
        setAppWidgetSize(DpSize(280.dp, 150.dp))
        provideComposable {
            GlanceTheme {
                BriefingContent(
                    state = fullState.copy(
                        discretionaryDollars = 250,
                        partnerDaysSince = 6,
                        courseworkHoursNext7d = 4.1,
                        temperatureF = 64,
                        weatherHighF = 92,
                        weatherLowF = 58,
                        weatherCondition = "Sunny",
                        notableEvents = sixNotable,
                    ),
                    nextTasks = NextTasksState.empty(),
                    config = WidgetDisplayConfig.comboGrid(),
                )
            }
        }

        onNode(hasText("64", true)).assertExists()
        onNode(hasText("GYM", true)).assertExists()
        onNode(hasText("Coming up", true)).assertExists()
        onNode(hasText("SPEND", true)).assertExists()
        onNode(hasText("PARTNER", true)).assertDoesNotExist()
    }

    @Test
    fun comboGrid_tallerPlacementAddsEvents() = runGlanceAppWidgetUnitTest {
        setAppWidgetSize(DpSize(280.dp, 220.dp))
        provideComposable {
            GlanceTheme {
                BriefingContent(
                    state = fullState.copy(
                        discretionaryDollars = 250,
                        partnerDaysSince = 6,
                        courseworkHoursNext7d = 4.1,
                        temperatureF = 64,
                        notableEvents = sixNotable,
                    ),
                    nextTasks = NextTasksState.empty(),
                    config = WidgetDisplayConfig.comboGrid(),
                )
            }
        }

        onNode(hasText("64", true)).assertExists()
        onNode(hasText("GYM", true)).assertExists()
        onNode(hasText("Coming up", true)).assertExists()
        onNode(hasText("Notable 1", true)).assertExists()
    }

    @Test
    fun comboGrid_honorsHiddenSectionsWhenSpaceIsTight() = runGlanceAppWidgetUnitTest {
        setAppWidgetSize(DpSize(120.dp, 120.dp))
        provideComposable {
            GlanceTheme {
                BriefingContent(
                    state = fullState.copy(
                        discretionaryDollars = 250,
                        partnerDaysSince = 6,
                        courseworkHoursNext7d = 4.1,
                    ),
                    nextTasks = NextTasksState.empty(),
                    config = WidgetDisplayConfig.comboGrid().copy(
                        hiddenSections = WidgetDisplayConfig.comboGrid().hiddenSections + WidgetSection.SOCIAL,
                    ),
                )
            }
        }

        onNode(hasText("64", true)).assertDoesNotExist()
        onNode(hasText("GYM", true)).assertExists()
        onNode(hasText("SPEND", true)).assertExists()
        onNode(hasText("PARTNER", true)).assertDoesNotExist()
    }

    @Test
    fun comboGrid_honorsSectionOrderWhenChoosingCompactCells() = runGlanceAppWidgetUnitTest {
        setAppWidgetSize(DpSize(120.dp, 120.dp))
        provideComposable {
            GlanceTheme {
                BriefingContent(
                    state = fullState.copy(
                        discretionaryDollars = 250,
                        partnerDaysSince = 6,
                        courseworkHoursNext7d = 4.1,
                    ),
                    nextTasks = NextTasksState.empty(),
                    config = WidgetDisplayConfig.comboGrid().copy(
                        sectionOrder = listOf(WidgetSection.COURSEWORK_TILE, WidgetSection.MONEY_TILE, WidgetSection.SOCIAL) +
                            WidgetSection.entries.filter {
                                it != WidgetSection.COURSEWORK_TILE &&
                                    it != WidgetSection.MONEY_TILE &&
                                    it != WidgetSection.SOCIAL
                            },
                        hiddenSections = WidgetDisplayConfig.comboGrid().hiddenSections - WidgetSection.COURSEWORK_TILE,
                    ),
                )
            }
        }

        onNode(hasText("COURSEWORK", true)).assertExists()
        onNode(hasText("SPEND", true)).assertExists()
        onNode(hasText("PARTNER", true)).assertDoesNotExist()
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
        // Gym stays visible in the full widget's compact row -- only coursework was hidden.
        onNode(hasText("Gym 2/3 (7d)", true)).assertExists()
    }

    @Test
    fun singleStatGymPreset_usesTodayStatusFromLiveGymRing() = runGlanceAppWidgetUnitTest {
        setAppWidgetSize(DpSize(120.dp, 120.dp))
        provideComposable {
            GlanceTheme {
                BriefingContent(
                    state = fullState,
                    nextTasks = NextTasksState(
                        tasks = emptyList(),
                        gymRing = GymRing(fill = 0.5f, color = "yellow", gymLast7d = 2, gymTarget = 4),
                    ),
                    config = WidgetDisplayConfig.singleStat(WidgetSection.GYM_RING),
                )
            }
        }

        onNode(hasText("GYM", true)).assertExists()
        onNode(hasText("2/4", true)).assertExists()
        onNode(hasText("TODAY", true)).assertDoesNotExist()
        onNode(hasContentDescription("Gym 2/4, needs gym today")).assertExists()
    }

    @Test
    fun singleStatGymPreset_fullAndDoneEncodesHealthyCountAndNoActionNeeded() = runGlanceAppWidgetUnitTest {
        setAppWidgetSize(DpSize(120.dp, 120.dp))
        provideComposable {
            GlanceTheme {
                BriefingContent(
                    state = fullState,
                    nextTasks = NextTasksState(
                        tasks = emptyList(),
                        gymRing = GymRing(
                            fill = 1.0f,
                            color = "green",
                            gymLast7d = 4,
                            gymTarget = 4,
                            todayDone = true,
                        ),
                    ),
                    config = WidgetDisplayConfig.singleStat(WidgetSection.GYM_RING),
                )
            }
        }

        onNode(hasText("4/4", true)).assertExists()
        onNode(hasText("DONE", true)).assertDoesNotExist()
        onNode(hasContentDescription("Gym 4/4, no gym needed today")).assertExists()
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
        // "Up next" Column holds a header + N rows + (when truncated) an
        // OverflowIndicator -- see MAX_TASKS_HARD_CEILING, 8 so 1+8+1=10).
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

        onNode(hasText("Task 8", true)).assertExists()
        onNode(hasText("Task 9", true)).assertDoesNotExist()
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
