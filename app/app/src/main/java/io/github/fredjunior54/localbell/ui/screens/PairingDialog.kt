package io.github.fredjunior54.localbell.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import io.github.fredjunior54.localbell.R
import io.github.fredjunior54.localbell.ui.theme.spacing

@Composable
internal fun PairingDialog(
    state: MainScreenViewModel.PairingUi,
    connect: (String) -> Unit,
    confirmComparison: () -> Unit,
    close: () -> Unit,
) {
    var address by rememberSaveable { mutableStateOf("") }
    val success = state.message == R.string.pairing_success
    AlertDialog(
        onDismissRequest = close,
        title = { Text(stringResource(R.string.pairing_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md),
            ) {
                if (!success)
                    OutlinedTextField(
                        value = address,
                        onValueChange = { address = it.take(259) },
                        label = { Text(stringResource(R.string.pairing_address)) },
                        placeholder = { Text(stringResource(R.string.pairing_example)) },
                        enabled = !state.busy,
                        isError = state.message == R.string.pairing_invalid,
                        singleLine = true,
                    )
                if (state.busy) {
                    LinearProgressIndicator()
                    val comparison = state.comparison
                    Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                        if (comparison == null) Text(stringResource(R.string.pairing_connecting))
                        else {
                            Text(
                                stringResource(
                                    if (state.confirmed) R.string.pairing_waiting
                                    else R.string.pairing_compare,
                                ),
                            )
                            Text(comparison, style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
                state.message?.let {
                    Text(
                        stringResource(it),
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        color =
                            if (success) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            if (success) TextButton(onClick = close) { Text(stringResource(R.string.about_close)) }
            else if (state.comparison != null) {
                Button(onClick = confirmComparison, enabled = !state.confirmed) {
                    Text(stringResource(R.string.pairing_match))
                }
            } else
                Button(
                    onClick = { connect(address) },
                    enabled = !state.busy && address.isNotBlank(),
                ) {
                    Text(stringResource(R.string.pairing_connect))
                }
        },
        dismissButton = {
            if (!success)
                TextButton(onClick = close) { Text(stringResource(R.string.pairing_cancel)) }
        },
    )
}
