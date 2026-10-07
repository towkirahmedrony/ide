package com.agentx.app.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Both Forge palettes back real UI text, so every text/background pair must
 * keep WCAG AA contrast (≥ 4.5:1) in light and dark. This is what guarantees
 * the light palette is safe to ship and that a future palette edit cannot
 * silently make Settings → Appearance unreadable in one of the modes.
 */
class ForgePaletteContrastTest {

    @Test
    fun `the dark palette keeps AA contrast for every text role`() {
        assertReadable(ForgeDarkPalette)
    }

    @Test
    fun `the light palette keeps AA contrast for every text role`() {
        assertReadable(ForgeLightPalette)
    }

    @Test
    fun `on-accent content is readable on its accent in both palettes`() {
        listOf(ForgeDarkPalette, ForgeLightPalette).forEach { palette ->
            assertTrue(
                contrast(palette.onAccent, palette.accent) >= 4.5,
                "onAccent on accent is below AA in ${if (palette === ForgeDarkPalette) "dark" else "light"}",
            )
        }
    }

    private fun assertReadable(palette: ForgePalette) {
        val textRoles = mapOf(
            "ink" to palette.ink,
            "muted" to palette.muted,
        )
        val backgrounds = mapOf(
            "canvas" to palette.canvas,
            "surface" to palette.surface,
            "surfaceVariant" to palette.surfaceVariant,
        )
        val accents = mapOf(
            "accent" to palette.accent,
            "secondary" to palette.secondary,
            "amber" to palette.amber,
            "danger" to palette.danger,
        )
        (textRoles + accents).forEach { (role, color) ->
            backgrounds.forEach { (background, back) ->
                val ratio = contrast(color, back)
                assertTrue(
                    ratio >= 4.5,
                    "$role on $background is ${"%.2f".format(ratio)}:1, below AA (4.5:1)",
                )
            }
        }
    }

    /** WCAG 2.x relative luminance. */
    private fun luminance(color: Color): Double {
        fun channel(value: Float): Double {
            val v = value.toDouble()
            return if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(color.red) +
            0.7152 * channel(color.green) +
            0.0722 * channel(color.blue)
    }

    /** WCAG 2.x contrast ratio, 1.0 (identical) .. 21.0 (black on white). */
    private fun contrast(first: Color, second: Color): Double {
        val a = luminance(first)
        val b = luminance(second)
        val high = maxOf(a, b)
        val low = minOf(a, b)
        return (high + 0.05) / (low + 0.05)
    }
}
