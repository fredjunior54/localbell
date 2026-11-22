package io.github.fredjunior54.localbell.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenerStatusColorsTest {
    @Test
    fun `dark accents retain the requested colors and both themes meet text contrast`() {
        val dark = darkColorScheme()
        val light = lightColorScheme()
        assertEquals(
            Color(0xFFA6E3A1),
            ListenerStatusColors.text(true, dark.surfaceContainerHighest, dark.onSurface),
        )
        assertEquals(
            Color(0xFFFAB387),
            ListenerStatusColors.text(false, dark.surfaceContainerHighest, dark.onSurface),
        )
        for (scheme in listOf(dark, light)) {
            for (ready in listOf(false, true)) {
                val background = scheme.surfaceContainerHighest
                val accent = ListenerStatusColors.text(ready, background, scheme.onSurface)
                assertTrue(ListenerStatusColors.contrast(accent, background) >= 4.5f)
            }
        }
    }
}
