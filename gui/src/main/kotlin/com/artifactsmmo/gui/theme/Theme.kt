package com.artifactsmmo.gui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ── Colour palette ────────────────────────────────────────────────────────────

private val DarkPrimary    = Color(0xFF90CAF9) // Blue 200
private val DarkSecondary  = Color(0xFFA5D6A7) // Green 200
private val DarkTertiary   = Color(0xFFFFCC80) // Orange 200
private val DarkBackground = Color(0xFF121212)
private val DarkSurface    = Color(0xFF1E1E1E)
private val DarkOnPrimary  = Color(0xFF003C71)
private val DarkOnSurface  = Color(0xFFE0E0E0)
private val DarkOnBackground = Color(0xFFE0E0E0)
private val DarkError      = Color(0xFFCF6679)

private val LightPrimary    = Color(0xFF1565C0) // Blue 800
private val LightSecondary  = Color(0xFF2E7D32) // Green 800
private val LightTertiary   = Color(0xFFE65100) // Deep Orange 900
private val LightBackground = Color(0xFFF5F5F5)
private val LightSurface    = Color(0xFFFFFFFF)
private val LightOnPrimary  = Color(0xFFFFFFFF)
private val LightOnSurface  = Color(0xFF212121)
private val LightError      = Color(0xFFB00020)

// ── Colour schemes ────────────────────────────────────────────────────────────

private val AppDarkColorScheme = darkColorScheme(
    primary          = DarkPrimary,
    onPrimary        = DarkOnPrimary,
    secondary        = DarkSecondary,
    tertiary         = DarkTertiary,
    background       = DarkBackground,
    surface          = DarkSurface,
    onSurface        = DarkOnSurface,
    onBackground     = DarkOnBackground,
    error            = DarkError,
)

private val AppLightColorScheme = lightColorScheme(
    primary          = LightPrimary,
    onPrimary        = LightOnPrimary,
    secondary        = LightSecondary,
    tertiary         = LightTertiary,
    background       = LightBackground,
    surface          = LightSurface,
    onSurface        = LightOnSurface,
    error            = LightError,
)

// ── Theme composable ──────────────────────────────────────────────────────────

/**
 * Root theme for the ArtifactsMMO GUI.
 * Defaults to dark. Pass [darkTheme] = false for light mode.
 */
@Composable
fun AppTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) AppDarkColorScheme else AppLightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
