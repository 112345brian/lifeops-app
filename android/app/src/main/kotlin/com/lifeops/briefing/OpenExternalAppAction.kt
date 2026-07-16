package com.lifeops.briefing

import android.content.Context
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Build
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import com.lifeops.briefing.data.WidgetDisplayConfig

class OpenExternalAppAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val target = parameters[TARGET_KEY] ?: return
        val packageName = parameters[PACKAGE_KEY]?.takeIf { it.isNotBlank() }
        var packageNameForFallback: String? = null
        val intent = when (target) {
            TARGET_MONEY -> {
                packageNameForFallback = packageName ?: WidgetDisplayConfig.DEFAULT_MONEY_APP_PACKAGE
                // Tapping through to YNAB is the strongest signal we have that
                // the user is about to (or just did) change a category balance
                // there -- schedule a forced refresh a couple minutes out so
                // the widget picks it up without waiting on YnabRefresh's
                // normal 45-min gate, instead of requiring a manual config-
                // screen Save to see the update.
                enqueueDelayedYnabRefresh(context)
                appOrStoreIntent(context, packageNameForFallback)
            }
            TARGET_GYM -> {
                packageNameForFallback = packageName ?: WidgetDisplayConfig.DEFAULT_GYM_APP_PACKAGE
                appOrStoreIntent(context, packageNameForFallback)
            }
            TARGET_WEATHER -> if (packageName != null) {
                packageNameForFallback = packageName
                appOrStoreIntent(context, packageName)
            } else {
                weatherIntent(context)
            }
            else -> null
        } ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            packageNameForFallback ?: return
            context.startActivity(storeIntent(packageNameForFallback).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun appOrStoreIntent(context: Context, packageName: String): Intent {
        val launchIntent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
        val activity = context.packageManager.queryLauncherActivities(launchIntent)
            .firstOrNull { it.activityInfo?.packageName == packageName }
            ?.activityInfo
        if (activity != null) {
            return Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(ComponentName(activity.packageName, activity.name))
        }
        return context.packageManager.getLaunchIntentForPackage(packageName)
            ?: storeIntent(packageName)
    }

    private fun storeIntent(packageName: String): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName"))

    private fun weatherIntent(context: Context): Intent {
        val prefs = context.getSharedPreferences(WidgetKeys.LOCATION_PREFS_NAME, Context.MODE_PRIVATE)
        val hasLocation = prefs.contains(WidgetKeys.KEY_LAST_LAT) && prefs.contains(WidgetKeys.KEY_LAST_LON)
        val url = if (hasLocation) {
            val lat = prefs.getFloat(WidgetKeys.KEY_LAST_LAT, 0f)
            val lon = prefs.getFloat(WidgetKeys.KEY_LAST_LON, 0f)
            "https://forecast.weather.gov/MapClick.php?lat=$lat&lon=$lon"
        } else {
            "https://www.weather.gov/"
        }
        return Intent(Intent.ACTION_VIEW, Uri.parse(url))
    }

    private fun PackageManager.queryLauncherActivities(intent: Intent): List<ResolveInfo> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            queryIntentActivities(intent, 0)
        }

    companion object {
        val TARGET_KEY = ActionParameters.Key<String>("externalTarget")
        val PACKAGE_KEY = ActionParameters.Key<String>("externalPackage")
        const val TARGET_MONEY = "money"
        const val TARGET_GYM = "gym"
        const val TARGET_WEATHER = "weather"
    }
}
