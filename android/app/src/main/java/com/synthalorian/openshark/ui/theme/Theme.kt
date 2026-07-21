package com.synthalorian.openshark.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// OpenShark Synthwave84 Palette
private val NeonPink = Color(0xFFFF7EDB)
private val ElectricPurple = Color(0xFF8F00FF)
private val DeepPurple = Color(0xFF240037)
private val NeonYellow = Color(0xFFF3E70F)
private val Magenta = Color(0xFFFF00FF)

private val DarkColorScheme = darkColorScheme(
    primary = NeonPink,
    onPrimary = Color.Black,
    primaryContainer = NeonPink.copy(alpha = 0.2f),
    onPrimaryContainer = NeonPink,
    secondary = ElectricPurple,
    onSecondary = Color.White,
    secondaryContainer = ElectricPurple.copy(alpha = 0.2f),
    onSecondaryContainer = Color.White,
    tertiary = NeonYellow,
    background = DeepPurple,
    onBackground = Color.White,
    surface = DeepPurple.copy(alpha = 0.8f),
    onSurface = Color.White,
    surfaceVariant = ElectricPurple.copy(alpha = 0.15f),
    onSurfaceVariant = Color.White.copy(alpha = 0.7f),
    outline = NeonPink.copy(alpha = 0.5f)
)

private val LightColorScheme = lightColorScheme(
    primary = ElectricPurple,
    onPrimary = Color.White,
    secondary = NeonPink,
    onSecondary = Color.Black,
    tertiary = Magenta,
    background = Color(0xFFF5F0FF),
    onBackground = Color(0xFF1C1B1F),
    surface = Color.White,
    onSurface = Color(0xFF1C1B1F)
)

@Composable
fun OpenSharkTheme(
    darkTheme: Boolean = true, // Default to synthwave dark
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = androidx.compose.material3.Typography(),
        content = content
    )
}
