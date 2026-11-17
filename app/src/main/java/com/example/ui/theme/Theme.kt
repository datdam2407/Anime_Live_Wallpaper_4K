package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val KuroDarkColorScheme = darkColorScheme(
    primary = CrimsonBlade,
    onPrimary = InkWhite,
    primaryContainer = CrimsonContainer,
    onPrimaryContainer = CrimsonGlow,
    secondary = ElectricViolet,
    onSecondary = InkWhite,
    secondaryContainer = ElectricVioletContainer,
    onSecondaryContainer = InkWhite,
    tertiary = CyberCyan,
    onTertiary = ObsidianVoid,
    tertiaryContainer = CyberCyanContainer,
    onTertiaryContainer = CyberCyan,
    background = ObsidianVoid,
    onBackground = InkWhite,
    surface = ObsidianSurface,
    onSurface = InkWhite,
    surfaceVariant = ObsidianSurfaceElevated,
    onSurfaceVariant = InkMuted,
    surfaceContainerLowest = ObsidianVoid,
    surfaceContainerLow = ObsidianSurface,
    surfaceContainer = ObsidianSurfaceElevated,
    surfaceContainerHigh = ObsidianSurfaceHighest,
    outline = HairlineBorder,
    outlineVariant = InkSubtle,
)

private val KuroShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun KuroWallTheme(
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = KuroDarkColorScheme,
        typography = KuroTypography,
        shapes = KuroShapes,
        content = content,
    )
}
