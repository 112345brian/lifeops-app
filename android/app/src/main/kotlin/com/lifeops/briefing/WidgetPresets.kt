package com.lifeops.briefing

import androidx.glance.appwidget.GlanceAppWidgetReceiver
import com.lifeops.briefing.data.WidgetDisplayConfig
import com.lifeops.briefing.data.WidgetSection

/** Single source of truth for every AppWidgetProvider receiver this app
 * registers and the [WidgetDisplayConfig] each one should start
 * pre-configured with. Three call sites consult this SAME map rather than
 * each hand-maintaining their own copy of the receiver-to-preset mapping
 * (they used to, and could silently drift out of sync):
 *  - BriefingWidget.presetDefaultConfig -- what a freshly-placed instance
 *    renders before anything's been saved.
 *  - WidgetConfigActivity -- what the configure screen shows pre-selected
 *    the first time it opens for that instance.
 *  - BriefingWidgetReceiver.kt's totalWidgetInstanceCount -- which
 *    providers' placed-instance counts to sum for the reference-counted
 *    next-tasks work scheduling.
 * Adding a new preset means adding ONE entry here (plus its receiver class,
 * manifest entry, and widget-info XML -- those still can't be avoided,
 * that's inherent to how AppWidgetProviderInfo works) and every consumer
 * picks it up automatically. */
object WidgetPresets {
    val RECEIVER_CONFIGS: Map<Class<out GlanceAppWidgetReceiver>, WidgetDisplayConfig> = mapOf(
        BriefingWidgetReceiver::class.java to WidgetDisplayConfig.default(),
        GymWidgetReceiver::class.java to WidgetDisplayConfig.singleStat(WidgetSection.GYM_RING),
        MoneyWidgetReceiver::class.java to WidgetDisplayConfig.singleStat(WidgetSection.MONEY_TILE),
        CourseworkWidgetReceiver::class.java to WidgetDisplayConfig.singleStat(WidgetSection.COURSEWORK_TILE),
        WeatherWidgetReceiver::class.java to WidgetDisplayConfig.singleStat(WidgetSection.WEATHER),
        SleepWidgetReceiver::class.java to WidgetDisplayConfig.singleStat(WidgetSection.SLEEP_TILE),
        SocialWidgetReceiver::class.java to WidgetDisplayConfig.singleStat(WidgetSection.SOCIAL),
        EventsWidgetReceiver::class.java to WidgetDisplayConfig.singleStat(WidgetSection.NOTABLE_EVENTS),
        ComboWidgetReceiver::class.java to WidgetDisplayConfig.comboGrid(),
    )

    fun defaultConfigFor(receiverClassName: String?): WidgetDisplayConfig =
        RECEIVER_CONFIGS.entries.firstOrNull { it.key.name == receiverClassName }?.value
            ?: WidgetDisplayConfig.default()
}
