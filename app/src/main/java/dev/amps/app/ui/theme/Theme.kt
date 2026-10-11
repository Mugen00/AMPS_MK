package dev.amps.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.amps.app.core.ThemeMode

private val DarkColors = darkColorScheme(
    primary = AmpsColors.violet,
    onPrimary = androidx.compose.ui.graphics.Color.White,
    primaryContainer = AmpsColors.violetDeep,
    onPrimaryContainer = androidx.compose.ui.graphics.Color.White,
    secondary = AmpsColors.cyan,
    onSecondary = AmpsColors.ink,
    tertiary = AmpsColors.amber,
    onTertiary = AmpsColors.ink,
    background = AmpsColors.ink,
    onBackground = androidx.compose.ui.graphics.Color(0xFFECEBF5),
    surface = AmpsColors.inkSoft,
    onSurface = androidx.compose.ui.graphics.Color(0xFFECEBF5),
    surfaceVariant = androidx.compose.ui.graphics.Color(0xFF1F1F2C),
    onSurfaceVariant = AmpsColors.onSurfaceVariant,
    outline = androidx.compose.ui.graphics.Color(0xFF3A3A4D),
    error = AmpsColors.rose,
)

private val LightColors = lightColorScheme(
    primary = AmpsColors.violetDeep,
    onPrimary = androidx.compose.ui.graphics.Color.White,
    secondary = AmpsColors.cyan,
    tertiary = AmpsColors.amber,
    background = androidx.compose.ui.graphics.Color(0xFFF7F5FF),
    surface = androidx.compose.ui.graphics.Color.White,
    onSurface = AmpsColors.ink,
    onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFF4A4760),
)

private val AMPSTypography = Typography(
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
fun AmpsTheme(
    themeMode: ThemeMode = ThemeMode.DARK,
    /** 1.2.4: hue акценту (сезонна або згенерована тема); null — фірмовий фіолет. */
    accentHue: Float? = null,
    content: @Composable () -> Unit,
) {
    var colors = when (themeMode) {
        ThemeMode.DARK -> DarkColors
        ThemeMode.LIGHT -> LightColors
        ThemeMode.SYSTEM -> if (androidx.compose.foundation.isSystemInDarkTheme()) DarkColors else LightColors
    }
    if (accentHue != null) {
        val accent = androidx.compose.ui.graphics.Color.hsv(accentHue, 0.52f, 0.92f)
        val accentDeep = androidx.compose.ui.graphics.Color.hsv(
            normalizedHue(accentHue + 22f), 0.55f, if (themeMode == ThemeMode.LIGHT) 0.55f else 0.78f,
        )
        val secondary = androidx.compose.ui.graphics.Color.hsv(
            normalizedHue(accentHue + 45f), 0.45f, 0.85f,
        )
        val tertiary = androidx.compose.ui.graphics.Color.hsv(
            normalizedHue(accentHue + 180f), 0.55f, 0.9f,
        )
        colors = colors.copy(
            primary = accent,
            primaryContainer = accentDeep,
            onPrimaryContainer = androidx.compose.ui.graphics.Color.White,
            secondary = accentDeep,
            onSecondary = androidx.compose.ui.graphics.Color.White,
            tertiary = androidx.compose.ui.graphics.Color.hsv(normalizedHue(accentHue + 160f), 0.5f, 0.85f),
        )
    }
    MaterialTheme(
        colorScheme = colors,
        typography = AMPSTypography,
        content = content,
    )
}

internal fun normalizedHue(h: Float): Float {
    val m = h % 360f
    return if (m < 0f) m + 360f else m
}
