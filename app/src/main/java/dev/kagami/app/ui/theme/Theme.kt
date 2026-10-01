package dev.kagami.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.kagami.app.core.ThemeMode

private val DarkColors = darkColorScheme(
    primary = KagamiColors.violet,
    onPrimary = androidx.compose.ui.graphics.Color.White,
    primaryContainer = KagamiColors.violetDeep,
    onPrimaryContainer = androidx.compose.ui.graphics.Color.White,
    secondary = KagamiColors.cyan,
    onSecondary = KagamiColors.ink,
    tertiary = KagamiColors.amber,
    onTertiary = KagamiColors.ink,
    background = KagamiColors.ink,
    onBackground = androidx.compose.ui.graphics.Color(0xFFECEBF5),
    surface = KagamiColors.inkSoft,
    onSurface = androidx.compose.ui.graphics.Color(0xFFECEBF5),
    surfaceVariant = androidx.compose.ui.graphics.Color(0xFF1F1F2C),
    onSurfaceVariant = KagamiColors.onSurfaceVariant,
    outline = androidx.compose.ui.graphics.Color(0xFF3A3A4D),
    error = KagamiColors.rose,
)

private val LightColors = lightColorScheme(
    primary = KagamiColors.violetDeep,
    onPrimary = androidx.compose.ui.graphics.Color.White,
    secondary = KagamiColors.cyan,
    tertiary = KagamiColors.amber,
    background = androidx.compose.ui.graphics.Color(0xFFF7F5FF),
    surface = androidx.compose.ui.graphics.Color.White,
    onSurface = KagamiColors.ink,
    onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFF4A4760),
)

private val KagamiTypography = Typography(
    headlineLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.4).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 21.sp,
        lineHeight = 27.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 23.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        letterSpacing = 0.4.sp,
    ),
)

@Composable
fun KagamiTheme(
    themeMode: ThemeMode = ThemeMode.DARK,
    content: @Composable () -> Unit,
) {
    val colors = when (themeMode) {
        ThemeMode.DARK -> DarkColors
        ThemeMode.LIGHT -> LightColors
        ThemeMode.SYSTEM -> if (androidx.compose.foundation.isSystemInDarkTheme()) DarkColors else LightColors
    }
    MaterialTheme(
        colorScheme = colors,
        typography = KagamiTypography,
        content = content,
    )
}
