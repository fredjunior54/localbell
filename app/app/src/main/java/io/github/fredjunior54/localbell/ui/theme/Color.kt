package io.github.fredjunior54.localbell.ui.theme

import android.content.Context
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme

internal val LocalBellLightColors = lightColorScheme()
internal val LocalBellDarkColors = darkColorScheme()

internal fun localBellColors(context: Context, darkTheme: Boolean, dynamicColor: Boolean) =
    when {
        dynamicColor && darkTheme -> dynamicDarkColorScheme(context)
        dynamicColor -> dynamicLightColorScheme(context)
        darkTheme -> LocalBellDarkColors
        else -> LocalBellLightColors
    }
