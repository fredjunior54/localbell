package io.github.fredjunior54.localbell.network

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import org.json.JSONObject

/** Phone side of enrollment and session authentication. Run on a worker with bounded socket I/O. */
internal class SessionPeer(input: DataInputStream, private val output: DataOutputStream) {
    private val input = input

    sealed interface Opening {
        data object Paired : Opening

        data object Authenticated : Opening

        data object Rejected : Opening
    }

    /** The store must atomically refuse replacement and persist only the digest of the token. */
    fun open(
        leaf: ByteArray,
        enrolledDigest: ByteArray?,
        approve: (String) -> Boolean,
        store: (ByteArray) -> Boolean,
    ): Opening {
        val first = read() ?: throw IOException("Missing session greeting")
        return when (first.getString("type")) {
            "authenticate" -> {
                val digest = digestToken(first.getString("token"))
                if (enrolledDigest == null || !MessageDigest.isEqual(enrolledDigest, digest)) {
                    reject()
                } else {
                    send("authenticated")
                    Opening.Authenticated
                }
            }
            "pair_start" ->
                if (enrolledDigest == null) enroll(first, leaf, approve, store) else reject()
            else -> throw IOException("Unexpected session greeting")
        }
    }

    private fun enroll(
        first: JSONObject,
        leaf: ByteArray,
        approve: (String) -> Boolean,
        store: (ByteArray) -> Boolean,
    ): Opening {
        val code = decode(first.getString("code"))
        val expectedCommitment = decode(first.getString("commitment"))
        val nonce = ByteArray(FIELD_BYTES).also(secureRandom::nextBytes)
        send("pair_nonce", "nonce" to nonce.toHexString())
        val challenge = decode(expect("pair_reveal").getString("challenge"))
        if (
            !MessageDigest.isEqual(
                expectedCommitment,
                SessionPairing.commitment(leaf, code, challenge),
            )
        ) {
            return reject()
        }
        if (!approve(SessionPairing.comparison(leaf, code, nonce, challenge))) return reject()
        send("pair_approved")
        val digest = digestToken(expect("pair_finish").getString("token"))
        if (!store(digest)) return reject()
        send("paired")
        return Opening.Paired
    }

    fun expect(type: String): JSONObject {
        val message = read()
        if (message?.getString("type") != type) throw IOException("Unexpected session message")
        return message
    }

    fun read(): JSONObject? {
        val frame = SessionFrame.read(input) ?: return null
        val json = JSONObject(frame.decodeToString(throwOnInvalidSequence = true))
        if (json.get("v") != 1) throw IOException("Unsupported session protocol version")
        return json
    }

    @Synchronized
    fun send(type: String, vararg values: Pair<String, Any>) {
        val message = JSONObject().put("v", 1).put("type", type)
        values.forEach { (key, value) -> message.put(key, value) }
        SessionFrame.write(output, message.toString().encodeToByteArray())
    }

    private fun reject(): Opening {
        send("rejected")
        return Opening.Rejected
    }

    companion object {
        private const val FIELD_BYTES = 32
        private val secureRandom = SecureRandom()

        private fun digestToken(value: String): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(decode(value))

        private fun decode(value: String): ByteArray {
            if (
                value.length != FIELD_BYTES * 2 || value.any { it !in '0'..'9' && it !in 'a'..'f' }
            ) {
                throw IOException("Invalid session field encoding")
            }
            return value.hexToByteArray()
        }
    }
}
