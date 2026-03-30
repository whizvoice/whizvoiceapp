package com.example.whiz.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

@Composable
fun WhizTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // Set the status bar color to your primary color
            window.statusBarColor = colorScheme.primary.toArgb()

            // Determine if the primary color is light or dark
            val primaryColor = colorScheme.primary
            val luminance =
                (0.2126 * primaryColor.red + 0.7152 * primaryColor.green + 0.0722 * primaryColor.blue)

            // Set icons to dark if the status bar color (primary) is light, and vice-versa
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars =
                luminance > 0.5
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )

}
