package io.github.fredjunior54.localbell.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import io.github.fredjunior54.localbell.R
import io.github.fredjunior54.localbell.ui.theme.spacing

@Composable
internal fun TestNotificationAction(
    state: MainScreenViewModel.TestNotificationUi,
    ready: Boolean,
    send: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)) {
        OutlinedButton(onClick = send, enabled = ready && !state.sending) {
            Text(
                stringResource(
                    if (state.sending) R.string.test_notification_sending
                    else R.string.test_notification_send,
                ),
            )
        }
        state.message?.let {
            Text(
                stringResource(it),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                color =
                    if (it == R.string.test_notification_failed) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
