package com.lifeops.briefing.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Same dark M3 palette [com.lifeops.briefing.WidgetConfigActivity]'s
 * `LifeOpsDarkColors` already uses to match the server-rendered panel's dark
 * theme (see that file) -- duplicated here rather than imported, since the
 * original is a `private val` local to that file (not module-visible).
 * Promoting/moving it felt riskier than a short, deliberately-labeled
 * literal duplicate for a handful of Color(...) constants that don't
 * actually change independently of each other.
 */
private val LifeOpsColors = darkColorScheme(
    primary = Color(0xFFA9C7FF),
    onPrimary = Color(0xFF0A2F5C),
    primaryContainer = Color(0xFF2F4D80),
    onPrimaryContainer = Color(0xFFDAE5FF),
    secondaryContainer = Color(0xFF24262F),
    onSecondaryContainer = Color(0xFFE4E2E9),
    background = Color(0xFF101116),
    onBackground = Color(0xFFE4E2E9),
    surface = Color(0xFF101116),
    onSurface = Color(0xFFE4E2E9),
    surfaceVariant = Color(0xFF1A1C23),
    onSurfaceVariant = Color(0xFFA4A2AE),
    outline = Color(0xFF4A4B57),
    outlineVariant = Color(0xFF2C2E38),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF5C1A1A),
)

@Composable
fun LifeOpsTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = LifeOpsColors, content = content)
}
