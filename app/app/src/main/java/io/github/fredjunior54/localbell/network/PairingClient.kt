package io.github.fredjunior54.localbell.network

import android.annotation.SuppressLint
import io.github.fredjunior54.localbell.data.SettingsRepository
import io.github.fredjunior54.localbell.util.CryptoUtils
import io.github.fredjunior54.localbell.util.LanEndpoint
import java.io.IOException
import java.net.Proxy
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

internal object PairingClient {
    enum class Failure {
        BUSY,
        REJECTED,
        TIMEOUT,
        PENDING,
        RATE_LIMITED,
    }

    class PairingFailure(val reason: Failure) : IOException()

    private val json = "application/json".toMediaType()
    private val hex = Regex("[0-9a-f]{64}")
    private val random = SecureRandom()

    private fun secret() = ByteArray(32).also(random::nextBytes).hex()

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    // Stop the handshake before the probe can send any HTTP data.
    @SuppressLint("CustomX509TrustManager") // Both trust callbacks always reject the handshake.
    private fun probe(url: HttpUrl): String {
        var observedLeaf: X509Certificate? = null
        val observer =
            object : X509TrustManager {
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
                    throw CertificateException("Client authentication is not supported")
                }

                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                    val leaf =
                        chain.firstOrNull()
                            ?: throw CertificateException("Missing server certificate")
                    leaf.checkValidity()
                    leaf.verify(leaf.publicKey)
                    observedLeaf = leaf
                    throw CertificateException("Pairing probe captured the leaf")
                }
            }
        val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(observer), null) }
        val client =
            OkHttpClient.Builder()
                .dns(LanEndpoint.dns)
                .proxy(Proxy.NO_PROXY)
                .followRedirects(false)
                .followSslRedirects(false)
                .callTimeout(5, TimeUnit.SECONDS)
                .sslSocketFactory(tls.socketFactory, observer)
                .hostnameVerifier { _, _ -> true }
                .build()
        try {
            client.newCall(Request.Builder().url(url).build()).execute().close()
            throw IOException("The pairing probe unexpectedly completed a TLS session")
        } catch (ex: SSLHandshakeException) {
            val leaf = observedLeaf ?: throw ex
            return MessageDigest.getInstance("SHA-256").digest(leaf.encoded).hex()
        }
    }

    suspend fun connect(
        endpoint: HttpUrl,
        settings: SettingsRepository,
        onComparison: suspend (String) -> Unit,
    ) =
        withContext(Dispatchers.IO) {
            val url = endpoint.newBuilder().addPathSegments("v3/pair").build()
            val pending = settings.pendingPair()
            if (pending != null) {
                if (pending.url != endpoint.toString()) throw PairingFailure(Failure.PENDING)
                try {
                    finish(settings, pending, url)
                    return@withContext
                } catch (ex: PairingFailure) {
                    if (ex.reason != Failure.REJECTED) throw ex
                    settings.savePendingPair(null)
                }
            }
            val fingerprint = probe(url)
            val client = pinned(fingerprint)
            val code = secret()
            val challenge = secret()
            val payload = JSONObject().put("code", code)
            var completing = false
            try {
                val nonce =
                    request(
                            client,
                            url,
                            "POST",
                            JSONObject()
                                .put("code", code)
                                .put("name", "Android phone")
                                .put("commitment", commitment(fingerprint, code, challenge)),
                        )
                        .getString("nonce")
                if (!hex.matches(nonce)) throw IOException("Invalid pairing nonce")
                val reveal = JSONObject().put("code", code).put("challenge", challenge)
                request(client, url, "PATCH", reveal)
                withTimeoutOrNull(115_000) {
                    onComparison(comparison(fingerprint, code, nonce, challenge))
                    while (!request(client, url, "PATCH", reveal).getBoolean("approved")) delay(
                        1000,
                    )
                } ?: throw PairingFailure(Failure.TIMEOUT)
                val pair =
                    SettingsRepository.PendingPair(endpoint.toString(), fingerprint, code, secret())
                withContext(NonCancellable) {
                    settings.savePendingPair(pair)
                    completing = true
                    finish(settings, pair, url)
                }
            } finally {
                if (!completing)
                    withContext(NonCancellable) {
                        runCatching { request(client, url, "DELETE", payload) }
                    }
            }
        }

    private fun pinned(fingerprint: String) =
        CryptoUtils.createPinnedOkHttpClient(fingerprint, 3000, 3000, 3000)
            .newBuilder()
            .callTimeout(5, TimeUnit.SECONDS)
            .build()

    private suspend fun finish(
        settings: SettingsRepository,
        pair: SettingsRepository.PendingPair,
        url: HttpUrl,
    ) =
        withContext(NonCancellable) {
            val payload = JSONObject().put("code", pair.code).put("token", pair.token)
            complete(pinned(pair.fingerprint), url, payload)
            settings.setServerConfig(pair.token, pair.fingerprint, pair.url)
        }

    private fun complete(client: OkHttpClient, url: HttpUrl, finish: JSONObject) {
        // The same proposed token makes retries safe, including after a receiver restart.
        repeat(2) {
            try {
                request(client, url, "PUT", finish)
                return
            } catch (ex: PairingFailure) {
                throw ex
            } catch (_: IOException) {
                // The server may have persisted the credential before the response was lost.
            }
        }
        request(client, url, "PUT", finish)
    }

    private fun request(
        client: OkHttpClient,
        url: HttpUrl,
        method: String,
        body: JSONObject,
    ): JSONObject =
        client
            .newCall(
                Request.Builder()
                    .url(url)
                    .method(method, body.toString().toRequestBody(json))
                    .build(),
            )
            .execute()
            .use { response ->
                when (response.code) {
                    409 -> throw PairingFailure(Failure.BUSY)
                    429 -> throw PairingFailure(Failure.RATE_LIMITED)
                    403,
                    410 -> throw PairingFailure(Failure.REJECTED)
                }
                if (!response.isSuccessful) throw IOException("Pairing request failed")
                JSONObject(response.peekBody(4096).string())
            }

    internal fun commitment(fingerprint: String, code: String, challenge: String): String =
        hash("localbell-commit-v3:$fingerprint:$code:$challenge").hex()

    internal fun comparison(
        fingerprint: String,
        code: String,
        nonce: String,
        challenge: String,
    ): String {
        val digest = hash("localbell-compare-v3:$fingerprint:$code:$nonce:$challenge")
        val value = java.nio.ByteBuffer.wrap(digest).int.toUInt()
        return (value % 1_000_000u).toString().padStart(6, '0')
    }

    private fun hash(message: String) =
        MessageDigest.getInstance("SHA-256").digest(message.toByteArray(Charsets.US_ASCII))
}
