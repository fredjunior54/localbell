package io.github.fredjunior54.localbell.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.fredjunior54.localbell.R
import io.github.fredjunior54.localbell.data.SettingsRepository
import io.github.fredjunior54.localbell.di.AppModule
import io.github.fredjunior54.localbell.network.HealthMonitor
import io.github.fredjunior54.localbell.network.PairingClient
import io.github.fredjunior54.localbell.util.LanEndpoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
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

    val connectionState =
        combine(settingsRepository.pairing, healthMonitor.readiness) { pairing, readiness ->
                readiness.state.takeIf { pairing != null && readiness.token == pairing.token }
                    ?: HealthMonitor.ConnectionState.DISCONNECTED
            }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                HealthMonitor.ConnectionState.DISCONNECTED,
            )

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

    val savedEndpoint =
        settingsRepository.pairing
            .map { it?.url }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun updatePermissions(
        notificationAccess: Boolean,
        batteryOptDisabled: Boolean,
    ) {
        _hasNotificationAccess.value = notificationAccess
        _isBatteryOptimizationDisabled.value = batteryOptDisabled
    }

    fun refreshConnection() {
        val message = _pairingUi.value.message
        if (
            message == R.string.pairing_network_settings ||
                message == R.string.pairing_network_required
        ) {
            _pairingUi.value = PairingUi()
        }
        healthMonitor.forceCheck()
    }

    fun showNetworkPermissionError(showSettings: Boolean) {
        _pairingUi.value =
            PairingUi(
                message =
                    if (showSettings) R.string.pairing_network_settings
                    else R.string.pairing_network_required,
            )
    }

    fun setServiceEnabled(enabled: Boolean) {
        viewModelScope.launch {
            val pairing = settingsRepository.pairing.first()
            if (
                !enabled ||
                    (_hasNotificationAccess.value &&
                        pairing != null &&
                        healthMonitor.getServerUrlIfConnected(pairing.token) != null)
            ) {
                settingsRepository.setServiceEnabled(enabled)
            }
        }
    }

    internal data class PairingUi(
        val busy: Boolean = false,
        val comparison: String? = null,
        val message: Int? = null,
        val confirmed: Boolean = false,
    )

    private val _pairingUi = MutableStateFlow(PairingUi())
    internal val pairingUi = _pairingUi.asStateFlow()
    private var pairingJob: kotlinx.coroutines.Job? = null

    private var comparisonConfirmation: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    fun confirmComparison() {
        val state = _pairingUi.value
        if (state.comparison != null && comparisonConfirmation?.complete(Unit) == true) {
            _pairingUi.value = state.copy(confirmed = true)
        }
    }

    fun cancelPairing() {
        pairingJob?.cancel()
        _pairingUi.value = PairingUi()
    }

    fun pair(address: String) {
        if (pairingJob?.isCompleted == false) return
        val endpoint = LanEndpoint.parse(address)
        if (endpoint == null) {
            _pairingUi.value = PairingUi(message = R.string.pairing_invalid)
            return
        }
        _pairingUi.value = PairingUi(busy = true)
        pairingJob = viewModelScope.launch {
            try {
                PairingClient.connect(endpoint, settingsRepository) {
                    val confirmation = kotlinx.coroutines.CompletableDeferred<Unit>()
                    comparisonConfirmation = confirmation
                    _pairingUi.value = PairingUi(busy = true, comparison = it)
                    try {
                        confirmation.await()
                    } finally {
                        comparisonConfirmation = null
                    }
                }
                _pairingUi.value = PairingUi(message = R.string.pairing_success)
                healthMonitor.forceCheck()
            } catch (ex: kotlinx.coroutines.CancellationException) {
                throw ex
            } catch (ex: PairingClient.PairingFailure) {
                val message =
                    when (ex.reason) {
                        PairingClient.Failure.BUSY -> R.string.pairing_busy
                        PairingClient.Failure.REJECTED -> R.string.pairing_rejected
                        PairingClient.Failure.TIMEOUT -> R.string.pairing_timeout
                        PairingClient.Failure.PENDING -> R.string.pairing_pending
                        PairingClient.Failure.RATE_LIMITED -> R.string.pairing_rate_limited
                    }
                _pairingUi.value = PairingUi(message = message)
            } catch (_: java.io.IOException) {
                _pairingUi.value = PairingUi(message = R.string.pairing_connection_error)
            } catch (_: org.json.JSONException) {
                _pairingUi.value = PairingUi(message = R.string.pairing_connection_error)
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
