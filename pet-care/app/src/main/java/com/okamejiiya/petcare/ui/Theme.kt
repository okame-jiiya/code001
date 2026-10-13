package com.okamejiiya.petcare.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** やさしいパステルカラー。ピンクを主役に、ミント・ラベンダー・ミルクティーを添える */
object Pastel {
    val Pink = Color(0xFFE891A9)
    val PinkLight = Color(0xFFFFE3EB)
    val Mint = Color(0xFF7FC8B8)
    val MintLight = Color(0xFFDDF3EE)
    val Lavender = Color(0xFFA996D6)
    val LavenderLight = Color(0xFFEDE6FA)
    val Latte = Color(0xFFD9B48F)
    val LatteLight = Color(0xFFF6EADC)
    val Lemon = Color(0xFFF2CF5B)
    val LemonLight = Color(0xFFFFF4C9)
    val Peach = Color(0xFFF4A989)
    val PeachLight = Color(0xFFFFE6DA)
    val Cream = Color(0xFFFFF9F5)
    val Ink = Color(0xFF4F4247)
    val InkSoft = Color(0xFF8A7A80)
    val Line = Color(0xFFEBDCE0)
}

private val colors = lightColorScheme(
    primary = Pastel.Pink,
    onPrimary = Color.White,
    primaryContainer = Pastel.PinkLight,
    onPrimaryContainer = Color(0xFF6B3446),
    secondary = Pastel.Mint,
    onSecondary = Color.White,
    secondaryContainer = Pastel.MintLight,
    onSecondaryContainer = Color(0xFF24504A),
    tertiary = Pastel.Lavender,
    onTertiary = Color.White,
    tertiaryContainer = Pastel.LavenderLight,
    onTertiaryContainer = Color(0xFF45386B),
    background = Pastel.Cream,
    onBackground = Pastel.Ink,
    surface = Pastel.Cream,
    onSurface = Pastel.Ink,
    surfaceVariant = Color(0xFFF8EEF1),
    onSurfaceVariant = Pastel.InkSoft,
    surfaceContainer = Color(0xFFFFF1F4),
    surfaceContainerLow = Color.White,
    surfaceContainerHigh = Color(0xFFFFEEF2),
    outline = Color(0xFFD9C3CA),
    outlineVariant = Pastel.Line,
    error = Color(0xFFD9667A),
)

private val shapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun PetCareTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, shapes = shapes, content = content)
}
