package com.lifeops.briefing

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
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
    val scope = rememberCoroutineScope()

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

        Button(
            onClick = {
                WidgetConfigStore.save(context, baseUrl.trim(), token.trim())
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
    }
}

/** Small local await() for a Firebase Task, avoiding a dependency on
 * kotlinx-coroutines-play-services just for this one call. */
private suspend fun <T> com.google.android.gms.tasks.Task<T>.await(): T =
    kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        addOnSuccessListener { cont.resumeWith(Result.success(it)) }
        addOnFailureListener { cont.resumeWith(Result.failure(it)) }
    }
