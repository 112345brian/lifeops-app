package com.lifeops.briefing

import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

/** Shared key names for the two persistence surfaces the widget uses -- fixed
 * here so the UI, the ntfy broadcast receiver, the next-tasks worker, and the
 * settings screen agree without needing to read each other's code. */
object WidgetKeys {
    // Glance per-widget state (PreferencesGlanceStateDefinition).
    // Written by BriefingReceiver on each ntfy "briefing-data" broadcast, read
    // by BriefingWidget.provideGlance.
    val BRIEFING_JSON = stringPreferencesKey("briefing_json") // BriefingState serialized as JSON, or absent if never received
    val LAST_FETCHED_AT = longPreferencesKey("last_fetched_at") // epoch millis the broadcast was received
    // Written by NextTasksRefreshWorker (periodic pull) and CompleteTaskAction
    // (optimistic local removal on tap), read by BriefingWidget.provideGlance.
    val NEXT_TASKS_JSON = stringPreferencesKey("next_tasks_json") // NextTasksState serialized as JSON, or absent if never fetched
    // {taskId: {tappedAt, title, start}} for tasks completed locally
    // (checkbox tap) but not yet confirmed reflected server-side. A full
    // refresh landing before the ~2-min ntfy->ingest completion cycle
    // catches up would otherwise silently resurrect a task the user just
    // checked off -- PendingRemovals filters these out of any fresh write
    // until they're confirmed complete, confirmed failed (past grace, still
    // present), or hit the ~10-min hard timeout and get restored to the
    // visible list from the stored title/start. See PendingRemovals.kt.
    val PENDING_REMOVED_JSON = stringPreferencesKey("pending_removed_json")
    // Written by WidgetConfigActivity (the per-instance widget-configure
    // screen), read by BriefingWidget.provideGlance. WidgetDisplayConfig
    // serialized as JSON, or absent if this instance has never been
    // configured (falls back to WidgetDisplayConfig.default()).
    val DISPLAY_CONFIG_JSON = stringPreferencesKey("display_config_json")

    // App-level SharedPreferences -- written by the settings screen.
    const val CONFIG_PREFS_NAME = "lifeops_widget_config"
    const val KEY_BASE_URL = "base_url" // e.g. "https://my-tailscale-host:8765" -- no trailing slash
    const val KEY_TOKEN = "token" // WEB_TOKEN value, appended as ?token= on next-tasks calls
    const val KEY_YNAB_TOKEN = "ynab_token"
    const val KEY_YNAB_BUDGET = "ynab_budget" // "last-used" is accepted by YNAB
    const val KEY_YNAB_DISCRETIONARY_CATEGORIES = "ynab_discretionary_categories" // comma-separated, mirrors server's DISCRETIONARY env var

    // Separate, unencrypted app-level SharedPreferences for YnabRefresh's
    // report-cadence gate -- just a timestamp, not a credential, same
    // reasoning as LOCATION_PREFS_NAME/WEATHER_PREFS_NAME below.
    const val YNAB_REFRESH_PREFS_NAME = "lifeops_ynab_refresh_gate"
    const val KEY_LAST_YNAB_REFRESH_AT = "last_ynab_refresh_at" // epoch millis, 0 = never
    const val KEY_LAST_YNAB_REFRESH_ATTEMPT_AT = "last_ynab_refresh_attempt_at"
    const val KEY_LAST_YNAB_REFRESH_STATUS = "last_ynab_refresh_status"
    const val KEY_LAST_YNAB_REFRESH_COUNT = "last_ynab_refresh_count"

    // Separate, unencrypted app-level SharedPreferences for LocationReporter's
    // report-cadence gate -- just a timestamp, not a credential, so it
    // doesn't need CONFIG_PREFS_NAME's EncryptedSharedPreferences machinery.
    const val LOCATION_PREFS_NAME = "lifeops_location_gate"
    const val KEY_LAST_LOCATION_REPORT_AT = "last_location_report_at" // epoch millis, 0 = never
    // The phone's own last GPS fix, persisted here regardless of whether the
    // panel is configured/reachable -- PhoneWeather.kt reads these directly
    // so its NOAA fetch has zero server dependency. Absent = no fix yet.
    const val KEY_LAST_LAT = "last_lat"
    const val KEY_LAST_LON = "last_lon"

    // Separate app-level SharedPreferences for PhoneWeather's own fetch-cadence
    // gate and cached result -- kept apart from LOCATION_PREFS_NAME since it's
    // a conceptually distinct cache (NOAA grid/forecast), not a location gate.
    const val WEATHER_PREFS_NAME = "lifeops_phone_weather"
    const val KEY_LAST_WEATHER_FETCH_AT = "last_weather_fetch_at" // epoch millis, 0 = never
    const val KEY_WEATHER_GRID_KEY = "weather_grid_key" // "<lat>,<lon>" the cached URLs below were resolved for
    const val KEY_WEATHER_HOURLY_URL = "weather_hourly_url"
    const val KEY_WEATHER_DAILY_URL = "weather_daily_url"
    const val KEY_WEATHER_TEMP_F = "weather_temp_f"
    const val KEY_WEATHER_HIGH_F = "weather_high_f"
    const val KEY_WEATHER_LOW_F = "weather_low_f"
    const val KEY_WEATHER_CONDITION = "weather_condition"
    const val KEY_WEATHER_FETCHED_AT = "weather_fetched_at" // epoch millis of the cached result above
}
