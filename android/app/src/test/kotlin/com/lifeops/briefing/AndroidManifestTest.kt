package com.lifeops.briefing

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidManifestTest {
    @Test
    fun packageVisibilityCoversLauncherAppsForConfigPicker() {
        val manifestFile = listOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml"),
        ).first { it.exists() }
        val manifest = manifestFile.readText()

        assertTrue(manifest.contains("""<queries>"""))
        assertTrue(manifest.contains("""<action android:name="android.intent.action.MAIN" />"""))
        assertTrue(manifest.contains("""<category android:name="android.intent.category.LAUNCHER" />"""))
        assertFalse(manifest.contains("""<package android:name="com.youneedabudget.evergreen.app" />"""))
        assertFalse(manifest.contains("""<package android:name="io.a24go.android.dev" />"""))
        assertFalse(manifest.contains("""<package android:name="com.apalon.weatherradar.free" />"""))
    }
}
