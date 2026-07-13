# LifeOps Briefing (Android)

A small Android companion app and home-screen widget for LifeOps. It shows the
daily briefing, today's events, and upcoming FlowSavvy tasks, and lets you check
tasks off from the widget.

The widget uses:

- Jetpack Glance for the home-screen UI.
- WorkManager for periodic refresh and resilient background writes.
- Firebase Cloud Messaging for reliable briefing pushes.
- EncryptedSharedPreferences for the panel URL and auth token.
- ntfy only as the fallback completion signal path for task checkboxes.

## Build

```powershell
cd android
.\gradlew.bat assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

Requires JDK 17+ and the Android SDK. The project currently targets SDK 37.

## Sideload

With a device connected and USB debugging enabled:

```powershell
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Then open the LifeOps app once, enter the panel URL and auth token, save, and
place the "LifeOps Briefing" widget from the launcher widget picker.

## Runtime Flow

1. `SettingsActivity` stores the panel URL and token.
2. `RegisterTokenWorker` sends the current FCM token to
   `/api/register-fcm-token`.
3. `NextTasksRefreshWorker` periodically pulls `/api/next-tasks` and
   `/api/briefing`.
4. `BriefingFcmService` receives the daily briefing push and enqueues
   `BriefingPersistWorker`.
5. `BriefingWidget` renders the persisted briefing, events, and tasks.
6. `CompleteTaskAction` removes a checked task optimistically and posts a
   `complete:<id>` signal through ntfy so the Python runner can complete it in
   FlowSavvy even when the phone is not on Tailscale.

## Project Layout

- `app/build.gradle.kts` - Android, Kotlin, Glance, WorkManager, security, and
  Firebase dependencies.
- `app/src/main/AndroidManifest.xml` - app, widget receiver, FCM service, and
  network/security declarations.
- `BriefingWidget.kt` - Glance widget UI.
- `SettingsActivity.kt` - panel URL/token setup.
- `NextTasksRefreshWorker.kt` - periodic pull fallback for tasks and briefing.
- `BriefingFcmService.kt` and `BriefingPersistWorker.kt` - reliable push path.
- `CompleteTaskAction.kt` and `PendingRemovals.kt` - checkbox completion flow.
- `WidgetConfigStore.kt` and `WidgetKeys.kt` - local configuration and Glance
  state keys.
