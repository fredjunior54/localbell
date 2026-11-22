package io.github.fredjunior54.localbell.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.math.max
import kotlin.math.min

/** Scoped connection accents; keep the surrounding card on the dynamic color scheme. */
internal object ListenerStatusColors {
    private val mochaGreen = Color(0xFFA6E3A1)
    private val mochaPeach = Color(0xFFFAB387)
    private val lightGreen = Color(0xFF275B2B)
    private val lightPeach = Color(0xFF83421E)
    private const val MIN_CONTRAST = 4.5f
    private const val LUMINANCE_OFFSET = 0.05f

    fun text(ready: Boolean, background: Color, foreground: Color): Color {
        val light = if (ready) mochaGreen else mochaPeach
        val dark = if (ready) lightGreen else lightPeach
        return when {
            contrast(light, background) >= MIN_CONTRAST -> light
            contrast(dark, background) >= MIN_CONTRAST -> dark
            else -> foreground
        }
    }

    internal fun contrast(first: Color, second: Color): Float {
        val a = first.luminance()
        val b = second.luminance()
        return (max(a, b) + LUMINANCE_OFFSET) / (min(a, b) + LUMINANCE_OFFSET)
    }
}
