package com.example.whiz.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFFFFD700),  // Yellow (gold)
    onPrimary = Color.Black,
    primaryContainer = Color(0xFFFFF59D),  // Light yellow
    onPrimaryContainer = Color.Black,
    secondary = Color(0xFF673AB7),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFF9C4),  // Light yellow for assistant messages
    onSecondaryContainer = Color.Black,
    tertiary = Color(0xFFFFD700),  // Yellow instead of green
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFF59D),  // Light yellow
    onTertiaryContainer = Color.Black,
    background = Color(0xFFF5F5F5),
    onBackground = Color(0xFF212121),
    surface = Color.White,
    onSurface = Color(0xFF212121),
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFFFD700),  // Yellow (gold)
    onPrimary = Color.Black,
    primaryContainer = Color(0xFFF9A825),  // Darker yellow
    onPrimaryContainer = Color.Black,
    secondary = Color(0xFFCE93D8),
    onSecondary = Color(0xFF4A148C),
    secondaryContainer = Color(0xFFFFF176),  // Light yellow for assistant messages in dark mode
    onSecondaryContainer = Color.Black,
    tertiary = Color(0xFFFFEB3B),  // Bright yellow
    onTertiary = Color.Black,
    tertiaryContainer = Color(0xFFF9A825),  // Darker yellow
    onTertiaryContainer = Color.Black,
    background = Color(0xFF121212),
    onBackground = Color(0xFFE0E0E0),
    surface = Color(0xFF1E1E1E),
    onSurface = Color(0xFFE0E0E0),
)

@Composable
fun WhizTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            // Get the dynamic color scheme but override with our yellow colors
            val baseScheme = if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            // Override the dynamic colors with our yellow theme
            baseScheme.copy(
                primary = if (darkTheme) Color(0xFFFFD700) else Color(0xFFFFD700),  // Yellow
                onPrimary = Color.Black,
                primaryContainer = if (darkTheme) Color(0xFFF9A825) else Color(0xFFFFF59D),  // Light yellow
                onPrimaryContainer = Color.Black,
                secondary = if (darkTheme) Color(0xFF673AB7) else Color(0xFF673AB7),  // Keep secondary
                onSecondary = Color.White,
                secondaryContainer = if (darkTheme) Color(0xFFFFF176) else Color(0xFFFFF9C4),  // Light yellow for bot messages
                onSecondaryContainer = Color.Black,
                tertiary = Color(0xFFFFD700),  // Yellow
                onTertiary = Color.Black,
                surface = if (darkTheme) Color(0xFF1E1E1E) else Color.White,
                onSurface = if (darkTheme) Color(0xFFE0E0E0) else Color.Black
            )
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // Set the status bar color to your primary color
            window.statusBarColor = colorScheme.primary.toArgb()

            // Determine if the primary color is light or dark
            // You might need a helper function to calculate luminance or use a library
            // For simplicity, let's assume a threshold (e.g., > 0.5 luminance is light)
            // This requires a way to calculate luminance. A simple approximation:
            val primaryColor = colorScheme.primary
            val luminance =
                (0.2126 * primaryColor.red + 0.7152 * primaryColor.green + 0.0722 * primaryColor.blue)

            // Set icons to dark if the status bar color (primary) is light, and vice-versa
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars =
                luminance > 0.5 // Adjust threshold as needed

            // Optional: You might also want to control the navigation bar color/icons
            // window.navigationBarColor = colorScheme.background.toArgb() // Example
            // WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = luminanceBackground > 0.5 // Example
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )

}