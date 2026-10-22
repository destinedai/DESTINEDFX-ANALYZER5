package com.example.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

// Unified rounded component geometry across Analyzer, Strategies, Calendar, Bot, Risk, and Settings.
private val DestinedFxShapes = Shapes(
  extraSmall = RoundedCornerShape(8.dp),
  small = RoundedCornerShape(10.dp),
  medium = RoundedCornerShape(12.dp),
  large = RoundedCornerShape(18.dp),
  extraLarge = RoundedCornerShape(24.dp)
)

// Force premium dark trading terminal color scheme
private val DestinedFxDarkColorScheme =
  darkColorScheme(
    primary = CyanPrimary,
    secondary = DarkSurfaceVariant,
    tertiary = ProfitGreen,
    background = DarkBackground,
    surface = DarkSurface,
    surfaceVariant = DarkSurfaceVariant,
    onPrimary = DarkBackground,
    onSecondary = TextPrimary,
    onTertiary = DarkBackground,
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
          window.statusBarColor = DarkBackground.toArgb()
          window.navigationBarColor = DarkBackground.toArgb()
          WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
          WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = false
        }
      } catch (ignored: Exception) {}
    }
  }

  MaterialTheme(colorScheme = colorScheme, typography = Typography, shapes = DestinedFxShapes, content = content)
}
