package com.example.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// Force premium dark trading terminal color scheme
private val DestinedFxDarkColorScheme =
  darkColorScheme(
    primary = CyanPrimary,
    secondary = BlueAccent,
    tertiary = ProfitGreen,
    background = DarkBackground,
    surface = DarkSurface,
    surfaceVariant = DarkSurfaceVariant,
    onPrimary = Color(0xFF070B14),
    onSecondary = Color(0xFF070B14),
    onTertiary = Color(0xFF070B14),
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    onSurfaceVariant = TextSecondary,
    error = LossRed,
    outline = TabBorder,
    outlineVariant = TabBorder
  )

private tailrec fun android.content.Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
fun DestinedFXTheme(
  themeMode: String = "Dark",
  content: @Composable () -> Unit,
) {
  // Always enforce dark theme for authentic trading terminal atmosphere
  val colorScheme = DestinedFxDarkColorScheme

  val view = LocalView.current
  if (!view.isInEditMode) {
    SideEffect {
      try {
        val activity = view.context.findActivity()
        val window = activity?.window
        if (window != null) {
          window.statusBarColor = Color(0xFF070B14).toArgb()
          window.navigationBarColor = Color(0xFF070B14).toArgb()
          WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
          WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = false
        }
      } catch (ignored: Exception) {}
    }
  }

  MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
