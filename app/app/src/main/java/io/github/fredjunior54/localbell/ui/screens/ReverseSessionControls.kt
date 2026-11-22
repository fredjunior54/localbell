package io.github.fredjunior54.localbell.ui.screens

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Phonelink
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.fredjunior54.localbell.R
import io.github.fredjunior54.localbell.network.NotificationRequest
import io.github.fredjunior54.localbell.network.PhoneSessionRuntime
import io.github.fredjunior54.localbell.network.SessionDelivery
import io.github.fredjunior54.localbell.service.PhoneSessionService
import io.github.fredjunior54.localbell.service.sessionPayload
import io.github.fredjunior54.localbell.service.withAppArtwork
import io.github.fredjunior54.localbell.ui.theme.ListenerStatusColors
import io.github.fredjunior54.localbell.ui.theme.spacing
import io.github.fredjunior54.localbell.util.LocalNetworkPermission
import java.util.UUID
import kotlinx.coroutines.delay

@Composable
internal fun ReverseSessionControls() {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("listener-v1", 0) }
    var port by rememberSaveable {
        mutableStateOf(prefs.getInt("port", PhoneSessionService.DEFAULT_PORT))
    }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showUnpair by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }
    val runtime by PhoneSessionService.sessions.collectAsStateWithLifecycle()
    val status =
        runtime?.status?.collectAsStateWithLifecycle()?.value ?: PhoneSessionRuntime.Status()
    val start: (Int) -> Unit = { value ->
        error =
            runCatching {
                context.startForegroundService(
                    Intent(context, PhoneSessionService::class.java)
                        .putExtra(PhoneSessionService.EXTRA_PORT, value),
                )
            }
                .exceptionOrNull()
                ?.let { R.string.listener_start_failed }
    }
    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) start(port) else error = R.string.listener_permission_denied
        }
    LaunchedEffect(Unit) {
        if (!LocalNetworkPermission.isMissing(context)) start(port)
    }
    if (showSettings)
        ListenerPortDialog(port, { showSettings = false }) { value ->
            port = value
            prefs.edit().putInt("port", value).apply()
            showSettings = false
            if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN &&
                    LocalNetworkPermission.isMissing(context)
            ) {
                permission.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
            } else start(value)
        }
    status.comparison?.let { number ->
        PairingApprovalDialog(number, status.approvalDeadline) { matches ->
            runtime?.approve(matches, status.comparisonId)
        }
    }
    if (showUnpair) {
        AlertDialog(
            onDismissRequest = { showUnpair = false },
            title = { Text(stringResource(R.string.listener_unpair)) },
            text = { Text(stringResource(R.string.listener_unpair_confirmation)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        error =
                            runCatching { runtime?.unpair() }
                                .exceptionOrNull()
                                ?.let { R.string.listener_unpair_failed }
                        showUnpair = false
                    },
                ) {
                    Text(stringResource(R.string.listener_unpair_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { showUnpair = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
    ListenerAction(Icons.Outlined.Settings, R.string.listener_settings) { showSettings = true }
    if (status.paired)
        ListenerAction(Icons.Outlined.Delete, R.string.listener_unpair_action) { showUnpair = true }
    val title = stringResource(R.string.test_notification_title)
    val message = stringResource(R.string.test_notification_sample)
    ListenerAction(
        Icons.Outlined.Notifications,
        R.string.test_notification_send,
        status.phase == PhoneSessionRuntime.Phase.READY,
    ) {
        val key = "localbell-test-${UUID.randomUUID()}"
        val item =
            NotificationRequest(key, "LocalBell", context.packageName, title, message)
                .withAppArtwork(context)
        error =
            if (runtime?.offer(SessionDelivery.Kind.TEST, key, item.sessionPayload()) == true) null
            else R.string.listener_test_failed
    }
    SectionHeader(stringResource(R.string.listener_status_title))
    ListenerStatus(port, status)
    error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
}

@Composable
private fun ListenerPortDialog(port: Int, dismiss: () -> Unit, save: (Int) -> Unit) {
    var text by rememberSaveable { mutableStateOf(port.toString()) }
    val parsed = text.toIntOrNull()?.takeIf { it in 1..65535 }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(stringResource(R.string.listener_settings)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { if (it.length <= 5 && it.all(Char::isDigit)) text = it },
                label = { Text(stringResource(R.string.pairing_port)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        },
        confirmButton = {
            TextButton(enabled = parsed != null, onClick = { parsed?.let(save) }) {
                Text(stringResource(R.string.listener_start))
            }
        },
        dismissButton = {
            TextButton(onClick = dismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

@Composable
internal fun PairingApprovalDialog(number: String, deadline: Long?, decide: (Boolean) -> Unit) {
    AlertDialog(
        onDismissRequest = { decide(false) },
        title = { Text(stringResource(R.string.listener_compare_title)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    number,
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                )
                if (deadline == null) {
                    Text(stringResource(R.string.listener_compare_instructions))
                } else {
                    Text(stringResource(R.string.listener_waiting_approval))
                    PairingCountdown(deadline)
                }
            }
        },
        confirmButton = {
            if (deadline == null) {
                TextButton(onClick = { decide(true) }) {
                    Text(stringResource(R.string.pairing_match))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = { decide(false) }) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}

internal fun approvalTimeRemaining(deadline: Long, now: Long): Long =
    (deadline - now).coerceIn(0, PhoneSessionRuntime.FINAL_APPROVAL_MS)

@Composable
private fun PairingCountdown(deadline: Long) {
    var remaining by
        remember(deadline) {
            mutableStateOf(approvalTimeRemaining(deadline, SystemClock.elapsedRealtime()))
        }
    LaunchedEffect(deadline) {
        while (remaining > 0) {
            delay(minOf(remaining, 100L))
            remaining = approvalTimeRemaining(deadline, SystemClock.elapsedRealtime())
        }
    }
    LinearProgressIndicator(
        progress = { remaining.toFloat() / PhoneSessionRuntime.FINAL_APPROVAL_MS },
        modifier = Modifier.fillMaxWidth(),
    )
    val seconds = ((remaining + 999L) / 1000L).toInt()
    Text(
        androidx.compose.ui.res.pluralStringResource(
            R.plurals.listener_approval_seconds,
            seconds,
            seconds,
        ),
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun ListenerAction(
    icon: ImageVector,
    label: Int,
    enabled: Boolean = true,
    click: () -> Unit,
) {
    Card(onClick = click, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(MaterialTheme.spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                stringResource(label),
                modifier = Modifier.padding(start = MaterialTheme.spacing.lg),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

@Composable
internal fun ListenerStatus(port: Int, status: PhoneSessionRuntime.Status) {
    val container = MaterialTheme.colorScheme.surfaceContainerHighest
    val foreground = MaterialTheme.colorScheme.onSurface
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = container),
    ) {
        Column(
            modifier = Modifier.padding(MaterialTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md),
        ) {
            ListenerPhase(status.phase, container, foreground)
            Column {
                Text(
                    stringResource(R.string.listener_count, status.forwarded),
                    style = MaterialTheme.typography.headlineLarge,
                )
                Text(
                    stringResource(R.string.listener_forwarded),
                    style = MaterialTheme.typography.bodySmall,
                    color = secondary,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs)) {
                status.address?.let {
                    ListenerDetail(stringResource(R.string.listener_peer_status, it))
                }
                ListenerDetail(stringResource(R.string.listener_port_status, port))
                status.outcomeTime?.let { stamp ->
                    val time =
                        android.text.format.DateFormat.getTimeFormat(LocalContext.current)
                            .format(java.util.Date(stamp))
                    val outcome =
                        stringResource(
                            if (status.accepted == true) R.string.listener_outcome_accepted
                            else R.string.listener_outcome_failed,
                        )
                    ListenerDetail(stringResource(R.string.listener_outcome_status, outcome, time))
                }
            }
            if (status.testAccepted == false) {
                Text(
                    stringResource(R.string.listener_test_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun ListenerPhase(
    phase: PhoneSessionRuntime.Phase,
    background: Color,
    foreground: Color,
) {
    val label =
        when (phase) {
            PhoneSessionRuntime.Phase.STARTING -> R.string.listener_starting
            PhoneSessionRuntime.Phase.LISTENING -> R.string.listener_waiting
            PhoneSessionRuntime.Phase.PAIRING -> R.string.listener_pairing
            PhoneSessionRuntime.Phase.UNAVAILABLE -> R.string.listener_unavailable
            PhoneSessionRuntime.Phase.READY -> R.string.listener_ready
            PhoneSessionRuntime.Phase.FAILED -> R.string.listener_start_failed
            PhoneSessionRuntime.Phase.STOPPED -> R.string.listener_stopped
        }
    val color =
        when (phase) {
            PhoneSessionRuntime.Phase.READY ->
                ListenerStatusColors.text(true, background, foreground)
            PhoneSessionRuntime.Phase.LISTENING ->
                ListenerStatusColors.text(false, background, foreground)
            else -> foreground
        }
    Row(
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.Phonelink,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(label),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = color,
        )
    }
}

@Composable
private fun ListenerDetail(value: String) {
    Text(
        value,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
