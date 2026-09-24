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

/** Design tokens from the master UI spec การปรับ ui ครั้งใหญ่ §§69–72 (CP-135). */
object AicodeColors {
    // Light
    val LightBackground = Color(0xFFF7F7F8)
    val LightSurface = Color(0xFFFFFFFF)
    val LightPrimary = Color(0xFF4F6FFF)
    val LightPrimaryDark = Color(0xFF3D5BF0)
    val LightSecondary = Color(0xFF6B7280)
    val LightTertiary = Color(0xFF9CA3AF)
    val LightBorder = Color(0xFFE5E7EB)
    val LightSuccess = Color(0xFF16A34A)
    val LightWarning = Color(0xFFD97706)
    val LightDanger = Color(0xFFDC2626)
    val LightInfo = Color(0xFF0284C7)

    // Dark (§69: bg #0B0D10, accent #6C8CFF)
    val DarkBackground = Color(0xFF0B0D10)
    val DarkSurface = Color(0xFF15181D)
    val DarkSurfaceVariant = Color(0xFF1C2027)
    val DarkPrimary = Color(0xFF6C8CFF)
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
    primaryContainer = Color(0xFFE2E7FF),
    onPrimaryContainer = Color(0xFF1E2FAF),
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
    onPrimary = Color.White,
    primaryContainer = Color(0xFF2A3566),
    onPrimaryContainer = Color(0xFFDCE3FF),
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

/** Corner radii from §71: 8 / 12 / 14 / 20 / 24 + chat bubble 18. */
object AicodeRadii {
    val S = 8.dp
    val M = 12.dp
    val L = 14.dp
    val XL = 20.dp
    val XXL = 24.dp
    val ChatBubble = 18.dp
}

/** Layout tokens from §§70–72: 4dp grid, 48dp targets, chat widths. */
object AicodeSize {
    val MinTouch = 48.dp
    const val UserBubbleWidth = 0.82f
    const val AiBubbleWidth = 0.96f
}

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
