package io.github.fredjunior54.localbell.network

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionFrameTest {
    @Test
    fun `reads and writes the shared Rust fixture`() {
        val fixture = File("../../tests/fixtures/session-frames.hex").readText().trim()
        val expected = fixture.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val output = ByteArrayOutputStream()
        val dataOutput = DataOutputStream(output)
        val input = input(expected)
        listOf("""{"v":1,"type":"ping"}""", """{"v":1,"type":"pong"}""").forEach { json ->
            val payload = json.toByteArray(Charsets.UTF_8)
            assertArrayEquals(payload, SessionFrame.read(input))
            SessionFrame.write(dataOutput, payload)
        }
        assertNull(SessionFrame.read(input))
        assertArrayEquals(expected, output.toByteArray())
    }

    @Test
    fun `rejects invalid lengths and truncated frames`() {
        listOf(0, SessionFrame.MAX_PAYLOAD + 1, -1).forEach { length ->
            val header = ByteArrayOutputStream()
            DataOutputStream(header).writeInt(length)
            assertThrows(IOException::class.java) { SessionFrame.read(input(header.toByteArray())) }
        }
        listOf(byteArrayOf(0, 0, 0), byteArrayOf(0, 0, 0, 2, 1)).forEach { partial ->
            assertThrows(EOFException::class.java) { SessionFrame.read(input(partial)) }
        }
        val output = ByteArrayOutputStream()
        assertThrows(IllegalArgumentException::class.java) {
            SessionFrame.write(DataOutputStream(output), byteArrayOf())
        }
        assertTrue(output.size() == 0)
    }

    private fun input(bytes: ByteArray) = DataInputStream(bytes.inputStream())
}
