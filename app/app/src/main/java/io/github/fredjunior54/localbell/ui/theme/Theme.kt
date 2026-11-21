package io.github.fredjunior54.localbell.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext

@Composable
fun LocalBellTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colors = localBellColors(LocalContext.current, darkTheme, dynamicColor)
    CompositionLocalProvider(LocalSpacing provides DefaultSpacing) {
        MaterialTheme(
            colorScheme = colors,
            typography = LocalBellTypography,
            shapes = LocalBellShapes,
            content = content,
        )
    }
}
