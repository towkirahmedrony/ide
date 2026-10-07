package com.agentx.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Resolves the selected [mode] against the device setting. This is the one
 * place that decides light vs dark, so the theme, the system bars and the
 * Appearance preview can never disagree about the current brightness.
 */
@Composable
fun resolveDarkTheme(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

/**
 * Material3 scheme derived from the active [palette], so the M3 defaults
 * (ripples, `TextButton` content, `RadioButton` selection, `Button` containers)
 * resolve to the same colors the screens use directly through [Forge*][ForgeCanvas].
 */
private fun forgeColorScheme(palette: ForgePalette, dark: Boolean): ColorScheme {
    if (dark) {
        return darkColorScheme(
            primary = palette.accent,
            onPrimary = palette.onAccent,
            secondary = palette.secondary,
            onSecondary = palette.onAccent,
            background = palette.canvas,
            onBackground = palette.ink,
            surface = palette.surface,
            onSurface = palette.ink,
            surfaceVariant = palette.surfaceVariant,
            onSurfaceVariant = palette.muted,
            outline = palette.border,
            error = palette.danger,
            onError = palette.onAccent,
        )
    }
    return lightColorScheme(
        primary = palette.accent,
        onPrimary = palette.onAccent,
        secondary = palette.secondary,
        onSecondary = palette.onAccent,
        background = palette.canvas,
        onBackground = palette.ink,
        surface = palette.surface,
        onSurface = palette.ink,
        surfaceVariant = palette.surfaceVariant,
        onSurfaceVariant = palette.muted,
        outline = palette.border,
        error = palette.danger,
        onError = palette.onAccent,
    )
}

private val ForgeTypography = Typography(
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp,
        lineHeight = 38.sp,
        letterSpacing = (-0.5).sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.6.sp,
    ),
)

/**
 * The AgentX theme. Applies the palette and Material3 scheme for [themeMode]
 * and provides them to the whole tree, so one change on Settings → Appearance
 * restyles every screen immediately.
 *
 * [ThemeMode.DARK] is the default, which is exactly the appearance AgentX has
 * always shipped with.
 */
@Composable
fun ForgeTheme(
    themeMode: ThemeMode = ThemeMode.DARK,
    content: @Composable () -> Unit,
) {
    val dark = resolveDarkTheme(themeMode)
    val palette = if (dark) ForgeDarkPalette else ForgeLightPalette
    CompositionLocalProvider(LocalForgePalette provides palette) {
        MaterialTheme(
            colorScheme = forgeColorScheme(palette, dark),
            typography = ForgeTypography,
            content = content,
        )
    }
}
