package io.github.fredjunior54.localbell.ui.screens

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.fredjunior54.localbell.R
import io.github.fredjunior54.localbell.service.NotificationForwarderService
import io.github.fredjunior54.localbell.ui.theme.spacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasNotificationAccess by remember { mutableStateOf(isNotificationServiceEnabled(context)) }
    var isBatteryOptimizationDisabled by remember {
        mutableStateOf(isBatteryOptimizationDisabled(context))
    }
    var showAboutDialog by remember { mutableStateOf(false) }
    var showBlacklist by remember { mutableStateOf(false) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasNotificationAccess = isNotificationServiceEnabled(context)
                isBatteryOptimizationDisabled = isBatteryOptimizationDisabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    if (showBlacklist) {
        BlacklistScreen(onClose = { showBlacklist = false })
        return
    }

    if (showAboutDialog) {
        AlertDialog(
            onDismissRequest = { showAboutDialog = false },
            title = { LocalBellWordmark() },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md),
                ) {
                    val logo = painterResource(R.drawable.app_logo)
                    Image(
                        painter = logo,
                        contentDescription = null,
                        modifier =
                            Modifier.fillMaxWidth()
                                .aspectRatio(
                                    logo.intrinsicSize.width / logo.intrinsicSize.height,
                                ),
                    )
                    Text(stringResource(R.string.about_description))
                    Column {
                        appVersionName(context)?.let { version ->
                            Text(
                                text = stringResource(R.string.about_version, version),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            text = stringResource(R.string.about_maintainer),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showAboutDialog = false
                        openProjectPage(context)
                    },
                ) {
                    Text(stringResource(R.string.about_project_page))
                }
            },
            dismissButton = {
                TextButton(onClick = { showAboutDialog = false }) {
                    Text(stringResource(R.string.about_close))
                }
            },
        )
    }

    MainScreenContent(
        hasNotificationAccess = hasNotificationAccess,
        isBatteryOptimizationDisabled = isBatteryOptimizationDisabled,
        onNotificationAccessClick = { openNotificationListenerSettings(context) },
        onBatteryOptimizationClick = { requestDisableBatteryOptimization(context) },
        onAboutClick = { showAboutDialog = true },
        onFilterClick = { showBlacklist = true },
    ) {
        ReverseSessionControls()
    }
}

/** Stateless content composable for MainScreen. Extracted for testability. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreenContent(
    hasNotificationAccess: Boolean,
    isBatteryOptimizationDisabled: Boolean,
    onNotificationAccessClick: () -> Unit,
    onBatteryOptimizationClick: () -> Unit,
    onAboutClick: () -> Unit,
    onFilterClick: () -> Unit,
    content: @Composable () -> Unit = {},
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { LocalBellWordmark() },
                actions = {
                    IconButton(onClick = onAboutClick) {
                        Icon(
                            Icons.AutoMirrored.Outlined.HelpOutline,
                            contentDescription = stringResource(R.string.about_action),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier.fillMaxSize()
                    .padding(padding)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Setup section
            SectionHeader("Setup")

            // Notification Access
            SettingRow(
                icon =
                    if (hasNotificationAccess) Icons.Default.Notifications
                    else Icons.Default.NotificationsOff,
                title = "Notification Access",
                state =
                    stringResource(
                        if (hasNotificationAccess) R.string.access_granted
                        else R.string.access_required,
                    ),
                isOk = hasNotificationAccess,
                onClick = onNotificationAccessClick,
            )

            // Battery Optimization
            SettingRow(
                icon =
                    if (isBatteryOptimizationDisabled) Icons.Default.BatterySaver
                    else Icons.Default.BatteryAlert,
                title = "Battery Optimization",
                state =
                    stringResource(
                        if (isBatteryOptimizationDisabled) R.string.battery_unrestricted
                        else R.string.battery_restricted,
                    ),
                isOk = isBatteryOptimizationDisabled,
                onClick = onBatteryOptimizationClick,
            )

            // App Filters
            Card(
                modifier = Modifier.fillMaxWidth().minimumInteractiveComponentSize(),
                onClick = onFilterClick,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(MaterialTheme.spacing.lg),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.width(MaterialTheme.spacing.lg))
                    Text(
                        "App Filters",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            content()
        }
    }
}

@Composable
internal fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingRow(
    icon: ImageVector,
    title: String,
    state: String,
    isOk: Boolean,
    onClick: (() -> Unit)?,
) {
    Card(
        modifier =
            Modifier.fillMaxWidth().minimumInteractiveComponentSize().semantics {
                stateDescription = state
            },
        onClick = onClick ?: {},
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(MaterialTheme.spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.width(MaterialTheme.spacing.lg))
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Icon(
                imageVector = if (isOk) Icons.Default.CheckCircle else Icons.Default.Error,
                contentDescription = null,
                modifier = Modifier.size(MaterialTheme.spacing.xl),
                tint =
                    if (isOk) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error,
            )
        }
    }
}

private fun isNotificationServiceEnabled(context: Context): Boolean {
    val manager = context.getSystemService(NotificationManager::class.java)
    return manager.isNotificationListenerAccessGranted(
        ComponentName(context, NotificationForwarderService::class.java),
    )
}

private fun openNotificationListenerSettings(context: Context) {
    val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
    context.startActivity(intent)
}

private fun appVersionName(context: Context): String? =
    context.packageManager
        .getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
        .versionName

private fun isBatteryOptimizationDisabled(context: Context): Boolean {
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    return powerManager.isIgnoringBatteryOptimizations(context.packageName)
}

private fun requestDisableBatteryOptimization(context: Context) {
    val intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
    context.startActivity(intent)
}

private fun openProjectPage(context: Context) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/fredjunior54/localbell"))
    context.startActivity(intent)
}
