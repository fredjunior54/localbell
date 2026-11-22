package io.github.fredjunior54.localbell.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import io.github.fredjunior54.localbell.R
import io.github.fredjunior54.localbell.ui.theme.WordmarkBackground
import io.github.fredjunior54.localbell.ui.theme.WordmarkBell
import io.github.fredjunior54.localbell.ui.theme.WordmarkLocal
import io.github.fredjunior54.localbell.ui.theme.spacing

@Composable
internal fun LocalBellWordmark() {
    val name = stringResource(R.string.app_name)
    val scheme = MaterialTheme.colorScheme
    val maxBackgroundLuminance = (WordmarkBell.luminance() + 0.05f) / 4.5f - 0.05f
    val background =
        if (
            scheme.surface.luminance() > maxBackgroundLuminance ||
                scheme.surfaceContainerHigh.luminance() > maxBackgroundLuminance
        ) {
            Modifier.background(WordmarkBackground, MaterialTheme.shapes.small)
                .padding(horizontal = MaterialTheme.spacing.sm, vertical = MaterialTheme.spacing.xs)
        } else {
            Modifier
        }
    Text(
        text =
            buildAnnotatedString {
                append(name)
                addStyle(SpanStyle(color = WordmarkLocal), 0, length)
                if (name.endsWith("Bell"))
                    addStyle(SpanStyle(color = WordmarkBell), length - "Bell".length, length)
            },
        modifier = background,
    )
}
