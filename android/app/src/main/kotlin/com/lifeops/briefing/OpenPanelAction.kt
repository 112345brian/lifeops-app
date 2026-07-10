package com.lifeops.briefing

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback

/**
 * Fired when the user taps the widget body (outside the refresh affordance)
 * to jump into the full LifeOps control panel.
 *
 * If the widget hasn't been configured yet (no base URL saved), opens
 * [SettingsActivity] instead so the user can set one up. Otherwise deep-links
 * into the panel's briefing card using the same "#briefing" URL-fragment
 * convention the server's ntfy alerts already use via panel_url() in
 * lifeops/ntfy.py.
 *
 * Glance action callbacks run outside of an Activity context, so any Intent
 * that starts an Activity needs FLAG_ACTIVITY_NEW_TASK.
 */
class OpenPanelAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val baseUrl = WidgetConfigStore.getBaseUrl(context)
        val intent = if (baseUrl == null) {
            Intent(context, SettingsActivity::class.java)
        } else {
            Intent(Intent.ACTION_VIEW, Uri.parse("$baseUrl/#briefing"))
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
