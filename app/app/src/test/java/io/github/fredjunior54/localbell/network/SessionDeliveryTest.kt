package io.github.fredjunior54.localbell.network

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SessionDeliveryTest {
    @Test
    fun `only sends dismissals for acknowledged automatic deliveries`() = runBlocking {
        val address = InetAddress.getLoopbackAddress()
        ServerSocket(0, 1, address).use { listener ->
            Socket(address, listener.localPort).use { desktop ->
                desktop.soTimeout = 3_000
                listener.accept().use { phone ->
                    val outcomes = Channel<Pair<SessionDelivery.Kind, Boolean>>(4)
                    val session =
                        SessionDelivery(peer(phone), phone) { kind, accepted ->
                            check(outcomes.trySend(kind to accepted).isSuccess)
                        }
                    val worker = launch(Dispatchers.IO) { session.run() }
                    try {
                        verifyExchange(session, peer(desktop), outcomes)
                    } finally {
                        desktop.shutdownOutput()
                        withTimeout(3_000) { worker.join() }
                        assertFalse(session.offer(SessionDelivery.Kind.DISMISS, "sample"))
                    }
                }
            }
        }
    }

    private suspend fun verifyExchange(
        session: SessionDelivery,
        desktop: SessionPeer,
        outcomes: Channel<Pair<SessionDelivery.Kind, Boolean>>,
    ) {
        val payload = JSONObject().put("key", "sample")
        assertTrue(session.offer(SessionDelivery.Kind.DISMISS, "untracked"))
        assertTrue(session.offer(SessionDelivery.Kind.AUTOMATIC, "sample", payload))
        assertEquals(1L, desktop.expect("notification").getLong("id"))
        desktop.send("ack", "id" to 1, "accepted" to true)
        assertEquals(
            SessionDelivery.Kind.AUTOMATIC to true,
            withTimeout(3_000) { outcomes.receive() },
        )
        assertTrue(session.offer(SessionDelivery.Kind.DISMISS, "sample"))
        assertEquals(2L, desktop.expect("dismiss").getLong("id"))
        desktop.send("ack", "id" to 2, "accepted" to true)
        assertEquals(
            SessionDelivery.Kind.DISMISS to true,
            withTimeout(3_000) { outcomes.receive() },
        )
    }

    private fun peer(socket: Socket) =
        SessionPeer(
            DataInputStream(socket.getInputStream()),
            DataOutputStream(socket.getOutputStream()),
        )
}
