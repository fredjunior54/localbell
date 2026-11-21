package io.github.fredjunior54.localbell.network

import android.content.Context
import android.util.Log
import io.github.fredjunior54.localbell.util.CryptoUtils
import io.github.fredjunior54.localbell.util.LocalNetworkPermission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request

/**
 * Monitors server health and manages server URL discovery. Provides connection status to the UI and
 * notification service.
 */
class HealthMonitor private constructor(private val context: Context) {

    companion object {
        private const val TAG = "HealthMonitor"
        private const val HEALTH_CHECK_INTERVAL_MS = 120_000L // 2 minutes
        private const val HEALTH_CHECK_TIMEOUT_MS = 5_000L

        @Volatile private var instance: HealthMonitor? = null

        fun getInstance(context: Context): HealthMonitor {
            return instance
                ?: synchronized(this) {
                    instance ?: HealthMonitor(context.applicationContext).also { instance = it }
                }
        }
    }

    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val settingsRepository by lazy {
        io.github.fredjunior54.localbell.di.AppModule.settingsRepository
    }
    private val serviceDiscovery = ServiceDiscovery(context)
    private val discoveryMutex = Mutex()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _serverUrl = MutableStateFlow<String?>(null)
    val serverUrl: StateFlow<String?> = _serverUrl.asStateFlow()

    private var monitoringJob: Job? = null

    @Volatile private var currentFingerprint: String? = null

    /** Starts monitoring server health. Should be called when service is enabled. */
    fun startMonitoring() {
        if (monitoringJob?.isActive == true) return

        Log.d(TAG, "Starting health monitoring")
        monitoringJob = scope.launch {
            while (true) {
                checkHealth()
                delay(HEALTH_CHECK_INTERVAL_MS)
            }
        }
    }

    /** Stops monitoring server health. */
    fun stopMonitoring() {
        Log.d(TAG, "Stopping health monitoring")
        monitoringJob?.cancel()
        monitoringJob = null
        _connectionState.value = ConnectionState.DISCONNECTED
        _serverUrl.value = null
    }

    /** Forces an immediate health check. Should be called after server configuration is updated. */
    fun forceCheck() {
        scope.launch {
            Log.d(TAG, "Forcing immediate health check")
            checkHealth()
        }
    }

    @Volatile private var connectedToken: String? = null

    fun getServerUrlIfConnected(token: String): String? =
        _serverUrl.value.takeIf {
            _connectionState.value == ConnectionState.CONNECTED && connectedToken == token
        }

    private val healthMutex = Mutex()

    private suspend fun checkHealth() = healthMutex.withLock {
        checkHealthOnce()
    }

    private suspend fun checkHealthOnce() {
        val pairing = settingsRepository.pairing.first()
        if (pairing == null) {
            Log.d(TAG, "No fingerprint configured, skipping health check")
            _serverUrl.value = null
            _connectionState.value = ConnectionState.DISCONNECTED
            return
        }

        val token = pairing.token
        val fingerprint = pairing.fingerprint
        if (currentFingerprint != fingerprint || connectedToken != token) _serverUrl.value = null
        currentFingerprint = fingerprint

        if (LocalNetworkPermission.isMissing(context)) {
            Log.w(TAG, "Local network permission not granted, skipping health check")
            _serverUrl.value = null
            _connectionState.value = ConnectionState.DISCONNECTED
            return
        }

        // Try cached URL first
        val cachedUrl = _serverUrl.value ?: pairing.url
        if (performHealthCheck(cachedUrl, fingerprint, token)) {
            _serverUrl.value = cachedUrl
            connectedToken = token
            _connectionState.value = ConnectionState.CONNECTED
            return
        }
        if (settingsRepository.pairing.first()?.token != token) {
            _serverUrl.value = null
            _connectionState.value = ConnectionState.DISCONNECTED
            return
        }
        _connectionState.value = ConnectionState.CONNECTING
        val url = discoverServer(fingerprint)

        if (url != null && performHealthCheck(url, fingerprint, token)) {
            _serverUrl.value = url
            settingsRepository.setCachedServerUrl(url, token)
            connectedToken = token
            _connectionState.value = ConnectionState.CONNECTED
            Log.d(TAG, "Connected to server at $url")
        } else {
            _serverUrl.value = null
            _connectionState.value = ConnectionState.DISCONNECTED
            Log.w(TAG, "Could not connect to server")
        }
    }

    private suspend fun discoverServer(fingerprint: String): String? {
        return discoveryMutex.withLock {
            Log.d(TAG, "Discovering server...")
            serviceDiscovery.discoverServer(fingerprint)?.url
        }
    }

    private suspend fun performHealthCheck(
        url: String,
        fingerprint: String,
        token: String,
    ): Boolean {
        val endpoint = url.toHttpUrlOrNull() ?: return false
        if (
            !endpoint.isHttps ||
                !io.github.fredjunior54.localbell.util.QrCodeParser.isPrivateIpv4(endpoint.host)
        )
            return false
        return try {
            val client =
                CryptoUtils.createPinnedOkHttpClient(
                    fingerprint = fingerprint,
                    connectTimeoutMs = HEALTH_CHECK_TIMEOUT_MS,
                    readTimeoutMs = HEALTH_CHECK_TIMEOUT_MS,
                )
            val request =
                Request.Builder()
                    .url("$url/v1/health")
                    .header("Authorization", "Bearer $token")
                    .get()
                    .build()

            val response = client.newCall(request).execute()
            val success = response.isSuccessful
            val revoked = response.code == 401
            response.close()
            if (revoked) settingsRepository.clearPairingIfCurrent(token)

            if (success) {
                Log.d(TAG, "Health check passed for $url")
            } else {
                Log.w(TAG, "Health check failed for $url: ${response.code}")
            }
            success
        } catch (e: Exception) {
            Log.w(TAG, "Health check error for $url: ${e.message}")
            false
        }
    }
}
