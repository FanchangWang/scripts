package com.chess.bot.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * 配色（品牌紫：primary 浅 #6750A4 / 深 #D0BCFF；动态取色默认关闭以保持品牌一致）。
 * 2026-09-07 UI 重构组A（A1 修复）：两套 scheme **显式补全全部 M3 token**——
 * 此前缺省 token 会回落 M3 baseline（浅色 outlineVariant≈#CAC4D0 在浅底上几乎不可见、
 * 深色正常 →「浅色模式边框颜色丢失」）。关键自定义：
 * - Light：background=#F3EDF7、surface=#FCF9FE、surfaceContainerLow=#F7F2FA（卡片底）、
 *   surfaceContainerHigh=#ECE6F0、**outlineVariant=#8F8999（较 baseline 加深，浅色下边框可见）**
 * - Dark：background=#131318、surfaceContainerLow=#1D1F26、outlineVariant=#4A4E58
 * 深浅值与交互目标稿 ui_redesign_mockup.html 的 CSS 变量一一对应。
 */
private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFD0BCFF),
    onPrimary = Color(0xFF381E72),
    primaryContainer = Color(0xFF4F378B),
    onPrimaryContainer = Color(0xFFEADDFF),
    inversePrimary = Color(0xFF6750A4),
    secondary = Color(0xFFCCC2DC),
    onSecondary = Color(0xFF332D41),
    secondaryContainer = Color(0xFF4A4458),
    onSecondaryContainer = Color(0xFFE7E0EC),
    tertiary = Color(0xFFEFB8C8),
    onTertiary = Color(0xFF492532),
    tertiaryContainer = Color(0xFF633B48),
    onTertiaryContainer = Color(0xFFFFD8E4),
    background = Color(0xFF131318),
    onBackground = Color(0xFFE8EAF0),
    surface = Color(0xFF16171D),
    onSurface = Color(0xFFE8EAF0),
    surfaceVariant = Color(0xFF2A2D36),
    onSurfaceVariant = Color(0xFF9AA0AD),
    surfaceContainerLowest = Color(0xFF0E0F13),
    surfaceContainerLow = Color(0xFF1D1F26),
    surfaceContainer = Color(0xFF212329),
    surfaceContainerHigh = Color(0xFF2A2D36),
    surfaceContainerHighest = Color(0xFF34373F),
    inverseSurface = Color(0xFFE8EAF0),
    inverseOnSurface = Color(0xFF313033),
    outline = Color(0xFF3D4048),
    outlineVariant = Color(0xFF4A4E58),
    surfaceTint = Color(0xFFD0BCFF),
    scrim = Color(0xFF000000),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF3B0C0C),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF6750A4),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFEADDFF),
    onPrimaryContainer = Color(0xFF21005D),
    inversePrimary = Color(0xFFD0BCFF),
    secondary = Color(0xFF625B71),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE8DEF8),
    onSecondaryContainer = Color(0xFF1D192B),
    tertiary = Color(0xFF7D5260),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFD8E4),
    onTertiaryContainer = Color(0xFF31111D),
    background = Color(0xFFF3EDF7),
    onBackground = Color(0xFF1B1B1F),
    surface = Color(0xFFFCF9FE),
    onSurface = Color(0xFF1B1B1F),
    surfaceVariant = Color(0xFFE7E0EC),
    onSurfaceVariant = Color(0xFF49454F),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F2FA),
    surfaceContainer = Color(0xFFF3EDF7),
    surfaceContainerHigh = Color(0xFFECE6F0),
    surfaceContainerHighest = Color(0xFFE6E0E9),
    inverseSurface = Color(0xFF313033),
    inverseOnSurface = Color(0xFFF4EFF4),
    outline = Color(0xFF79747E),
    outlineVariant = Color(0xFF8F8999),
    surfaceTint = Color(0xFF6750A4),
    scrim = Color(0xFF000000),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
)

/** 扩展语义色（success/danger/棋谱强调色等）的 CompositionLocal，深浅自适应。 */
val LocalExtendedColors = staticCompositionLocalOf { LightExtendedColors }

@Composable
fun ChessBotTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+（默认关闭：保持品牌紫不被系统取色覆盖）
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    CompositionLocalProvider(
        LocalExtendedColors provides if (darkTheme) DarkExtendedColors else LightExtendedColors
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}
