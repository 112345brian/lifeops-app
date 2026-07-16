package com.lifeops.briefing

import android.content.Context
import android.util.Log
import androidx.glance.appwidget.GlanceAppWidgetManager
import com.lifeops.briefing.data.WeatherInfo
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import org.json.JSONArray
import org.json.JSONObject

/**
 * Phone-side NOAA/NWS fetch -- entirely independent of the LifeOps server.
 * Mirrors lifeops/weather.py's own two-step points->forecast lookup (same
 * public, no-API-key api.weather.gov endpoints, same grid-URL caching, same
 * "match today's high/low by real date, not position" logic), reimplemented
 * here so the widget's weather doesn't depend on the PC being on, reachable,
 * or even configured at all (2026-07-15: "if the server goes down, we'd
 * want the widget to update regardless" -- true for gym/tasks, which only
 * ever exist on the PC via FlowSavvy/YNAB, but NOT true for weather, which
 * is a public API the phone can reach on its own internet connection).
 *
 * Uses the phone's own last-reported GPS fix (see LocationReporter.kt,
 * persisted locally regardless of whether the panel is configured) --
 * silently no-ops if there's no fix yet. Gated to MIN_INTERVAL_MS,
 * piggybacked on NextTasksRefreshWorker's existing periodic cycle, same
 * shape as LocationReporter's own gate -- NOAA data doesn't change fast
 * enough to justify fetching on every 15-min tick.
 */
internal suspend fun reportWeatherIfDue(context: Context) {
    val locationPrefs = context.getSharedPreferences(WidgetKeys.LOCATION_PREFS_NAME, Context.MODE_PRIVATE)
    if (!locationPrefs.contains(WidgetKeys.KEY_LAST_LAT) || !locationPrefs.contains(WidgetKeys.KEY_LAST_LON)) {
        return // no GPS fix yet -- nothing to fetch weather FOR
    }
    val lat = locationPrefs.getFloat(WidgetKeys.KEY_LAST_LAT, 0f).toDouble()
    val lon = locationPrefs.getFloat(WidgetKeys.KEY_LAST_LON, 0f).toDouble()

    val prefs = context.getSharedPreferences(WidgetKeys.WEATHER_PREFS_NAME, Context.MODE_PRIVATE)
    val now = System.currentTimeMillis()
    if (now - prefs.getLong(WidgetKeys.KEY_LAST_WEATHER_FETCH_AT, 0L) < MIN_INTERVAL_MS) return
    // Stamp the gate before attempting -- a flaky/failed fetch (network
    // down, NOAA unreachable) must not turn into a retry-every-15-min loop,
    // same reasoning as LocationReporter's own gate.
    prefs.edit().putLong(WidgetKeys.KEY_LAST_WEATHER_FETCH_AT, now).apply()

    try {
        val (hourlyUrl, dailyUrl) = forecastUrls(prefs, lat, lon)
        val hourlyPeriods = JSONObject(fetch(hourlyUrl))
            .getJSONObject("properties").getJSONArray("periods")
        val nowPeriod = hourlyPeriods.getJSONObject(0)
        val tempF = toF(nowPeriod.getDouble("temperature"), nowPeriod.optString("temperatureUnit", "F"))
        val condition = nowPeriod.optString("shortForecast").takeIf { it.isNotEmpty() }

        val dailyPeriods = JSONObject(fetch(dailyUrl))
            .getJSONObject("properties").getJSONArray("periods")
        val (highF, lowF) = todayHighLow(dailyPeriods)

        val editor = prefs.edit()
            .putInt(WidgetKeys.KEY_WEATHER_TEMP_F, tempF)
            .putLong(WidgetKeys.KEY_WEATHER_FETCHED_AT, now)
        if (highF != null) editor.putInt(WidgetKeys.KEY_WEATHER_HIGH_F, highF) else editor.remove(WidgetKeys.KEY_WEATHER_HIGH_F)
        if (lowF != null) editor.putInt(WidgetKeys.KEY_WEATHER_LOW_F, lowF) else editor.remove(WidgetKeys.KEY_WEATHER_LOW_F)
        if (condition != null) editor.putString(WidgetKeys.KEY_WEATHER_CONDITION, condition) else editor.remove(WidgetKeys.KEY_WEATHER_CONDITION)
        editor.apply()

        // Push the fresh reading to every placed instance now, rather than
        // waiting for something else to trigger a recomposition -- this is
        // the only writer of this cache, so nothing else would. GlanceAppWidget
        // has no updateAll(); update() takes a specific GlanceId, so every
        // placed instance of this widget class needs its own call, same
        // pattern as NextTasksRefreshWorker's applyToAllInstances.
        val widget = BriefingWidget()
        for (glanceId in GlanceAppWidgetManager(context).getGlanceIds(BriefingWidget::class.java)) {
            widget.update(context, glanceId)
        }
    } catch (e: Exception) {
        Log.w(TAG, "phone-side weather fetch failed", e)
    }
}

/** The phone-side NOAA reading, only while it is fresh enough to outrank the
 * server-side nextTasks.weather fallback. A failed fetch stamps
 * KEY_LAST_WEATHER_FETCH_AT so we don't hammer NOAA, but deliberately does
 * not update KEY_WEATHER_FETCHED_AT; using the successful-result timestamp
 * here prevents one old local value (e.g. yesterday's 64F) from pinning the
 * widget forever ahead of fresher server weather. */
internal fun readCachedPhoneWeather(
    context: Context,
    nowMillis: Long = System.currentTimeMillis(),
): WeatherInfo? {
    val prefs = context.getSharedPreferences(WidgetKeys.WEATHER_PREFS_NAME, Context.MODE_PRIVATE)
    if (!prefs.contains(WidgetKeys.KEY_WEATHER_TEMP_F)) return null
    val fetchedAt = prefs.getLong(
        WidgetKeys.KEY_WEATHER_FETCHED_AT,
        prefs.getLong(WidgetKeys.KEY_LAST_WEATHER_FETCH_AT, 0L),
    )
    if (fetchedAt <= 0L || nowMillis - fetchedAt > MAX_CACHE_AGE_MS) return null
    return WeatherInfo(
        temperatureF = prefs.getInt(WidgetKeys.KEY_WEATHER_TEMP_F, 0),
        highF = if (prefs.contains(WidgetKeys.KEY_WEATHER_HIGH_F)) prefs.getInt(WidgetKeys.KEY_WEATHER_HIGH_F, 0) else null,
        lowF = if (prefs.contains(WidgetKeys.KEY_WEATHER_LOW_F)) prefs.getInt(WidgetKeys.KEY_WEATHER_LOW_F, 0) else null,
        condition = prefs.getString(WidgetKeys.KEY_WEATHER_CONDITION, null),
    )
}

/** The /points/{lat},{lon} -> forecast URL mapping is static for a fixed
 * location, so it's cached (keyed by a rounded "lat,lon" string) after the
 * first lookup instead of re-resolving on every fetch -- same reasoning as
 * weather.py's own _forecast_urls/grid cache. 4 decimal places (~11m) is
 * comfortably finer than a phone's realistic GPS drift, so a stationary
 * phone reliably reuses the cached grid instead of re-resolving due to
 * float jitter. */
private fun forecastUrls(prefs: android.content.SharedPreferences, lat: Double, lon: Double): Pair<String, String> {
    val key = "%.4f,%.4f".format(lat, lon)
    val cachedKey = prefs.getString(WidgetKeys.KEY_WEATHER_GRID_KEY, null)
    val cachedHourly = prefs.getString(WidgetKeys.KEY_WEATHER_HOURLY_URL, null)
    val cachedDaily = prefs.getString(WidgetKeys.KEY_WEATHER_DAILY_URL, null)
    if (key == cachedKey && cachedHourly != null && cachedDaily != null) {
        return cachedHourly to cachedDaily
    }
    val points = JSONObject(fetch("https://api.weather.gov/points/$lat,$lon")).getJSONObject("properties")
    val hourly = points.getString("forecastHourly")
    val daily = points.getString("forecast")
    prefs.edit()
        .putString(WidgetKeys.KEY_WEATHER_GRID_KEY, key)
        .putString(WidgetKeys.KEY_WEATHER_HOURLY_URL, hourly)
        .putString(WidgetKeys.KEY_WEATHER_DAILY_URL, daily)
        .apply()
    return hourly to daily
}

private fun fetch(url: String): String = httpRequest(
    url = url,
    method = "GET",
    headers = mapOf("User-Agent" to "lifeops-app-android (personal use)", "Accept" to "application/geo+json"),
)

internal fun toF(temp: Double, unit: String): Int =
    if (unit == "C") Math.round(temp * 9 / 5 + 32).toInt() else Math.round(temp).toInt()

/** The daily forecast alternates day/night 12h periods. Matches periods
 * against [today]'s REAL date via each period's own startTime rather than
 * just taking "whichever daytime/nighttime period comes first" -- same fix
 * (and same reasoning) as weather.py's _today_high_low: called in the
 * evening, "whichever comes first" would silently return TOMORROW's high,
 * mislabeled as today's. [today] defaults to the real current date; a
 * parameter (not a bare LocalDate.now() call inside) so this is testable
 * with a fixed date instead of whatever day the test happens to run on. */
internal fun todayHighLow(periods: JSONArray, today: LocalDate = LocalDate.now()): Pair<Int?, Int?> {
    var high: Int? = null
    var low: Int? = null
    for (i in 0 until periods.length()) {
        val p = periods.getJSONObject(i)
        val startTime = p.optString("startTime").takeIf { it.isNotEmpty() } ?: continue
        val date = try {
            OffsetDateTime.parse(startTime).toLocalDate()
        } catch (e: DateTimeParseException) {
            continue
        }
        if (date != today) continue
        val isDaytime = p.optBoolean("isDaytime")
        val tempF = toF(p.getDouble("temperature"), p.optString("temperatureUnit", "F"))
        if (isDaytime && high == null) high = tempF
        if (!isDaytime && low == null) low = tempF
    }
    return high to low
}

// ~45 min: fresh enough that the temperature never looks stuck for the
// afternoon (the original complaint), rare enough not to hammer a free
// public API with no real benefit -- NOAA's own hourly forecast doesn't
// change faster than this anyway.
private const val MIN_INTERVAL_MS = 45 * 60 * 1000L
private const val MAX_CACHE_AGE_MS = 2 * 60 * 60 * 1000L
private const val TAG = "PhoneWeather"
