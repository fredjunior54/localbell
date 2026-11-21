package io.github.fredjunior54.localbell.ui.screens

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.fredjunior54.localbell.R
import io.github.fredjunior54.localbell.network.HealthMonitor
import io.github.fredjunior54.localbell.service.NotificationForwarderService
import io.github.fredjunior54.localbell.ui.theme.spacing
import io.github.fredjunior54.localbell.util.LocalNetworkPermission

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainScreenViewModel = viewModel(factory = MainScreenViewModel.Factory)) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val isConfigured by viewModel.isConfigured.collectAsStateWithLifecycle()
    val serviceEnabled by viewModel.serviceEnabled.collectAsStateWithLifecycle()
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val serverUrl by viewModel.serverUrl.collectAsStateWithLifecycle()
    val hasNotificationAccess by viewModel.hasNotificationAccess.collectAsStateWithLifecycle()
    val isBatteryOptimizationDisabled by
        viewModel.isBatteryOptimizationDisabled.collectAsStateWithLifecycle()
    val blacklistedAppsCount by viewModel.blacklistedAppsCount.collectAsStateWithLifecycle()
    val savedEndpoint by viewModel.savedEndpoint.collectAsStateWithLifecycle()
    val activity = LocalActivity.current

    var showPairing by rememberSaveable { mutableStateOf(false) }
    var pendingEndpoint by rememberSaveable { mutableStateOf<String?>(null) }
    var showAboutDialog by remember { mutableStateOf(false) }
    var showBlacklist by remember { mutableStateOf(false) }

    // Forwarding works without it, but the foreground service notification stays hidden
    val notificationPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    // Android 17 blocks discovery and the server connection until this is granted
    val localNetworkPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted
            ->
            val requested = pendingEndpoint
            pendingEndpoint = null
            if (isGranted) {
                cancelLocalNetworkNotification(context)
                requested?.let(viewModel::pair)
                viewModel.refreshConnection()
            } else if (
                requested != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN
            ) {
                val showSettings =
                    activity != null &&
                        !ActivityCompat.shouldShowRequestPermissionRationale(
                            activity,
                            Manifest.permission.ACCESS_LOCAL_NETWORK,
                        )
                viewModel.showNetworkPermissionError(showSettings)
            }
        }

    // Check permissions when app resumes (lifecycle-aware, no polling!)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.updatePermissions(
                    notificationAccess = isNotificationServiceEnabled(context),
                    batteryOptDisabled = isBatteryOptimizationDisabled(context),
                )
                val localNetworkGranted = !LocalNetworkPermission.isMissing(context)
                if (localNetworkGranted) {
                    cancelLocalNetworkNotification(context)
                    viewModel.refreshConnection()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (showBlacklist) {
        BlacklistScreen(onClose = { showBlacklist = false })
        return
    }

    if (showPairing) {
        val pairing by viewModel.pairingUi.collectAsStateWithLifecycle()
        PairingDialog(
            state = pairing,
            savedEndpoint = savedEndpoint,
            connect = { endpoint ->
                if (
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN &&
                        LocalNetworkPermission.isMissing(context)
                ) {
                    pendingEndpoint = endpoint
                    localNetworkPermissionLauncher.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
                } else viewModel.pair(endpoint)
            },
            confirmComparison = viewModel::confirmComparison,
            openSettings = { openAppSettings(context) },
        ) {
            pendingEndpoint = null
            viewModel.cancelPairing()
            showPairing = false
        }
    }

    if (showAboutDialog) {
        AlertDialog(
            onDismissRequest = { showAboutDialog = false },
            title = { Text(stringResource(R.string.app_name)) },
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
        isConfigured = isConfigured,
        serviceEnabled = serviceEnabled,
        connectionState = connectionState,
        serverUrl = serverUrl,
        hasNotificationAccess = hasNotificationAccess,
        isBatteryOptimizationDisabled = isBatteryOptimizationDisabled,
        onNotificationAccessClick = { openNotificationListenerSettings(context) },
        onBatteryOptimizationClick = { requestDisableBatteryOptimization(context) },
        onServerConfigClick = { showPairing = true },
        onServiceEnabledChange = { enabled ->
            if (enabled && !hasNotificationPermission(context)) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            viewModel.setServiceEnabled(enabled)
        },
        onAboutClick = { showAboutDialog = true },
        onFilterClick = { showBlacklist = true },
        blacklistedAppsCount = blacklistedAppsCount,
    )
}

/** Stateless content composable for MainScreen. Extracted for testability. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreenContent(
    isConfigured: Boolean,
    serviceEnabled: Boolean,
    connectionState: HealthMonitor.ConnectionState,
    serverUrl: String?,
    hasNotificationAccess: Boolean,
    isBatteryOptimizationDisabled: Boolean,
    onNotificationAccessClick: () -> Unit,
    onBatteryOptimizationClick: () -> Unit,
    onServerConfigClick: () -> Unit,
    onServiceEnabledChange: (Boolean) -> Unit,
    onAboutClick: () -> Unit,
    onFilterClick: () -> Unit,
    blacklistedAppsCount: Int = 0,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("LocalBell") },
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
                subtitle = if (hasNotificationAccess) "Granted" else "Tap to grant",
                isOk = hasNotificationAccess,
                onClick = onNotificationAccessClick,
            )

            // Battery Optimization
            SettingRow(
                icon =
                    if (isBatteryOptimizationDisabled) Icons.Default.BatterySaver
                    else Icons.Default.BatteryAlert,
                title = "Battery Optimization",
                subtitle = if (isBatteryOptimizationDisabled) "Unrestricted" else "Tap to disable",
                isOk = isBatteryOptimizationDisabled,
                onClick = onBatteryOptimizationClick,
            )

            // Server configuration
            Card(modifier = Modifier.fillMaxWidth(), onClick = onServerConfigClick) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Link,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Server Configuration",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text =
                                if (isConfigured) stringResource(R.string.pairing_configured)
                                else stringResource(R.string.pairing_enter_address),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(
                        imageVector =
                            if (isConfigured) Icons.Default.CheckCircle else Icons.Default.Error,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint =
                            if (isConfigured) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error,
                    )
                }
            }

            // App Filters
            Card(modifier = Modifier.fillMaxWidth(), onClick = onFilterClick) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "App Filters",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text =
                                if (blacklistedAppsCount == 0) "All apps forwarded"
                                else
                                    "$blacklistedAppsCount app${if (blacklistedAppsCount == 1) "" else "s"} blocked",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // Status section
            SectionHeader("Status")

            // Server Connection Status
            SettingRow(
                icon =
                    when (connectionState) {
                        HealthMonitor.ConnectionState.CONNECTED -> Icons.Default.Cloud
                        HealthMonitor.ConnectionState.CONNECTING -> Icons.Default.Sync
                        HealthMonitor.ConnectionState.DISCONNECTED -> Icons.Default.CloudOff
                    },
                title = "Server Status",
                subtitle =
                    when {
                        !isConfigured -> "Configure first"
                        connectionState == HealthMonitor.ConnectionState.CONNECTED ->
                            serverUrl ?: "Connected"
                        connectionState == HealthMonitor.ConnectionState.CONNECTING ->
                            "Discovering..."
                        else -> "Not found"
                    },
                isOk = connectionState == HealthMonitor.ConnectionState.CONNECTED,
                onClick = null,
            )

            // Forward Switch
            val isForwardingActive =
                serviceEnabled &&
                    hasNotificationAccess &&
                    isConfigured &&
                    connectionState == HealthMonitor.ConnectionState.CONNECTED

            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector =
                            if (isForwardingActive) Icons.Default.CheckCircle
                            else Icons.Default.Error,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint =
                            if (isForwardingActive) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error,
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "Forwarding", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text =
                                when {
                                    !hasNotificationAccess -> "Grant access first"
                                    !isConfigured -> "Configure first"
                                    connectionState != HealthMonitor.ConnectionState.CONNECTED ->
                                        "Waiting for server..."
                                    !serviceEnabled -> "Disabled"
                                    else -> "Active"
                                },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = serviceEnabled,
                        onCheckedChange = onServiceEnabledChange,
                        enabled =
                            hasNotificationAccess &&
                                isConfigured &&
                                connectionState == HealthMonitor.ConnectionState.CONNECTED,
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
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
    subtitle: String,
    isOk: Boolean,
    onClick: (() -> Unit)?,
) {
    Card(modifier = Modifier.fillMaxWidth(), onClick = onClick ?: {}) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = if (isOk) Icons.Default.CheckCircle else Icons.Default.Error,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
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

private fun cancelLocalNetworkNotification(context: Context) {
    NotificationManagerCompat.from(context)
        .cancel(NotificationForwarderService.LOCAL_NETWORK_NOTIFICATION_ID)
}

private fun hasNotificationPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED

private fun isBatteryOptimizationDisabled(context: Context): Boolean {
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    return powerManager.isIgnoringBatteryOptimizations(context.packageName)
}

private fun openAppSettings(context: Context) {
    val intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
    context.startActivity(intent)
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
