package com.lifeops.briefing

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import java.io.IOException
import java.net.HttpURLConnection
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject

/**
 * Best-effort phone-location report, piggybacked on
 * [NextTasksRefreshWorker]'s existing 15-minute periodic cycle rather than
 * scheduling its own timer -- same reasoning as
 * [NextTasksRefreshWorker.revertExpiredPendingCompletions]. Gated to
 * [MIN_INTERVAL_MS] so this is a handful of one-shot GPS fixes a day (the
 * thing the user actually asked for), not a fix on every 15-min tick --
 * that's the whole battery story here: one balanced-power
 * [Priority.PRIORITY_BALANCED_POWER_ACCURACY] fix every few hours, no
 * continuous tracking, no foreground service.
 *
 * Silently no-ops (not an error) if the location permission hasn't been
 * granted (see SettingsScreen's request flow) or a fix fails for any
 * reason -- weather.py already falls back to the static WEATHER_LAT/LON
 * when no phone report is on file, so there's nothing here worth failing
 * the whole worker over.
 */
internal suspend fun reportLocationIfDue(context: Context) {
    if (!hasLocationPermission(context)) return

    val prefs = context.getSharedPreferences(WidgetKeys.LOCATION_PREFS_NAME, Context.MODE_PRIVATE)
    val lastReportAt = prefs.getLong(WidgetKeys.KEY_LAST_LOCATION_REPORT_AT, 0L)
    val now = System.currentTimeMillis()
    if (now - lastReportAt < MIN_INTERVAL_MS) return

    val fix = try {
        fetchOneShotLocation(context)
    } catch (e: Exception) {
        // SecurityException (permission revoked between the check above and
        // the call), or the fused client itself failing -- either way, this
        // is the expensive/battery-relevant step, so stamp the gate on ANY
        // outcome (success or failure) rather than just success, so a
        // flaky/denied fix doesn't turn into a retry-every-15-min loop.
        Log.w(TAG, "location fetch failed", e)
        null
    }
    prefs.edit().putLong(WidgetKeys.KEY_LAST_LOCATION_REPORT_AT, now).apply()
    fix ?: return

    // Persisted locally FIRST, unconditionally -- PhoneWeather reads this
    // directly (see PhoneWeather.kt), so a phone-side weather fetch has
    // ZERO dependency on the panel being configured, reachable, or even
    // set up at all (2026-07-15: "if the server goes down, we'd want the
    // widget to update regardless"). The POST below is a separate,
    // best-effort concern for the server's own use of the phone's location
    // (weather.py's fallback path), not a prerequisite for this.
    prefs.edit()
        .putFloat(WidgetKeys.KEY_LAST_LAT, fix.first.toFloat())
        .putFloat(WidgetKeys.KEY_LAST_LON, fix.second.toFloat())
        .apply()

    val baseUrl = WidgetConfigStore.getBaseUrl(context)
    val token = WidgetConfigStore.getToken(context)
    if (baseUrl == null || token == null) return
    try {
        postLocation(baseUrl, token, fix.first, fix.second)
    } catch (e: IOException) {
        Log.w(TAG, "error posting location", e)
    }
}

private fun hasLocationPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED ||
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

/** One-shot fix (not a subscription/continuous update) via
 * FusedLocationProviderClient.getCurrentLocation -- the battery-cheap
 * primitive for "where is the phone right now", as opposed to
 * requestLocationUpdates' continuous-tracking API. Returns null if the
 * platform can't produce a fix (GPS/network location off, no recent-enough
 * signal). */
private suspend fun fetchOneShotLocation(context: Context): Pair<Double, Double>? =
    suspendCancellableCoroutine { cont ->
        val client = LocationServices.getFusedLocationProviderClient(context)
        val cancellationSource = CancellationTokenSource()
        cont.invokeOnCancellation { cancellationSource.cancel() }
        val request = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_BALANCED_POWER_ACCURACY)
            .build()
        try {
            client.getCurrentLocation(request, cancellationSource.token)
                .addOnSuccessListener { loc ->
                    cont.resumeWith(Result.success(loc?.let { it.latitude to it.longitude }))
                }
                .addOnFailureListener { e -> cont.resumeWith(Result.failure(e)) }
        } catch (e: SecurityException) {
            cont.resumeWith(Result.failure(e))
        }
    }

private fun postLocation(baseUrl: String, token: String, lat: Double, lon: Double) {
    val body = JSONObject().put("lat", lat).put("lon", lon).toString()
    httpRequest(
        url = authenticatedUrl(baseUrl, "/api/location", token),
        method = "POST",
        body = body,
        requireExactCode = HttpURLConnection.HTTP_OK,
    )
}

// ~5h: comfortably lands 2-3 reports across a waking day off the existing
// 15-min worker cadence, without needing a dedicated shorter-period job.
private const val MIN_INTERVAL_MS = 5 * 60 * 60 * 1000L
private const val TAG = "LocationReporter"
