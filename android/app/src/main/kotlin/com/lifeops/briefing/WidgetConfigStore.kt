package com.lifeops.briefing

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Single place that opens the EncryptedSharedPreferences holding the panel
 * base URL and auth token -- OpenPanelAction/NextTasksRefreshWorker/
 * CompleteTaskAction (read) and the settings screen (write) all go through
 * this so they can't disagree on how it's stored. Encrypted because the
 * token is a real credential (WEB_TOKEN) used for the next-tasks pull/
 * complete calls. */
object WidgetConfigStore {
    private fun prefs(context: Context) = EncryptedSharedPreferences.create(
        context,
        WidgetKeys.CONFIG_PREFS_NAME,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun getBaseUrl(context: Context): String? =
        prefs(context).getString(WidgetKeys.KEY_BASE_URL, null)?.trimEnd('/')?.takeIf { it.isNotBlank() }

    fun getToken(context: Context): String? =
        prefs(context).getString(WidgetKeys.KEY_TOKEN, null)?.takeIf { it.isNotBlank() }

    fun save(context: Context, baseUrl: String, token: String) {
        prefs(context).edit()
            .putString(WidgetKeys.KEY_BASE_URL, baseUrl.trimEnd('/'))
            .putString(WidgetKeys.KEY_TOKEN, token)
            .apply()
    }
}
