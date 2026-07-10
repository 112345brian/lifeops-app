plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.gms.google-services")
}

android {
    namespace = "com.lifeops.briefing"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.lifeops.briefing"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        getByName("main") {
            kotlin.srcDirs("src/main/kotlin")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.0")

    // Jetpack Glance, for building the home-screen widget UI.
    implementation("androidx.glance:glance-appwidget:1.1.1")

    // Preferences DataStore -- glance-appwidget pulls this in transitively for
    // PreferencesGlanceStateDefinition, declared explicitly so WidgetKeys.kt's
    // stringPreferencesKey/longPreferencesKey imports resolve without relying
    // on transitive resolution.
    implementation("androidx.datastore:datastore-preferences:1.1.7")

    // Periodic pull of the next-tasks list (NextTasksRefreshWorker).
    implementation("androidx.work:work-runtime-ktx:2.11.2")

    // EncryptedSharedPreferences, for storing the server base URL + auth
    // token used by NextTasksRefreshWorker/CompleteTaskAction.
    implementation("androidx.security:security-crypto:1.1.0")

    // Jetpack Compose, for the settings screen (SettingsActivity).
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation(platform("androidx.compose:compose-bom:2025.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Firebase Cloud Messaging -- reliable push for the briefing (replaces
    // the ntfy broadcast, which can't wake a stopped app). BoM manages
    // compatible versions, so firebase-messaging itself is unversioned.
    implementation(platform("com.google.firebase:firebase-bom:34.15.0"))
    implementation("com.google.firebase:firebase-messaging")

    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.glance:glance-testing:1.1.1")
    testImplementation("androidx.glance:glance-appwidget-testing:1.1.1")
    testImplementation("org.robolectric:robolectric:4.16.1")
}
