package io.github.fredjunior54.localbell.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SessionPairingTest {
    @Test
    fun `matches the Rust pairing vector`() {
        val leaf = ByteArray(32)
        val code = ByteArray(32) { 1 }
        val nonce = ByteArray(32) { 2 }
        val challenge = ByteArray(32) { 3 }
        val commitment = SessionPairing.commitment(leaf, code, challenge)
        assertEquals(
            "4490a209f578c7cf67c098543f7b0c9d5b0920e715b9002b97ccf1e7d04c59c8",
            commitment.joinToString("") { "%02x".format(it) },
        )
        assertEquals("232961", SessionPairing.comparison(leaf, code, nonce, challenge))
        assertThrows(IllegalArgumentException::class.java) {
            SessionPairing.comparison(leaf, code, ByteArray(31), challenge)
        }
    }
}
