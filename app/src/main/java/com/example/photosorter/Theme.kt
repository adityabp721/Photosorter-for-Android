package com.example.photosorter

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape

// Brand palette - matches the launcher icon (violet -> indigo gradient, amber sparkle).
private val Violet = Color(0xFF6750E8)
private val VioletDark = Color(0xFFC9BEFF)

private val LightColors = lightColorScheme(
    primary = Violet,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE6DEFF),
    onPrimaryContainer = Color(0xFF1F0B6E),
    secondary = Color(0xFF5D5A7A),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE5E0FA),
    onSecondaryContainer = Color(0xFF1E1A3C),
    tertiary = Color(0xFFB26A00),
    tertiaryContainer = Color(0xFFFFE0B2),
    onTertiaryContainer = Color(0xFF2C1700),
    background = Color(0xFFF8F6FF),
    surface = Color(0xFFF8F6FF),
    surfaceVariant = Color(0xFFE7E3F2),
    onSurfaceVariant = Color(0xFF494555),
    outlineVariant = Color(0xFFCBC5DA),
)

private val DarkColors = darkColorScheme(
    primary = VioletDark,
    onPrimary = Color(0xFF32199A),
    primaryContainer = Color(0xFF4A35C7),
    onPrimaryContainer = Color(0xFFE6DEFF),
    secondary = Color(0xFFC8C2F0),
    onSecondary = Color(0xFF2F2B4C),
    secondaryContainer = Color(0xFF3E3A5C),
    onSecondaryContainer = Color(0xFFE3DFFF),
    tertiary = Color(0xFFFFB960),
    tertiaryContainer = Color(0xFF653E00),
    onTertiaryContainer = Color(0xFFFFE0B2),
    background = Color(0xFF131218),
    surface = Color(0xFF131218),
    surfaceVariant = Color(0xFF2B2836),
    onSurfaceVariant = Color(0xFFCAC4D6),
    outlineVariant = Color(0xFF494555),
)

/** Green used for "kept / filed / done" states in both themes. */
val SuccessColor: Color
    @Composable get() = if (isSystemInDarkTheme()) Color(0xFF8BD69A) else Color(0xFF2E7D32)

@Composable
fun PhotoSorterTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        shapes = Shapes(
            small = RoundedCornerShape(10.dp),
            medium = RoundedCornerShape(16.dp),
            large = RoundedCornerShape(24.dp),
        ),
        content = content,
    )
}
