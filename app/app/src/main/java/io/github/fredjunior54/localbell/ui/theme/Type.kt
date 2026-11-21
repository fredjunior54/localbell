package io.github.fredjunior54.localbell.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp

private val LocalBellSans = FontFamily.SansSerif

private fun TextStyle.withLocalBellType(size: Int) =
    copy(fontFamily = LocalBellSans, fontSize = size.sp)

// Reuse Material's weights and line heights with a compact, seven-size scale.
@Suppress("MagicNumber")
internal val LocalBellTypography =
    with(Typography()) {
        Typography(
            displayLarge = displayLarge.withLocalBellType(48),
            displayMedium = displayMedium.withLocalBellType(48),
            displaySmall = displaySmall.withLocalBellType(32),
            headlineLarge = headlineLarge.withLocalBellType(32),
            headlineMedium = headlineMedium.withLocalBellType(28),
            headlineSmall = headlineSmall.withLocalBellType(22),
            titleLarge = titleLarge.withLocalBellType(22),
            titleMedium = titleMedium.withLocalBellType(16),
            titleSmall = titleSmall.withLocalBellType(14),
            bodyLarge = bodyLarge.withLocalBellType(16),
            bodyMedium = bodyMedium.withLocalBellType(14),
            bodySmall = bodySmall.withLocalBellType(12),
            labelLarge = labelLarge.withLocalBellType(14),
            labelMedium = labelMedium.withLocalBellType(12),
            labelSmall = labelSmall.withLocalBellType(12),
        )
    }
