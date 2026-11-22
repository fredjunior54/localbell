package io.github.fredjunior54.localbell.network

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.fredjunior54.localbell.MainActivity
import io.github.fredjunior54.localbell.service.PhoneSessionService
import io.github.fredjunior54.localbell.util.CryptoUtils
import io.github.fredjunior54.localbell.util.LocalNetworkPermission
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.NetworkInterface
import java.net.ServerSocket
import java.util.concurrent.ScheduledThreadPoolExecutor
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionRuntimeTest {
    @Test
    fun startsAndStopsTheForegroundListener(): Unit = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        assumeFalse(LocalNetworkPermission.isMissing(ctx))
        val port = ServerSocket(0).use { it.localPort }
        val intent =
            Intent(ctx, PhoneSessionService::class.java)
                .putExtra(PhoneSessionService.EXTRA_PORT, port)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.startForegroundService(intent) }
            try {
                withTimeout(10_000) {
                    while (
                        PhoneSessionService.active?.status?.value?.phase !=
                            PhoneSessionRuntime.Phase.LISTENING
                    ) {
                        delay(50)
                    }
                }
            } finally {
                ctx.stopService(intent)
                withTimeout(5_000) { while (PhoneSessionService.active != null) delay(50) }
            }
        }
    }

    @Test
    fun acceptsPinnedTlsAndDeliversOnlyAfterEnrollment(): Unit = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val credential = File.createTempFile("session-test", ".digest", ctx.noBackupFilesDir)
        check(credential.delete())
        val deadlines = ScheduledThreadPoolExecutor(1).apply { removeOnCancelPolicy = true }
        val runtime = PhoneSessionRuntime(credential, deadlines)
        val port = ServerSocket(0).use { it.localPort }
        val address =
            NetworkInterface.getNetworkInterfaces()
                .toList()
                .flatMap { it.inetAddresses.toList() }
                .first(SessionTls::permitsPeer)
        val leaf = SessionTls.load().fingerprint()
        val trust = CryptoUtils.createPinningTrustManager(leaf.toHexString())
        val tls =
            SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(trust), null) }
        val worker = launch { runtime.run(port) }
        try {
            withTimeout(10_000) {
                runtime.status.first { it.phase == PhoneSessionRuntime.Phase.LISTENING }
            }
            assertFalse(runtime.offer(SessionDelivery.Kind.TEST, "offline"))
            val token = ByteArray(32) { 4 }.toHexString()
            tls.connect(address, port).use { socket -> enroll(peer(socket), runtime, leaf, token) }
            tls.connect(address, port).use { socket ->
                verifyDelivery(peer(socket), runtime, token)
            }
            runtime.unpair()
            tls.connect(address, port).use { socket ->
                val peer = peer(socket)
                peer.send("authenticate", "token" to token)
                peer.expect("rejected")
            }
            delay(1_100)
            tls.connect(address, port).use { socket ->
                val peer = peer(socket)
                beginEnrollment(peer, runtime, leaf)
                runtime.approve(true)
                peer.expect("pair_approved")
                val deadline = runtime.status.value.approvalDeadline
                runtime.approve(true)
                assertEquals(deadline, runtime.status.value.approvalDeadline)
                runtime.approve(false)
                assertFalse(credential.exists())
            }
            delay(1_100)
            tls.connect(address, port).use { socket ->
                val peer = peer(socket)
                beginEnrollment(peer, runtime, leaf)
                runtime.approve(true)
                peer.expect("pair_approved")
                withTimeout(35_000) {
                    runtime.status.first { it.phase == PhoneSessionRuntime.Phase.LISTENING }
                }
                assertFalse(credential.exists())
            }
        } finally {
            runtime.close()
            withTimeout(10_000) { worker.join() }
            deadlines.shutdownNow()
            credential.delete()
        }
    }

    private suspend fun enroll(
        peer: SessionPeer,
        runtime: PhoneSessionRuntime,
        leaf: ByteArray,
        token: String,
    ) {
        beginEnrollment(peer, runtime, leaf)
        runtime.approve(true)
        peer.expect("pair_approved")
        peer.send("pair_finish", "token" to token)
        peer.expect("paired")
    }

    private suspend fun beginEnrollment(
        peer: SessionPeer,
        runtime: PhoneSessionRuntime,
        leaf: ByteArray,
    ) {
        val code = ByteArray(32) { 1 }
        val challenge = ByteArray(32) { 3 }
        val commit = SessionPairing.commitment(leaf, code, challenge)
        peer.send("pair_start", "code" to code.toHexString(), "commitment" to commit.toHexString())
        val nonce = peer.expect("pair_nonce").getString("nonce").hexToByteArray()
        peer.send("pair_reveal", "challenge" to challenge.toHexString())
        val status =
            withTimeout(5_000) {
                runtime.status.first { it.phase == PhoneSessionRuntime.Phase.PAIRING }
            }
        assertEquals(SessionPairing.comparison(leaf, code, nonce, challenge), status.comparison)
    }

    private suspend fun verifyDelivery(
        peer: SessionPeer,
        runtime: PhoneSessionRuntime,
        token: String,
    ) {
        peer.send("authenticate", "token" to token)
        peer.expect("authenticated")
        peer.send("readiness", "ready" to true)
        withTimeout(5_000) { runtime.status.first { it.phase == PhoneSessionRuntime.Phase.READY } }
        assertTrue(
            runtime.offer(
                SessionDelivery.Kind.AUTOMATIC,
                "fixture",
                JSONObject().put("key", "fixture"),
            ),
        )
        val request = peer.expect("notification")
        peer.send("ack", "id" to request.getLong("id"), "accepted" to true)
        withTimeout(5_000) { runtime.status.first { it.forwarded == 1L } }
    }

    private fun SSLContext.connect(address: java.net.InetAddress, port: Int) =
        (socketFactory.createSocket(address, port) as SSLSocket).apply {
            useClientMode = true
            enabledProtocols = arrayOf("TLSv1.3")
            sslParameters = sslParameters.apply { applicationProtocols = arrayOf("localbell/1") }
            soTimeout = 5_000
            startHandshake()
            assertEquals("localbell/1", applicationProtocol)
        }

    private fun peer(socket: SSLSocket) =
        SessionPeer(
            DataInputStream(socket.inputStream),
            DataOutputStream(socket.outputStream),
        )
}
