import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.gms.google-services")
    // Room's annotation processor (RoutineEntity/HistoryEventEntity/
    // TaskCacheEntity's generated DAO impls + LifeOpsDatabase_Impl) --
    // see root build.gradle.kts for why KSP over kapt.
    id("com.google.devtools.ksp")
}

// ntfy.signalTopic in local.properties (gitignored, machine-local, same file
// that already holds sdk.dir) -- baked in at build time as a BuildConfig
// field so CompleteTaskAction can POST a completion signal straight to
// ntfy.sh without needing the Tailscale-gated panel URL/token at all. Not a
// secret (ntfy.py: "No auth (public topics)") but also not something that
// belongs in a tracked source file, hence local.properties over hardcoding.
val localProperties = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val ntfySignalTopic: String = localProperties.getProperty("ntfy.signalTopic", "")

android {
    namespace = "com.lifeops.briefing"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.lifeops.briefing"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "NTFY_SIGNAL_TOPIC", "\"$ntfySignalTopic\"")
    }

    buildFeatures {
        buildConfig = true
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

    // FusedLocationProviderClient, for LocationReporter's one-shot GPS fix.
    implementation("com.google.android.gms:play-services-location:21.3.0")

    // Room, for the on-device persistence layer (RoutineEntity/
    // HistoryEventEntity/TaskCacheEntity, see com.lifeops.briefing.data) that
    // the data-gather layer, WorkManager compute tick, and native UI work
    // will build on. room-runtime + room-ktx for the (suspend-fun) query
    // surface; room-compiler is a KSP processor, not a runtime dependency,
    // hence "ksp(...)" rather than "implementation(...)".
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")

    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.glance:glance-testing:1.1.1")
    testImplementation("androidx.glance:glance-appwidget-testing:1.1.1")
    testImplementation("org.robolectric:robolectric:4.16.1")
}
