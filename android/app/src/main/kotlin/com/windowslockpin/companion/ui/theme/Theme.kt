package com.windowslockpin.companion.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Crystalline Ice Blue & Tech Navy Palette
val IceBluePrimary = Color(0xFF0088FF)
val IceBlueLight = Color(0xFF5BC0FF)
val IceBlueDark = Color(0xFF0055B3)
val CyanAccent = Color(0xFF00E5FF)
val PrismIndigo = Color(0xFF4361EE)

val DeepNavy = Color(0xFF0A192F)
val SlateGray = Color(0xFF4A6572)
val LightSurface = Color(0xF2FFFFFF)
val GlassCard = Color(0xD9FFFFFF)
val GlassCardDark = Color(0xCC0D1B2A)
val BorderCrystal = Color(0x6600E5FF)
val BorderSubtle = Color(0x260088FF)

val SoftRed = Color(0xFFFF4D4F)
val EmeraldGreen = Color(0xFF00C853)

private val LightColorScheme = lightColorScheme(
    primary = IceBluePrimary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE3F2FD),
    onPrimaryContainer = DeepNavy,
    secondary = PrismIndigo,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE8EAF6),
    onSecondaryContainer = DeepNavy,
    surface = LightSurface,
    onSurface = DeepNavy,
    surfaceVariant = GlassCard,
    onSurfaceVariant = SlateGray,
    outline = BorderCrystal,
    error = SoftRed,
    onError = Color.White
)

val LivingUnlockShapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

@Composable
fun LivingUnlockTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = LightColorScheme,
        shapes = LivingUnlockShapes,
        content = content
    )
}
