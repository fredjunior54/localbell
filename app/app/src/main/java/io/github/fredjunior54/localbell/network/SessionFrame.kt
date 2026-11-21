package io.github.fredjunior54.localbell.network

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/** Length-delimited payloads for the reverse TLS session. */
internal object SessionFrame {
    const val MAX_PAYLOAD = 32 * 1024

    /** Returns null only at a frame boundary. Close the session on any exception. */
    fun read(input: DataInputStream): ByteArray? {
        val first = input.read()
        if (first == -1) return null
        val size =
            (first shl 24) or
                (input.readUnsignedByte() shl 16) or
                (input.readUnsignedByte() shl 8) or
                input.readUnsignedByte()
        if (size !in 1..MAX_PAYLOAD) throw IOException("Invalid frame length")
        return ByteArray(size).also { input.readFully(it) }
    }

    /** Serialize calls. Close the session on any exception. */
    fun write(output: DataOutputStream, payload: ByteArray) {
        require(payload.size in 1..MAX_PAYLOAD) { "Invalid frame length" }
        output.writeInt(payload.size)
        output.write(payload)
        output.flush()
    }
}
