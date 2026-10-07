package com.agentx.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * One color role in the Forge design system.
 *
 * The palette is the single source of truth for every surface, text and accent
 * color the UI draws. Screens never hardcode a value: they read the [Forge*]
 * accessors below (or [MaterialTheme][androidx.compose.material3.MaterialTheme]),
 * which resolve against the palette [ForgeTheme] provides for the selected
 * brightness. Switching the theme mode therefore restyles the whole app at once.
 */
data class ForgePalette(
    /** Screen background behind every card and bar. */
    val canvas: Color,
    /** Cards, bars and drawers sitting on the canvas. */
    val surface: Color,
    /** Recessed blocks: icon tiles, code strips, inputs. */
    val surfaceVariant: Color,
    /** Hairline borders and dividers. */
    val border: Color,
    /** Primary text and foreground icons. */
    val ink: Color,
    /** Secondary text, hints and inactive icons. */
    val muted: Color,
    /** Primary accent: primary actions, selection, progress and "success". */
    val accent: Color,
    /** Link/accent secondary: periwinkle highlights and outlines. */
    val secondary: Color,
    /** Warning and pending states. */
    val amber: Color,
    /** Error and destructive states. */
    val danger: Color,
    /** Content drawn on top of an accent-filled surface. */
    val onAccent: Color,
)

/**
 * The dark Forge palette. This is the shipped AgentX identity and remains the
 * app's default appearance; every value here is the color the app has always
 * used in dark mode.
 */
val ForgeDarkPalette = ForgePalette(
    canvas = Color(0xFF07080B),
    surface = Color(0xFF0E1116),
    surfaceVariant = Color(0xFF161B23),
    border = Color(0xFF232A35),
    ink = Color(0xFFE7ECF2),
    muted = Color(0xFF8C97A6),
    accent = Color(0xFF7DF3C5),
    secondary = Color(0xFF7AA2F7),
    amber = Color(0xFFF5C77E),
    danger = Color(0xFFF07178),
    onAccent = Color(0xFF07080B),
)

/**
 * The light Forge palette: the same roles on bright surfaces, with each accent
 * darkened so small text keeps an AA contrast ratio (≥ 4.5:1) against the
 * canvas and cards, and [onAccent] flips to white so accent-filled buttons stay
 * readable in both brightnesses.
 */
val ForgeLightPalette = ForgePalette(
    canvas = Color(0xFFF6F7F9),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFECEEF2),
    border = Color(0xFFD3D9E2),
    ink = Color(0xFF11161D),
    muted = Color(0xFF55606F),
    accent = Color(0xFF0A7558),
    secondary = Color(0xFF3560C4),
    amber = Color(0xFF8A5A00),
    danger = Color(0xFFB62E36),
    onAccent = Color(0xFFFFFFFF),
)

/** The palette [ForgeTheme] installs for the resolved brightness. */
val LocalForgePalette = staticCompositionLocalOf { ForgeDarkPalette }

/** Screen background; resolves through the active palette. */
val ForgeCanvas: Color
    @Composable get() = LocalForgePalette.current.canvas

/** Card/bar surface; resolves through the active palette. */
val ForgeSurface: Color
    @Composable get() = LocalForgePalette.current.surface

/** Recessed block surface; resolves through the active palette. */
val ForgeSurfaceVariant: Color
    @Composable get() = LocalForgePalette.current.surfaceVariant

/** Hairline border; resolves through the active palette. */
val ForgeBorder: Color
    @Composable get() = LocalForgePalette.current.border

/** Primary text/icons; resolves through the active palette. */
val ForgeInk: Color
    @Composable get() = LocalForgePalette.current.ink

/** Secondary text/icons; resolves through the active palette. */
val ForgeMuted: Color
    @Composable get() = LocalForgePalette.current.muted

/** Primary mint accent; resolves through the active palette. */
val ForgeMint: Color
    @Composable get() = LocalForgePalette.current.accent

/** Periwinkle secondary accent; resolves through the active palette. */
val ForgePeriwinkle: Color
    @Composable get() = LocalForgePalette.current.secondary

/** Warning amber; resolves through the active palette. */
val ForgeAmber: Color
    @Composable get() = LocalForgePalette.current.amber

/** Error/danger; resolves through the active palette. */
val ForgeDanger: Color
    @Composable get() = LocalForgePalette.current.danger

/** Content drawn on an accent-filled surface (e.g. text on a mint button). */
val ForgeOnAccent: Color
    @Composable get() = LocalForgePalette.current.onAccent
