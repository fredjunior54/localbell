package io.github.fredjunior54.localbell.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp

@Suppress("MagicNumber")
internal class Spacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 20.dp
    val xxl = 24.dp
    val xxxl = 32.dp
    val large = 40.dp
    val touchTarget = 48.dp
    val section = 56.dp
}

internal val DefaultSpacing = Spacing()
internal val LocalSpacing = staticCompositionLocalOf { DefaultSpacing }

internal val MaterialTheme.spacing: Spacing
    @Composable @ReadOnlyComposable get() = LocalSpacing.current
