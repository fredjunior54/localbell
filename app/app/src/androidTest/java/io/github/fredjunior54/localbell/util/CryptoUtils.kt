package io.github.fredjunior54.localbell.util

import android.annotation.SuppressLint
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

object CryptoUtils {
    fun calculateFingerprint(cert: X509Certificate): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(cert.encoded)
        return digest.joinToString("") { "%02X".format(it) }
    }

    fun calculateFingerprint(certBytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(certBytes)
        return digest.joinToString("") { "%02X".format(it) }
    }

    fun fingerprintsMatch(a: String, b: String): Boolean {
        return a.equals(b, ignoreCase = true)
    }

    /**
     * Creates an X509TrustManager that validates server certificates against an expected
     * fingerprint. Used for certificate pinning.
     */
    @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
    fun createPinningTrustManager(expectedFingerprint: String): X509TrustManager {
        return object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                // Client certificates not used
            }

            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                if (chain.isNullOrEmpty())
                    throw CertificateException("No certificate provided by server")
                val serverFingerprint = calculateFingerprint(chain[0])
                if (!fingerprintsMatch(serverFingerprint, expectedFingerprint)) {
                    throw CertificateException("Certificate fingerprint mismatch")
                }
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
    }
}
