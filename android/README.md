# LifeOps Briefing (Android widget)

A minimal home-screen widget app, built with Jetpack Glance, that will show a
daily briefing pulled from the lifeops server. This is currently a **scaffold
only** — the widget renders a single placeholder line ("LifeOps Briefing —
not yet configured"). Real widget UI, the WorkManager-based refresh worker,
the settings screen (server URL + auth token), and action wiring are added in
later passes on top of this scaffold.

## Build

```
cd android
./gradlew assembleDebug
```

(On Windows: `gradlew.bat assembleDebug`.) The debug APK is written to
`app/build/outputs/apk/debug/app-debug.apk`.

Requires JDK 17+ and the Android SDK (compileSdk/targetSdk 36) available to
Gradle — either via Android Studio or `ANDROID_HOME`/`ANDROID_SDK_ROOT` set in
your environment.

## Sideload

With a device connected and USB debugging enabled (or over `adb connect` on
Tailscale/Wi-Fi):

```
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Then long-press the home screen, choose Widgets, find "LifeOps Briefing", and
place it.

## What this talks to

Once the network-fetch step lands, this widget will call the lifeops
server's `/api/briefing` endpoint over your Tailscale network (so the phone
never needs to reach the server over the public internet). The server's base
URL and an auth token will be configured through an in-app settings screen
(not yet built) and stored using `androidx.security` `EncryptedSharedPreferences`.
Until then, the widget shows only the static placeholder text.

## Project layout

- `settings.gradle.kts`, `build.gradle.kts` — top-level Gradle config.
- `app/build.gradle.kts` — module config: Kotlin, Glance, WorkManager,
  security-crypto dependencies.
- `app/src/main/AndroidManifest.xml` — declares the `INTERNET` permission and
  the `BriefingWidgetReceiver`.
- `app/src/main/kotlin/com/lifeops/briefing/`
  - `BriefingWidgetReceiver.kt` — `GlanceAppWidgetReceiver` subclass, OS entry
    point for widget lifecycle events.
  - `BriefingWidget.kt` — `GlanceAppWidget` subclass, renders the placeholder
    content.
- `app/src/main/res/xml/briefing_widget_info.xml` — appwidget-provider
  metadata (size, resize behavior, fallback update period).
- `app/src/main/res/layout/widget_loading.xml` — plain-View fallback layout
  used as the widget's `initialLayout`/`previewLayout` before Glance takes
  over.
