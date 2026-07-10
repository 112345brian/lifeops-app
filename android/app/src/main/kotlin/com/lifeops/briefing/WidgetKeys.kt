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
    // (immediate update from a complete response), read by
    // BriefingWidget.provideGlance.
    val NEXT_TASKS_JSON = stringPreferencesKey("next_tasks_json") // NextTasksState serialized as JSON, or absent if never fetched

    // App-level SharedPreferences -- written by the settings screen.
    const val CONFIG_PREFS_NAME = "lifeops_widget_config"
    const val KEY_BASE_URL = "base_url" // e.g. "https://my-tailscale-host:8765" -- no trailing slash
    const val KEY_TOKEN = "token" // WEB_TOKEN value, appended as ?token= on next-tasks/complete calls
}
