package com.example.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

val LocalQuarkTheme = staticCompositionLocalOf { QuarkThemes.OneDark }

@Composable
fun QuarkMaterialTheme(
  theme: QuarkThemeDefinition = QuarkThemes.OneDark,
  content: @Composable () -> Unit,
) {
  val view = LocalView.current
  if (!view.isInEditMode) {
    SideEffect {
      val window = (view.context as? Activity)?.window
      if (window != null) {
        val ctrl = WindowCompat.getInsetsController(window, view)
        ctrl.isAppearanceLightStatusBars = !theme.isDark
        ctrl.isAppearanceLightNavigationBars = !theme.isDark
      }
    }
  }

  val scheme =
    if (theme.isDark) {
      darkColorScheme(
        primary = theme.ui.accent,
        onPrimary = theme.ui.onAccent,
        background = theme.ui.editorBg,
        onBackground = theme.ui.textPrimary,
        surface = theme.ui.sidePanelBg,
        onSurface = theme.ui.textPrimary,
        surfaceVariant = theme.ui.tabBarBg,
        onSurfaceVariant = theme.ui.textSecondary,
        outline = theme.ui.border,
        error = theme.ui.danger,
      )
    } else {
      lightColorScheme(
        primary = theme.ui.accent,
        onPrimary = theme.ui.onAccent,
        background = theme.ui.editorBg,
        onBackground = theme.ui.textPrimary,
        surface = theme.ui.sidePanelBg,
        onSurface = theme.ui.textPrimary,
        surfaceVariant = theme.ui.tabBarBg,
        onSurfaceVariant = theme.ui.textSecondary,
        outline = theme.ui.border,
        error = theme.ui.danger,
      )
    }

  CompositionLocalProvider(LocalQuarkTheme provides theme) {
    MaterialTheme(
      colorScheme = scheme,
      typography = QuarkTypography,
      content = content,
    )
  }
}
