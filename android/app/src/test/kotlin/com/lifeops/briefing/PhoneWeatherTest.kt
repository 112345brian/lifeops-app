package com.lifeops.briefing

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/** Pure-logic pieces of PhoneWeather.kt -- the phone-side NOAA fetch that
 * mirrors lifeops/weather.py's own points->forecast logic, but runs
 * entirely on-device with no server dependency. HTTP/SharedPreferences
 * plumbing isn't covered here (would need Robolectric + a fake HTTP layer);
 * this is the same "trust the shared helper, test the math" split as the
 * rest of this file's tests.
 *
 * org.json.JSONObject is the real Android stub jar on the unit-test
 * classpath (throws "not mocked" on every call) unless run under
 * Robolectric, same as BriefingStateTest/NextTasksStateTest. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PhoneWeatherTest {

    @Test
    fun toF_passesThroughFahrenheit() {
        assertEquals(73, toF(73.0, "F"))
    }

    @Test
    fun toF_convertsCelsius() {
        // Matches weather.py's own _to_f exactly: 23C -> 73F.
        assertEquals(73, toF(23.0, "C"))
    }

    private fun period(startTime: String, isDaytime: Boolean, tempF: Int) = JSONObject()
        .put("startTime", startTime)
        .put("isDaytime", isDaytime)
        .put("temperature", tempF)
        .put("temperatureUnit", "F")

    @Test
    fun todayHighLow_matchesPeriodsByRealDateNotPosition() {
        // Regression case for the exact bug weather.py's own
        // _today_high_low fixed: called in the evening, "whichever
        // daytime/nighttime period comes first" would silently return
        // TOMORROW's high, mislabeled as today's.
        val today = LocalDate.of(2026, 7, 13)
        val periods = JSONArray()
            .put(period("2026-07-13T18:00:00-04:00", isDaytime = false, tempF = 64))
            .put(period("2026-07-14T06:00:00-04:00", isDaytime = true, tempF = 90))

        val (high, low) = todayHighLow(periods, today)

        assertEquals(64, low)
        assertEquals(null, high) // today's daytime period already passed -- must NOT fall back to tomorrow's 90
    }

    @Test
    fun todayHighLow_returnsBothWhenPresent() {
        val today = LocalDate.of(2026, 7, 13)
        val periods = JSONArray()
            .put(period("2026-07-13T06:00:00-04:00", isDaytime = true, tempF = 85))
            .put(period("2026-07-13T18:00:00-04:00", isDaytime = false, tempF = 67))

        val (high, low) = todayHighLow(periods, today)

        assertEquals(85, high)
        assertEquals(67, low)
    }

    @Test
    fun todayHighLow_returnsNullsWhenNothingMatchesToday() {
        val today = LocalDate.of(2026, 7, 13)
        val periods = JSONArray()
            .put(period("2026-07-14T06:00:00-04:00", isDaytime = true, tempF = 90))

        val (high, low) = todayHighLow(periods, today)

        assertEquals(null, high)
        assertEquals(null, low)
    }

    @Test
    fun todayHighLow_skipsMalformedStartTimeInsteadOfCrashing() {
        val today = LocalDate.of(2026, 7, 13)
        val periods = JSONArray()
            .put(JSONObject().put("startTime", "not-a-date").put("isDaytime", true).put("temperature", 99).put("temperatureUnit", "F"))
            .put(period("2026-07-13T06:00:00-04:00", isDaytime = true, tempF = 85))

        val (high, low) = todayHighLow(periods, today)

        assertEquals(85, high)
        assertEquals(null, low)
    }

    @Test
    fun readCachedPhoneWeather_returnsFreshSuccessfulResult() {
        val context = RuntimeEnvironment.getApplication() as Context
        val prefs = context.getSharedPreferences(WidgetKeys.WEATHER_PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear()
            .putInt(WidgetKeys.KEY_WEATHER_TEMP_F, 64)
            .putInt(WidgetKeys.KEY_WEATHER_HIGH_F, 70)
            .putInt(WidgetKeys.KEY_WEATHER_LOW_F, 58)
            .putString(WidgetKeys.KEY_WEATHER_CONDITION, "Sunny")
            .putLong(WidgetKeys.KEY_WEATHER_FETCHED_AT, 1_000L)
            .apply()

        val weather = readCachedPhoneWeather(context, nowMillis = 1_000L + 30 * 60 * 1000L)

        assertEquals(64, weather?.temperatureF)
        assertEquals(70, weather?.highF)
        assertEquals(58, weather?.lowF)
        assertEquals("Sunny", weather?.condition)
    }

    @Test
    fun readCachedPhoneWeather_ignoresStaleSuccessfulResult() {
        val context = RuntimeEnvironment.getApplication() as Context
        val prefs = context.getSharedPreferences(WidgetKeys.WEATHER_PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear()
            .putInt(WidgetKeys.KEY_WEATHER_TEMP_F, 64)
            .putLong(WidgetKeys.KEY_WEATHER_FETCHED_AT, 1_000L)
            .apply()

        val weather = readCachedPhoneWeather(context, nowMillis = 1_000L + 3 * 60 * 60 * 1000L)

        assertNull(weather)
    }

    @Test
    fun readCachedPhoneWeather_readsLegacyCacheUsingLastFetchTimestamp() {
        val context = RuntimeEnvironment.getApplication() as Context
        val prefs = context.getSharedPreferences(WidgetKeys.WEATHER_PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear()
            .putInt(WidgetKeys.KEY_WEATHER_TEMP_F, 64)
            .putLong(WidgetKeys.KEY_LAST_WEATHER_FETCH_AT, 1_000L)
            .apply()

        val weather = readCachedPhoneWeather(context, nowMillis = 1_000L + 30 * 60 * 1000L)

        assertEquals(64, weather?.temperatureF)
    }
}
