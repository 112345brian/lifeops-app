package com.lifeops.briefing

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

/**
 * Entry point the Android OS talks to for widget lifecycle events
 * (placed on home screen, resized, updated, removed, etc.).
 *
 * Registered in AndroidManifest.xml with the APPWIDGET_UPDATE intent-filter
 * and pointed at res/xml/briefing_widget_info.xml. The briefing itself is
 * push-only via BriefingReceiver (ntfy broadcasts) -- no scheduling needed
 * for that. The next-tasks list, however, is periodic pull (see
 * NextTasksRefreshWorker), scheduled here as soon as the first widget
 * instance is placed and cancelled once the last is removed. Per the
 * The briefing also arrives through FCM, but a periodic pull keeps the widget
 * self-healing when push is missed or the token is not registered yet. Per the
 * AppWidgetProvider/GlanceAppWidgetReceiver contract, onEnabled fires only
 * for the first instance placed and onDisabled only when the last instance
 * is removed -- both are no-ops for intermediate add/remove of additional
 * instances -- so this is safe to call unconditionally here.
 */
class BriefingWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = BriefingWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        NextTasksRefreshWorker.schedulePeriodic(context)
        // Don't make a freshly-placed widget wait up to 15 minutes (or an
        // FCM push that may never come, e.g. no token registered yet) for
        // its first content -- fire one immediate pull too.
        WorkManager.getInstance(context).enqueue(OneTimeWorkRequestBuilder<NextTasksRefreshWorker>().build())
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        WorkManager.getInstance(context).cancelUniqueWork(NextTasksRefreshWorker.UNIQUE_PERIODIC_WORK_NAME)
    }
}
