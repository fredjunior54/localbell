package io.github.fredjunior54.localbell.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.fredjunior54.localbell.data.SettingsRepository
import io.github.fredjunior54.localbell.di.AppModule
import io.github.fredjunior54.localbell.network.HealthMonitor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainScreenViewModel(
    private val settingsRepository: SettingsRepository,
    private val healthMonitor: HealthMonitor,
) : ViewModel() {

    val isConfigured =
        settingsRepository.isConfigured.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            false,
        )

    val serviceEnabled =
        settingsRepository.serviceEnabled.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            false,
        )

    val connectionState: StateFlow<HealthMonitor.ConnectionState> = healthMonitor.connectionState

    val serverUrl: StateFlow<String?> = healthMonitor.serverUrl

    val blacklistedAppsCount: StateFlow<Int> =
        settingsRepository.blacklistedApps
            .map { it.size }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private val _hasNotificationAccess = MutableStateFlow(false)
    val hasNotificationAccess: StateFlow<Boolean> = _hasNotificationAccess.asStateFlow()

    private val _isBatteryOptimizationDisabled = MutableStateFlow(false)
    val isBatteryOptimizationDisabled: StateFlow<Boolean> =
        _isBatteryOptimizationDisabled.asStateFlow()

    private val _hasCameraPermission = MutableStateFlow(false)
    val hasCameraPermission: StateFlow<Boolean> = _hasCameraPermission.asStateFlow()

    private val _hasLocalNetworkPermission = MutableStateFlow(true)
    val hasLocalNetworkPermission: StateFlow<Boolean> = _hasLocalNetworkPermission.asStateFlow()

    val localNetworkRequested =
        settingsRepository.localNetworkRequested.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            false,
        )

    fun updatePermissions(
        notificationAccess: Boolean,
        batteryOptDisabled: Boolean,
        cameraPermission: Boolean = _hasCameraPermission.value,
    ) {
        _hasNotificationAccess.value = notificationAccess
        _isBatteryOptimizationDisabled.value = batteryOptDisabled
        _hasCameraPermission.value = cameraPermission
    }

    fun setCameraPermission(granted: Boolean) {
        _hasCameraPermission.value = granted
    }

    fun setLocalNetworkPermission(granted: Boolean) {
        val newlyGranted = granted && !_hasLocalNetworkPermission.value
        _hasLocalNetworkPermission.value = granted
        if (newlyGranted) {
            healthMonitor.forceCheck()
        }
    }

    fun markLocalNetworkRequested() {
        viewModelScope.launch { settingsRepository.setLocalNetworkRequested() }
    }

    fun setServiceEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setServiceEnabled(enabled) }
    }

    private val _pairingMessage = MutableStateFlow<String?>(null)
    val pairingMessage = _pairingMessage.asStateFlow()
    private var pairingJob: kotlinx.coroutines.Job? = null

    fun pair(qrData: String) {
        pairingJob?.cancel()
        val pairing = io.github.fredjunior54.localbell.util.QrCodeParser.parse(qrData)
        if (pairing == null) {
            _pairingMessage.value = "Invalid LocalBell QR code. Generate a new code on the desktop."
            return
        }
        pairingJob = viewModelScope.launch {
            _pairingMessage.value = "Pairing with LocalBell…"
            try {
                val token =
                    io.github.fredjunior54.localbell.network.PairingClient.exchange(
                        pairing,
                        android.os.Build.MODEL,
                    )
                settingsRepository.setServerConfig(token, pairing.fingerprint, pairing.url)
                _pairingMessage.value = null
                healthMonitor.forceCheck()
            } catch (ex: kotlinx.coroutines.CancellationException) {
                throw ex
            } catch (_: Exception) {
                _pairingMessage.value =
                    "Could not pair. Check the local network and scan a new code."
            }
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return MainScreenViewModel(
                        settingsRepository = AppModule.settingsRepository,
                        healthMonitor = AppModule.healthMonitor,
                    )
                        as T
                }
            }
    }
}
