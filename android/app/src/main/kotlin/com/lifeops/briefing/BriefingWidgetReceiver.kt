package com.lifeops.briefing

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

/**
 * Entry point the Android OS talks to for widget lifecycle events
 * (placed on home screen, resized, updated, removed, etc.).
 *
 * [BriefingWidgetReceiver] is the original full/customizable widget. The
 * three single-stat receivers below (Gym/Money/Coursework) are separate
 * AppWidgetProvider entries -- each with its own widget-info XML under
 * res/xml (gym_widget_info.xml etc.) and android:label -- purely so they
 * show up as their own pickable presets in the OS widget tray (drag
 * straight to "just gym count" without going through the full widget's
 * configure screen first). They all render via the SAME plain, no-arg
 * [BriefingWidget]/[BriefingContent] composable -- BriefingWidget resolves
 * its own starting layout per-instance via [WidgetPresets], so no
 * per-receiver config needs to be threaded through here.
 *
 * The briefing itself is push-only via BriefingFcmService (FCM) / ntfy --
 * no scheduling needed for that. The next-tasks list (which also carries
 * gym-ring data), however, is periodic pull (see NextTasksRefreshWorker),
 * scheduled as soon as the first instance of ANY of these providers is
 * placed and cancelled only once the last instance across ALL of them is
 * removed -- see [totalWidgetInstanceCount]. A naive per-provider
 * onEnabled/onDisabled (correct when there was only one provider) would
 * cancel the periodic pull out from under, say, a still-placed Briefing
 * widget the moment its only Gym widget was removed.
 */
abstract class BaseBriefingWidgetReceiver : GlanceAppWidgetReceiver() {
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
        if (totalWidgetInstanceCount(context) == 0) {
            WorkManager.getInstance(context).cancelUniqueWork(NextTasksRefreshWorker.UNIQUE_PERIODIC_WORK_NAME)
        }
    }
}

class BriefingWidgetReceiver : BaseBriefingWidgetReceiver()
class GymWidgetReceiver : BaseBriefingWidgetReceiver()
class MoneyWidgetReceiver : BaseBriefingWidgetReceiver()
class CourseworkWidgetReceiver : BaseBriefingWidgetReceiver()
class WeatherWidgetReceiver : BaseBriefingWidgetReceiver()
class SleepWidgetReceiver : BaseBriefingWidgetReceiver()
class SocialWidgetReceiver : BaseBriefingWidgetReceiver()
class EventsWidgetReceiver : BaseBriefingWidgetReceiver()
class ComboWidgetReceiver : BaseBriefingWidgetReceiver()

private fun totalWidgetInstanceCount(context: Context): Int {
    val manager = AppWidgetManager.getInstance(context)
    return WidgetPresets.RECEIVER_CONFIGS.keys.sumOf { manager.getAppWidgetIds(ComponentName(context, it)).size }
}
