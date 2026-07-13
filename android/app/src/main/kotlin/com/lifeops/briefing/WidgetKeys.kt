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
}
