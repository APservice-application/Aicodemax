package com.aicodemax.ui.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Design tokens mapped from 1.2.txt §§4–14 (v1 mapping; full spec stays in requirements). */
object AicodeColors {
    // Light
    val LightBackground = Color(0xFFF7F7F8)
    val LightSurface = Color(0xFFFFFFFF)
    val LightPrimary = Color(0xFF2563EB)
    val LightPrimaryDark = Color(0xFF1D4ED8)
    val LightSecondary = Color(0xFF6B7280)
    val LightTertiary = Color(0xFF9CA3AF)
    val LightBorder = Color(0xFFE5E7EB)
    val LightSuccess = Color(0xFF16A34A)
    val LightWarning = Color(0xFFD97706)
    val LightDanger = Color(0xFFDC2626)
    val LightInfo = Color(0xFF0284C7)

    // Dark
    val DarkBackground = Color(0xFF0F1115)
    val DarkSurface = Color(0xFF1B202A)
    val DarkSurfaceVariant = Color(0xFF171B23)
    val DarkPrimary = Color(0xFF60A5FA)
    val DarkSecondary = Color(0xFFA1A1AA)
    val DarkTertiary = Color(0xFF71717A)
    val DarkBorder = Color(0xFF272D38)
    val DarkText = Color(0xFFF3F4F6)
    val DarkSuccess = Color(0xFF4ADE80)
    val DarkWarning = Color(0xFFFBBF24)
    val DarkDanger = Color(0xFFF87171)
    val DarkInfo = Color(0xFF38BDF8)

    // Terminal
    val TerminalBackground = Color(0xFF0B0D10)
    val TerminalForeground = Color(0xFFE5E7EB)
}

private val LightScheme = lightColorScheme(
    primary = AicodeColors.LightPrimary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDBEAFE),
    onPrimaryContainer = Color(0xFF1E40AF),
    secondary = AicodeColors.LightSecondary,
    background = AicodeColors.LightBackground,
    onBackground = Color(0xFF111827),
    surface = AicodeColors.LightSurface,
    onSurface = Color(0xFF111827),
    surfaceVariant = Color(0xFFF3F4F6),
    outline = AicodeColors.LightBorder,
    error = AicodeColors.LightDanger,
)

private val DarkScheme = darkColorScheme(
    primary = AicodeColors.DarkPrimary,
    onPrimary = Color(0xFF0F1115),
    secondary = AicodeColors.DarkSecondary,
    background = AicodeColors.DarkBackground,
    onBackground = AicodeColors.DarkText,
    surface = AicodeColors.DarkSurface,
    onSurface = AicodeColors.DarkText,
    surfaceVariant = AicodeColors.DarkSurfaceVariant,
    outline = AicodeColors.DarkBorder,
    error = AicodeColors.DarkDanger,
)

private val AicodeTypography = Typography(
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, lineHeight = 26.sp),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, lineHeight = 20.sp),
    labelSmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
)

@Immutable
data class AicodeSpacing(
    val xs: Dp = 4.dp,
    val sm: Dp = 8.dp,
    val md: Dp = 12.dp,
    val lg: Dp = 16.dp,
    val xl: Dp = 24.dp,
)

val LocalSpacing = staticCompositionLocalOf { AicodeSpacing() }

enum class StatusKind { SUCCESS, WARNING, DANGER, INFO }

@Composable
fun statusColor(kind: StatusKind): Color {
    val dark = isSystemInDarkTheme()
    return when (kind) {
        StatusKind.SUCCESS -> if (dark) AicodeColors.DarkSuccess else AicodeColors.LightSuccess
        StatusKind.WARNING -> if (dark) AicodeColors.DarkWarning else AicodeColors.LightWarning
        StatusKind.DANGER -> if (dark) AicodeColors.DarkDanger else AicodeColors.LightDanger
        StatusKind.INFO -> if (dark) AicodeColors.DarkInfo else AicodeColors.LightInfo
    }
}

@Composable
fun AicodemaxTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalSpacing provides AicodeSpacing()) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkScheme else LightScheme,
            typography = AicodeTypography,
            content = content,
        )
    }
}
