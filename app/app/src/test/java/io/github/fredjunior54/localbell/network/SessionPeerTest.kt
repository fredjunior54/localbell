package io.github.fredjunior54.localbell.network

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SessionPeerTest {
    private val leaf = ByteArray(32) { 1 }
    private val code = ByteArray(32) { 2 }
    private val challenge = ByteArray(32) { 3 }
    private val token = ByteArray(32) { 4 }
    private val digest = MessageDigest.getInstance("SHA-256").digest(token)

    @Test
    fun `persists only after approval and accepts the saved token on a new stream`() {
        val fixture = enrollment()
        var displayed: String? = null
        val result =
            fixture.peer.open(
                leaf,
                null,
                {
                    displayed = it
                    true
                },
            ) {
                assertArrayEquals(digest, it)
                true
            }
        assertEquals(SessionPeer.Opening.Paired, result)
        val responses = fixture.responses()
        val nonce = responses.first().getString("nonce").hexToByteArray()
        assertEquals(SessionPairing.comparison(leaf, code, nonce, challenge), displayed)
        assertEquals(
            listOf("pair_nonce", "pair_approved", "paired"),
            responses.map { it.getString("type") },
        )

        val reconnect =
            Fixture("""{"v":1,"type":"authenticate","token":"${token.toHexString()}"}""")
        assertEquals(
            SessionPeer.Opening.Authenticated,
            reconnect.peer.open(leaf, digest, { error("Unexpected prompt") }) {
                error("Unexpected persistence")
            },
        )
        assertEquals("authenticated", reconnect.responses().single().getString("type"))
    }

    @Test
    fun `rejects a mismatched reveal before showing a confirmation`() {
        val fixture = enrollment(reveal = ByteArray(32))
        val result =
            fixture.peer.open(leaf, null, { error("Unexpected prompt") }) {
                error("Unexpected persistence")
            }
        assertEquals(SessionPeer.Opening.Rejected, result)
        assertEquals(
            listOf("pair_nonce", "rejected"),
            fixture.responses().map { it.getString("type") },
        )
    }

    @Test
    fun `rejects an old token after unpair and refuses occupied enrollment`() {
        val attempt = Fixture("""{"v":1,"type":"authenticate","token":"${token.toHexString()}"}""")
        val occupied = enrollment()
        val neverApprove: (String) -> Boolean = { error("Unexpected prompt") }
        val neverSave: (ByteArray) -> Boolean = { error("Unexpected persistence") }
        assertEquals(
            SessionPeer.Opening.Rejected,
            attempt.peer.open(leaf, null, neverApprove, neverSave),
        )
        assertEquals(
            SessionPeer.Opening.Rejected,
            occupied.peer.open(leaf, digest, neverApprove, neverSave),
        )
    }

    @Test
    fun `interoperates with the real Rust session client`() {
        val path = System.getenv("LOCALBELL_SESSION_INTEROP")
        assumeNotNull(path)
        val child =
            ProcessBuilder(checkNotNull(path))
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start()
        try {
            val responses = child.inputStream.bufferedReader()
            val port = responses.readLine().toInt()
            var approved: String? = null
            var pairedDigest: ByteArray? = null
            connect(port).use { socket ->
                val peer = session(socket)
                val result =
                    peer.open(
                        leaf,
                        null,
                        {
                            approved = it
                            true
                        },
                    ) {
                        pairedDigest = it
                        true
                    }
                assertEquals(SessionPeer.Opening.Paired, result)
            }
            assertEquals(approved, responses.readLine())
            connect(port).use { socket ->
                val peer = session(socket)
                val result =
                    peer.open(leaf, checkNotNull(pairedDigest), { error("Unexpected prompt") }) {
                        error("Unexpected persistence")
                    }
                assertEquals(SessionPeer.Opening.Authenticated, result)
                assertTrue(peer.expect("readiness").getBoolean("ready"))
                peer.send(
                    "notification",
                    "id" to 1,
                    "payload" to JSONObject().put("key", "interop"),
                )
                val ack = peer.expect("ack")
                assertEquals(1, ack.getInt("id"))
                assertTrue(ack.getBoolean("accepted"))
                peer.send("revoked")
            }
            assertTrue(child.waitFor(5, TimeUnit.SECONDS))
            assertEquals(0, child.exitValue())
        } finally {
            child.destroyForcibly()
        }
    }

    private fun connect(port: Int): Socket =
        Socket(InetAddress.getByName("127.0.0.1"), port).apply {
            soTimeout = 5_000
        }

    private fun session(socket: Socket) =
        SessionPeer(
            DataInputStream(socket.getInputStream()),
            DataOutputStream(socket.getOutputStream()),
        )

    private fun enrollment(reveal: ByteArray = challenge): Fixture {
        val commitment = SessionPairing.commitment(leaf, code, challenge).toHexString()
        return Fixture(
            JSONObject()
                .put("v", 1)
                .put("type", "pair_start")
                .put("code", code.toHexString())
                .put("commitment", commitment)
                .toString(),
            """{"v":1,"type":"pair_reveal","challenge":"${reveal.toHexString()}"}""",
            """{"v":1,"type":"pair_finish","token":"${token.toHexString()}"}""",
        )
    }

    private class Fixture(vararg messages: String) {
        private val output = ByteArrayOutputStream()
        private val requests =
            ByteArrayOutputStream().apply {
                val stream = DataOutputStream(this)
                messages.forEach { SessionFrame.write(stream, it.encodeToByteArray()) }
            }
        val peer =
            SessionPeer(
                DataInputStream(requests.toByteArray().inputStream()),
                DataOutputStream(output),
            )

        fun responses(): List<JSONObject> = buildList {
            val stream = DataInputStream(output.toByteArray().inputStream())
            while (true) {
                val next = SessionFrame.read(stream) ?: break
                add(JSONObject(next.decodeToString()))
            }
        }
    }
}
