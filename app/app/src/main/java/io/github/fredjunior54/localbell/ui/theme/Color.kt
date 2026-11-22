package io.github.fredjunior54.localbell.ui.theme

import android.content.Context
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

internal val LocalBellLightColors = lightColorScheme()
internal val LocalBellDarkColors = darkColorScheme()

internal val WordmarkBackground = Color(0xFF1E1E2E)
internal val WordmarkLocal = Color.White
internal val WordmarkBell = Color(0xFF89B4FA)

internal fun localBellColors(context: Context, darkTheme: Boolean, dynamicColor: Boolean) =
    when {
        dynamicColor && darkTheme -> dynamicDarkColorScheme(context)
        dynamicColor -> dynamicLightColorScheme(context)
        darkTheme -> LocalBellDarkColors
        else -> LocalBellLightColors
    }
