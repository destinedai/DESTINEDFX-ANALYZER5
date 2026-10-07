package com.example.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DestinedFxDarkColorScheme =
  darkColorScheme(
    primary = CyanPrimary,
    secondary = PurpleSecondary,
    tertiary = GreenTertiary,
    background = DarkBackground,
    surface = DarkSurface,
    surfaceVariant = DarkSurfaceVariant,
    onPrimary = Color.Black,
    onSecondary = Color.Black,
    onTertiary = Color.Black,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    onSurfaceVariant = TextSecondary,
    error = RedAccent,
    outline = BorderColor,
    outlineVariant = BorderColor
  )

private val DestinedFxLightColorScheme =
  lightColorScheme(
    primary = Color(0xFF007A8C),      // Vibrant, professional Teal
    secondary = Color(0xFF6200EA),    // Deep Royal Purple
    tertiary = Color(0xFF00A254),     // Safe Green
    background = Color(0xFFF1F5F9),   // Slate light background
    surface = Color(0xFFFFFFFF),      // Clean white surfaces
    surfaceVariant = Color(0xFFE2E8F0), // Border/Card background accents
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = Color(0xFF0F172A),  // Deep slate for dark text
    onSurface = Color(0xFF0F172A),
    onSurfaceVariant = Color(0xFF475569),
    error = Color(0xFFD50000),         // Clean error red
    outline = Color(0xFFCBD5E1),
    outlineVariant = Color(0xFFCBD5E1)
  )

@Composable
fun DestinedFXTheme(
  themeMode: String = "System",
  content: @Composable () -> Unit,
) {
  val isDark = when (themeMode) {
    "Light" -> false
    "Dark" -> true
    else -> isSystemInDarkTheme()
  }

  val colorScheme = if (isDark) DestinedFxDarkColorScheme else DestinedFxLightColorScheme
  
  val view = LocalView.current
  if (!view.isInEditMode) {
    SideEffect {
      val window = (view.context as Activity).window
      window.statusBarColor = Color.Transparent.toArgb()
      window.navigationBarColor = Color.Transparent.toArgb()
      WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !isDark
      WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !isDark
    }
  }

  MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
