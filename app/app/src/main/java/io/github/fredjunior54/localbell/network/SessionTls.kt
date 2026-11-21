package io.github.fredjunior54.localbell.network

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager

internal class SessionTls
private constructor(
    private val context: SSLContext,
    private val leaf: X509Certificate,
) {
    fun fingerprint(): ByteArray = MessageDigest.getInstance("SHA-256").digest(leaf.encoded)

    /** Takes ownership of the socket. The caller must enforce an overall handshake deadline. */
    fun accept(socket: Socket): SSLSocket {
        try {
            if (!permitsPeer(socket.inetAddress)) throw IOException("Unsupported peer address")
            val tls =
                context.socketFactory.createSocket(socket, null, socket.port, true) as SSLSocket
            tls.useClientMode = false
            tls.enabledProtocols = arrayOf("TLSv1.3")
            tls.sslParameters = tls.sslParameters.apply { applicationProtocols = arrayOf(ALPN) }
            tls.soTimeout = HANDSHAKE_IDLE_MS
            tls.tcpNoDelay = true
            tls.startHandshake()
            if (tls.applicationProtocol != ALPN) {
                throw SSLHandshakeException("Peer did not negotiate the LocalBell protocol")
            }
            return tls
        } catch (failure: Exception) {
            runCatching { socket.close() }
            throw failure
        }
    }

    companion object {
        private const val ALIAS = "localbell-session-identity-v1"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val ALPN = "localbell/1"
        private const val HANDSHAKE_IDLE_MS = 5_000

        @Synchronized
        fun load(): SessionTls {
            val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            if (!store.containsAlias(ALIAS)) {
                val spec =
                    KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                        .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                        .setDigests(KeyProperties.DIGEST_SHA256)
                        .build()
                KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE).apply {
                    initialize(spec)
                    generateKeyPair()
                }
            }
            val key =
                store.getKey(ALIAS, null) as? PrivateKey ?: error("Missing session private key")
            val leaf =
                store.getCertificate(ALIAS) as? X509Certificate
                    ?: error("Missing session certificate")
            val context =
                SSLContext.getInstance("TLS").apply {
                    init(arrayOf(SessionKeyManager(key, leaf)), null, null)
                }
            return SessionTls(context, leaf)
        }

        internal fun permitsPeer(address: InetAddress): Boolean {
            if (address !is Inet4Address) return false
            val octets = address.address
            val first = octets[0].toInt() and 0xff
            val second = octets[1].toInt() and 0xff
            return first == 10 ||
                (first == 172 && second in 16..31) ||
                (first == 192 && second == 168) ||
                (first == 169 && second == 254) ||
                (first == 100 && second in 64..127)
        }

        private class SessionKeyManager(
            private val key: PrivateKey,
            private val leaf: X509Certificate,
        ) : X509ExtendedKeyManager() {
            override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = null

            override fun chooseClientAlias(
                keyType: Array<out String>?,
                issuers: Array<out Principal>?,
                socket: Socket?,
            ) = null

            override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) =
                if (keyType == "EC") arrayOf(ALIAS) else null

            override fun chooseServerAlias(
                keyType: String?,
                issuers: Array<out Principal>?,
                socket: Socket?,
            ) = getServerAliases(keyType, issuers)?.firstOrNull()

            override fun getCertificateChain(alias: String?) =
                if (alias == ALIAS) arrayOf(leaf) else null

            override fun getPrivateKey(alias: String?) = if (alias == ALIAS) key else null
        }
    }
}
