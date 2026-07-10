// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    // AGP 9.x has built-in Kotlin support -- the separate
    // org.jetbrains.kotlin.android plugin is no longer needed (and conflicts
    // with it), so only the compose compiler plugin is applied here.
    id("com.android.application") version "9.2.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.0" apply false
    // Makes google-services.json's values available to the Firebase SDKs
    // (FCM push for the briefing, replacing the unreliable ntfy broadcast).
    id("com.google.gms.google-services") version "4.5.0" apply false
}
