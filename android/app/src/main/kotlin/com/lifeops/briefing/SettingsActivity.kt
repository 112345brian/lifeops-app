package com.lifeops.briefing

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Settings screen: panel base URL (used by [OpenPanelAction]'s tap-to-open
 * deep link, and as the target for FCM-token registration/next-tasks calls)
 * and auth token (used by [NextTasksRefreshWorker]/[CompleteTaskAction] and
 * FCM-token registration -- the briefing push itself needs neither, Firebase
 * handles delivery). Also doubles as the app's only launcher activity, since
 * the widget itself has no other UI entry point. */
class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.hide()
        if (intent.getBooleanExtra(EXTRA_FORCE_YNAB_REFRESH, false)) {
            lifecycleScope.launch {
                WidgetConfigStore.importYnabConfigFileIfPresent(this@SettingsActivity)
                refreshYnabCategoriesIfConfigured(this@SettingsActivity, force = true)
                Toast.makeText(this@SettingsActivity, "Refreshed YNAB", Toast.LENGTH_SHORT).show()
                finish()
            }
            return
        }
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SettingsScreen(
                        context = this,
                        onSaved = { finish() },
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(context: Context, onSaved: () -> Unit) {
    var baseUrl by remember { mutableStateOf(WidgetConfigStore.getBaseUrl(context) ?: "") }
    var token by remember { mutableStateOf(WidgetConfigStore.getToken(context) ?: "") }
    var ynabToken by remember { mutableStateOf(WidgetConfigStore.getYnabToken(context) ?: "") }
    var ynabBudget by remember { mutableStateOf(WidgetConfigStore.getYnabBudget(context)) }
    var ynabDiscretionaryCategories by remember {
        mutableStateOf(WidgetConfigStore.getYnabDiscretionaryCategoriesRaw(context))
    }
    var hasForegroundLocation by remember { mutableStateOf(hasForegroundLocationPermission(context)) }
    val scope = rememberCoroutineScope()

    // Fine+coarse can be requested together in one runtime dialog; background
    // location cannot be bundled into that same request on API 30+ (the OS
    // rejects it), so it's a separate step below once foreground is granted.
    val foregroundLocationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        hasForegroundLocation = granted.values.any { it }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(text = "Widget settings", style = MaterialTheme.typography.titleLarge)

        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            label = { Text("Panel URL") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            label = { Text("Auth token") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = ynabToken,
            onValueChange = { ynabToken = it },
            label = { Text("YNAB API token") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = ynabBudget,
            onValueChange = { ynabBudget = it },
            label = { Text("YNAB budget ID") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = ynabDiscretionaryCategories,
            onValueChange = { ynabDiscretionaryCategories = it },
            label = { Text("Discretionary categories (comma-separated)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        // LocationReporter (piggybacked on NextTasksRefreshWorker) silently
        // no-ops without this permission, so surface it explicitly here
        // rather than leaving weather stuck on the static WEATHER_LAT/LON
        // with no way to tell why.
        Text(
            text = if (hasForegroundLocation) {
                "Location: granted. For background reports, allow \"All the time\" " +
                    "in the system location settings below."
            } else {
                "Location permission lets weather follow your phone instead of a fixed spot."
            },
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(
            onClick = {
                if (hasForegroundLocation) {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            .setData(Uri.fromParts("package", context.packageName, null)),
                    )
                } else {
                    foregroundLocationLauncher.launch(
                        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (hasForegroundLocation) "Open location settings" else "Grant location access")
        }

        Button(
            onClick = {
                WidgetConfigStore.save(
                    context, baseUrl.trim(), token.trim(), ynabToken.trim(), ynabBudget.trim(),
                    ynabDiscretionaryCategories.trim(),
                )
                WorkManager.getInstance(context)
                    .enqueue(OneTimeWorkRequestBuilder<NextTasksRefreshWorker>().build())
                // Register the current FCM token now too -- covers the
                // common case where Settings gets configured after
                // BriefingFcmService.onNewToken already fired once (e.g.
                // right after install, before a panel URL/token existed).
                scope.launch {
                    val fcmToken = try {
                        FirebaseMessaging.getInstance().token.await()
                    } catch (e: Exception) {
                        null
                    }
                    if (fcmToken != null) {
                        withContext(Dispatchers.IO) { registerToken(context, fcmToken) }
                    }
                }
                Toast.makeText(context, "Saved", Toast.LENGTH_SHORT).show()
                onSaved()
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Save")
        }

        OutlinedButton(
            onClick = {
                scope.launch {
                    WidgetConfigStore.importYnabConfigFileIfPresent(context)
                    refreshYnabCategoriesIfConfigured(context, force = true)
                    Toast.makeText(context, "Refreshed YNAB", Toast.LENGTH_SHORT).show()
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Refresh YNAB now")
        }
    }
}

private const val EXTRA_FORCE_YNAB_REFRESH = "forceYnabRefresh"

private fun hasForegroundLocationPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED ||
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

/** Small local await() for a Firebase Task, avoiding a dependency on
 * kotlinx-coroutines-play-services just for this one call. */
private suspend fun <T> com.google.android.gms.tasks.Task<T>.await(): T =
    kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        addOnSuccessListener { cont.resumeWith(Result.success(it)) }
        addOnFailureListener { cont.resumeWith(Result.failure(it)) }
    }
