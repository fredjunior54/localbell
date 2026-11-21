package io.github.fredjunior54.localbell.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorContrastTest {
    @Test
    fun `fallback schemes meet contrast for text roles and outlines`() {
        for ((mode, colors) in
            listOf("light" to LocalBellLightColors, "dark" to LocalBellDarkColors)) {
            for ((background, text) in textPairs(colors)) {
                assertContrast(mode, background, text, 4.5)
            }
            assertContrast(mode, colors.surface, colors.outline, 3.0)
        }
    }

    private fun textPairs(colors: ColorScheme) =
        with(colors) {
            listOf(
                primary to onPrimary,
                primaryContainer to onPrimaryContainer,
                secondary to onSecondary,
                secondaryContainer to onSecondaryContainer,
                tertiary to onTertiary,
                tertiaryContainer to onTertiaryContainer,
                error to onError,
                errorContainer to onErrorContainer,
                background to onBackground,
                surface to onSurface,
                surfaceVariant to onSurfaceVariant,
                surfaceContainerLowest to onSurface,
                surfaceContainerLow to onSurface,
                surfaceContainer to onSurface,
                surfaceContainerHigh to onSurface,
                surfaceContainerHighest to onSurface,
                inverseSurface to inverseOnSurface,
            )
        }

    private fun assertContrast(
        mode: String,
        background: Color,
        foreground: Color,
        minimum: Double,
    ) {
        val (darker, lighter) = listOf(background.luminance(), foreground.luminance()).sorted()
        val ratio = (lighter + LUMINANCE_OFFSET) / (darker + LUMINANCE_OFFSET)
        assertTrue(
            "$mode: $foreground on $background has contrast $ratio; expected $minimum",
            ratio >= minimum,
        )
    }

    private companion object {
        const val LUMINANCE_OFFSET = 0.05
    }
}
