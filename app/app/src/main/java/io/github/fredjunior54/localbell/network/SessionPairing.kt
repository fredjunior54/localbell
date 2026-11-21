package io.github.fredjunior54.localbell.network

import java.nio.ByteBuffer
import java.security.MessageDigest

/** Certificate-bound comparison for reverse-session enrollment. */
internal object SessionPairing {
    fun commitment(leaf: ByteArray, code: ByteArray, challenge: ByteArray): ByteArray =
        transcript("localbell-session-commit-v1", leaf, code, challenge)

    fun comparison(
        leaf: ByteArray,
        code: ByteArray,
        nonce: ByteArray,
        challenge: ByteArray,
    ): String {
        val hash = transcript("localbell-session-compare-v1", leaf, code, nonce, challenge)
        val unsigned = ByteBuffer.wrap(hash).int.toLong() and 0xffffffffL
        return (unsigned % 1_000_000).toString().padStart(6, '0')
    }

    private fun transcript(domain: String, vararg fields: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(domain.toByteArray(Charsets.US_ASCII))
        digest.update(0.toByte())
        fields.forEach {
            require(it.size == 32) { "Invalid pairing transcript field length" }
            digest.update(it)
        }
        return digest.digest()
    }
}
