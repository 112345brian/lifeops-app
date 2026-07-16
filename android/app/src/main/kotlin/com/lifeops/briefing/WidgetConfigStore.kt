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
    private fun open(context: Context) = EncryptedSharedPreferences.create(
        context,
        WidgetKeys.CONFIG_PREFS_NAME,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    // Defense in depth alongside data_extraction_rules.xml/backup_rules.xml:
    // if an undecryptable prefs file ever ends up on disk again (a restored
    // backup predating those rules, a corrupted write, etc.), the Keystore
    // throws on the FIRST read/write, not just once -- so without this the
    // app is permanently crash-looped until someone manually clears app data.
    // Wipe the file and start fresh instead of taking the whole app down.
    //
    // Nullable return: if the RETRY also throws (a genuinely broken
    // Keystore/StrongBox, not just a stale file -- rare but real on some
    // OEM devices), there's nothing more we can do here. Every caller
    // treats null as "not configured yet" rather than crashing -- this used
    // to propagate uncaught into SettingsActivity (the app's only launcher
    // activity), OpenPanelAction, NextTasksRefreshWorker, and
    // BriefingFcmService.onNewToken's bare coroutine launch, any one of
    // which crashing the whole process on a broken Keystore.
    private fun prefs(context: Context): android.content.SharedPreferences? = try {
        open(context)
    } catch (e: Exception) {
        try {
            context.deleteSharedPreferences(WidgetKeys.CONFIG_PREFS_NAME)
            open(context)
        } catch (e2: Exception) {
            null
        }
    }

    fun getBaseUrl(context: Context): String? =
        prefs(context)?.getString(WidgetKeys.KEY_BASE_URL, null)?.trimEnd('/')?.takeIf { it.isNotBlank() }

    fun getToken(context: Context): String? =
        prefs(context)?.getString(WidgetKeys.KEY_TOKEN, null)?.takeIf { it.isNotBlank() }

    fun getYnabToken(context: Context): String? =
        prefs(context)?.getString(WidgetKeys.KEY_YNAB_TOKEN, null)?.takeIf { it.isNotBlank() }

    fun getYnabBudget(context: Context): String =
        prefs(context)?.getString(WidgetKeys.KEY_YNAB_BUDGET, null)?.takeIf { it.isNotBlank() } ?: "last-used"

    // Mirrors lifeops/config.py's DISCRETIONARY env var default
    // ("Shopping,Entertainment,Eating Out,Shows,Splurge") so the phone-side
    // YnabRefresh sums the same categories the server does for
    // discretionary_current_dollars, instead of a single hardcoded name that
    // could silently diverge from the server's (possibly overridden) set.
    const val DEFAULT_YNAB_DISCRETIONARY_CATEGORIES = "Shopping,Entertainment,Eating Out,Shows,Splurge"

    // Raw, original-case string for round-tripping through the settings
    // screen's text field -- comparisons against category names should go
    // through [getYnabDiscretionaryCategories] instead, which normalizes.
    fun getYnabDiscretionaryCategoriesRaw(context: Context): String =
        prefs(context)?.getString(WidgetKeys.KEY_YNAB_DISCRETIONARY_CATEGORIES, null)
            ?.takeIf { it.isNotBlank() } ?: DEFAULT_YNAB_DISCRETIONARY_CATEGORIES

    fun getYnabDiscretionaryCategories(context: Context): List<String> =
        getYnabDiscretionaryCategoriesRaw(context)
            .split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }

    fun save(
        context: Context, baseUrl: String, token: String, ynabToken: String, ynabBudget: String,
        ynabDiscretionaryCategories: String,
    ) {
        prefs(context)?.edit()
            ?.putString(WidgetKeys.KEY_BASE_URL, baseUrl.trimEnd('/'))
            ?.putString(WidgetKeys.KEY_TOKEN, token)
            ?.putString(WidgetKeys.KEY_YNAB_TOKEN, ynabToken)
            ?.putString(WidgetKeys.KEY_YNAB_BUDGET, ynabBudget.ifBlank { "last-used" })
            ?.putString(
                WidgetKeys.KEY_YNAB_DISCRETIONARY_CATEGORIES,
                ynabDiscretionaryCategories.ifBlank { DEFAULT_YNAB_DISCRETIONARY_CATEGORIES },
            )
            ?.apply()
    }
}
