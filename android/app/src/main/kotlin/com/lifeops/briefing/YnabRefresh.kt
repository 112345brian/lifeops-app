package com.lifeops.briefing

import android.content.Context
import android.util.Log
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import com.lifeops.briefing.data.BriefingState
import com.lifeops.briefing.data.YnabCategoryBalance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlin.math.roundToInt

internal suspend fun refreshYnabCategoriesIfConfigured(context: Context, force: Boolean = false) = withContext(Dispatchers.IO) {
    val gatePrefs = context.getSharedPreferences(WidgetKeys.YNAB_REFRESH_PREFS_NAME, Context.MODE_PRIVATE)
    val now = System.currentTimeMillis()
    gatePrefs.edit()
        .putLong(WidgetKeys.KEY_LAST_YNAB_REFRESH_ATTEMPT_AT, now)
        .apply()
    val token = WidgetConfigStore.getYnabToken(context)
    if (token == null) {
        gatePrefs.edit()
            .putString(WidgetKeys.KEY_LAST_YNAB_REFRESH_STATUS, "no_token")
            .putInt(WidgetKeys.KEY_LAST_YNAB_REFRESH_COUNT, 0)
            .apply()
        Log.i("YnabRefresh", "skipping YNAB category refresh: no local API token configured")
        return@withContext
    }
    val budget = WidgetConfigStore.getYnabBudget(context)

    // Self-gate like PhoneWeather's reportWeatherIfDue: category balances
    // don't change fast enough to justify hitting the YNAB API on every
    // 15-min periodic-worker tick.
    if (!force && now - gatePrefs.getLong(WidgetKeys.KEY_LAST_YNAB_REFRESH_AT, 0L) < MIN_REFRESH_INTERVAL_MS) {
        gatePrefs.edit()
            .putString(WidgetKeys.KEY_LAST_YNAB_REFRESH_STATUS, "cached")
            .apply()
        Log.i("YnabRefresh", "skipping YNAB category refresh: cached result is still fresh")
        return@withContext
    }

    val balances = try {
        fetchYnabCategoryBalances(token, budget)
    } catch (e: Exception) {
        gatePrefs.edit()
            .putString(WidgetKeys.KEY_LAST_YNAB_REFRESH_STATUS, "error")
            .putString(WidgetKeys.KEY_LAST_YNAB_REFRESH_ERROR, "${e::class.java.simpleName}: ${e.message}")
            .putInt(WidgetKeys.KEY_LAST_YNAB_REFRESH_COUNT, 0)
            .apply()
        Log.e("YnabRefresh", "error fetching YNAB category balances", e)
        return@withContext
    }
    gatePrefs.edit()
        .putLong(WidgetKeys.KEY_LAST_YNAB_REFRESH_AT, now)
        .putString(WidgetKeys.KEY_LAST_YNAB_REFRESH_STATUS, "success")
        .remove(WidgetKeys.KEY_LAST_YNAB_REFRESH_ERROR)
        .putInt(WidgetKeys.KEY_LAST_YNAB_REFRESH_COUNT, balances.size)
        .apply()
    Log.i("YnabRefresh", "refreshed ${balances.size} YNAB category balances")
    // Mirrors lifeops/gather.py's fun_money: the sum of every category whose
    // name is in the (server-mirrored, user-configurable) discretionary set
    // -- not a single hardcoded category -- so this phone-side refresh can't
    // silently disagree with the server-pushed figure for the same budget.
    val discretionaryNames = WidgetConfigStore.getYnabDiscretionaryCategories(context)
    val discretionarySum = balances.filter { it.name.lowercase() in discretionaryNames }
        .sumOf { it.dollars }

    val manager = GlanceAppWidgetManager(context)
    for (glanceId in manager.getGlanceIds(BriefingWidget::class.java)) {
        val current = try {
            getAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId)[WidgetKeys.BRIEFING_JSON]
                ?.let { BriefingState.fromJson(it) }
        } catch (e: Exception) {
            null
        } ?: BriefingState.empty()
        val updated = current.copy(
            ynabCategoryBalances = balances,
            discretionaryCurrentDollars = if (discretionaryNames.isNotEmpty()) {
                discretionarySum
            } else {
                current.discretionaryCurrentDollars
            },
            fetchedAtEpochMillis = System.currentTimeMillis(),
        )
        updateAppWidgetState(context, glanceId) { prefs ->
            prefs[WidgetKeys.BRIEFING_JSON] = updated.toJson()
            updated.fetchedAtEpochMillis?.let { prefs[WidgetKeys.LAST_FETCHED_AT] = it }
        }
        BriefingWidget().update(context, glanceId)
    }
}

private fun fetchYnabCategoryBalances(token: String, budget: String): List<YnabCategoryBalance> {
    val encodedBudget = URLEncoder.encode(budget, StandardCharsets.UTF_8.name())
    val url = "https://api.ynab.com/v1/budgets/$encodedBudget/months/current"
    val body = httpRequest(
        url = url,
        method = "GET",
        headers = mapOf("Authorization" to "Bearer $token"),
        connectTimeoutMs = 10_000,
        readTimeoutMs = 15_000,
        requireExactCode = HttpURLConnection.HTTP_OK,
    )
    val categories = JSONObject(body).getJSONObject("data").getJSONObject("month").getJSONArray("categories")
    return (0 until categories.length()).mapNotNull { i ->
        val category = categories.getJSONObject(i)
        if (category.optBoolean("hidden", false) || category.optBoolean("deleted", false)) return@mapNotNull null
        val name = category.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
        // Matches lifeops/gather.py's round() on the same milliunit balance
        // -- truncating toward zero here would both disagree with the
        // server's figure for the same category and mask a small negative
        // (over-budget) balance as $0.
        YnabCategoryBalance(name = name, dollars = (category.optInt("balance") / 1000.0).roundToInt())
    }.sortedBy { it.name.lowercase() }
}

private const val MIN_REFRESH_INTERVAL_MS = 45 * 60 * 1000L
