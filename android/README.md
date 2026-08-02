# LifeOps Briefing (Android)

An Android companion app and home-screen widget for LifeOps. Most domains
(gym, chore, social, meal, deadline-risk, YNAB) now compute on-device, backed
by a local Room database, rather than pulling pre-computed state from the
Python home server -- the server remains authoritative only for Canvas
(JHU blocks self-service API tokens and the login flow can't be automated
off a real browser session). See `docs/lifeops_capability_todo.md` at the
repo root for the full migration rationale.

The app uses:

- Jetpack Glance for the home-screen widget UI, plus a native Jetpack Compose
  app (`ui/`) for the Today/Attention/Recurring/History/System screens.
- Room for local persistence (routines, gym/chore/social history, cached
  tasks, blocked days).
- WorkManager (`LifeOpsComputeWorker`) for the periodic on-device compute
  tick: fetches FlowSavvy/YNAB data, runs the ported engines, computes
  attention state, and persists the result.
- An on-device FlowSavvy client and Anthropic API client for direct,
  no-server-round-trip data fetches and LLM calls (weekly digest, YNAB
  payee categorization).
- Firebase Cloud Messaging for reliable briefing pushes.
- EncryptedSharedPreferences for the panel URL/token, YNAB token, and
  Anthropic API key.
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

1. `SettingsActivity` stores the panel URL/token, YNAB token, and Anthropic
   API key.
2. `RegisterTokenWorker` sends the current FCM token to
   `/api/register-fcm-token`.
3. `LifeOpsComputeWorker` runs every 15 minutes: fetches FlowSavvy/YNAB data
   on-device (`FlowSavvyClient.kt`), persists it to Room, runs the ported
   engines (`GymSchedule`/`ChoreSchedule`/`SocialSchedule`/`MealSchedule`/
   `YnabEngine`/`DeadlineRisk`), feeds the results into `Attention.kt`'s
   deterministic ok/watch/risk/fucked state, and persists the widget/UI-
   readable `BriefingState`/`NextTasksState`. Also self-gates the weekly
   digest LLM call (`WeeklyDigest.kt`) on Sundays.
4. `BriefingFcmService` receives the daily briefing push and enqueues
   `BriefingPersistWorker` (still used as a push-driven path independent of
   the compute tick above).
5. `BriefingWidget` renders the persisted briefing, events, and tasks;
   `ui/MainActivity` and its Compose screens (`TodayScreen`, `AttentionScreen`,
   `RoutineScreens`, `HistoryScreen`, `SystemScreen`) render the same
   underlying state as the full app.
6. `CompleteTaskAction` (widget) / `TodayRepository.completeTask` (app) each
   remove a checked task optimistically and post a `complete:<id>` signal
   through ntfy as a fallback when the direct FlowSavvy API call fails.
7. The Today screen's quick actions (log/skip gym, block day, run catchup)
   write directly to Room and re-run the relevant engine immediately, via
   `ui/PanelActionsClient.kt` -- no server round-trip.

## Project Layout

- `app/build.gradle.kts` - Android, Kotlin, Glance, WorkManager, Room,
  biometric, security, and Firebase dependencies.
- `app/src/main/AndroidManifest.xml` - app, widget receiver, FCM service, and
  network/security declarations.
- `BriefingWidget.kt` - Glance widget UI.
- `SettingsActivity.kt` - panel URL/token, YNAB, and Anthropic key setup.
- `LifeOpsComputeWorker.kt` - the on-device compute tick (see Runtime Flow).
- `FlowSavvyClient.kt`, `Attention.kt` - on-device data fetch and the
  deterministic attention-state engine.
- `Routine.kt`, `LifeScript.kt`, `GymSchedule.kt`, `ChoreSchedule.kt`,
  `SocialSchedule.kt`, `MealSchedule.kt`, `YnabEngine.kt`, `DeadlineRisk.kt` -
  the ported domain engines, expressed as generic scheduling + a small
  expression grammar rather than one bespoke module per domain.
- `AnthropicClient.kt`, `WeeklyDigest.kt`, `BiometricGate.kt` - on-device LLM
  calls and the (currently unwired) write-action gating primitive.
- `data/` - Room entities/DAOs (`RoutineEntity`, `HistoryEventEntity`,
  `TaskCacheEntity`, `BlockedDayEntity`) and the widget-readable
  `BriefingState`/`NextTasksState` JSON shapes.
- `ui/` - the native Compose app (`MainActivity` + Today/Attention/Recurring/
  History/System screens).
- `BriefingFcmService.kt` and `BriefingPersistWorker.kt` - reliable push path.
- `CompleteTaskAction.kt` and `PendingRemovals.kt` - checkbox completion flow.
- `WidgetConfigStore.kt` and `WidgetKeys.kt` - local configuration and Glance
  state keys.
