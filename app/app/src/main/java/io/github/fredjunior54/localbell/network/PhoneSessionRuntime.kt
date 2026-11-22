package io.github.fredjunior54.localbell.network

import android.os.SystemClock
import android.util.AtomicFile
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.net.Socket
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/** Owns Android's listener, pending operator decision, and at most one desktop session. */
internal class PhoneSessionRuntime(
    credentialFile: File,
    private val deadlines: ScheduledExecutorService,
) : Closeable {
    enum class Phase {
        STARTING,
        LISTENING,
        PAIRING,
        UNAVAILABLE,
        READY,
        FAILED,
        STOPPED,
    }

    data class Status(
        val phase: Phase = Phase.STOPPED,
        val paired: Boolean = false,
        val comparison: String? = null,
        val comparisonId: Long = 0,
        val approvalDeadline: Long? = null,
        val address: String? = null,
        val forwarded: Long = 0,
        val accepted: Boolean? = null,
        val outcomeTime: Long? = null,
        val testAccepted: Boolean? = null,
    )

    private val credentials = AtomicFile(credentialFile)
    private val lock = Any()
    private val mutableStatus = MutableStateFlow(Status())
    val status = mutableStatus.asStateFlow()
    private var closed = false
    private var listener: SessionListener? = null
    private var socket: Socket? = null
    private var decision: CompletableDeferred<Boolean>? = null
    private var delivery: SessionDelivery? = null
    private var comparisonId = 0L
    private var lastEnrollment: Long? = null

    fun approve(matches: Boolean, attempt: Long = status.value.comparisonId) =
        synchronized(lock) {
            val current = mutableStatus.value
            if (current.comparison == null || current.comparisonId != attempt) return@synchronized
            if (current.approvalDeadline != null) {
                if (!matches) {
                    mutableStatus.update { it.copy(comparison = null, approvalDeadline = null) }
                    runCatching { socket?.close() }
                }
            } else if (decision?.complete(matches) == true && matches) {
                mutableStatus.update {
                    it.copy(approvalDeadline = SystemClock.elapsedRealtime() + FINAL_APPROVAL_MS)
                }
            }
        }

    fun offer(kind: SessionDelivery.Kind, key: String, payload: JSONObject? = null) =
        synchronized(lock) {
            delivery?.offer(kind, key, payload) == true
        }

    fun unpair() =
        synchronized(lock) {
            credentials.delete()
            check(readCredential() == null) { "Could not remove desktop pairing" }
            delivery?.close()
            decision?.complete(false)
            runCatching { socket?.close() }
            mutableStatus.value = Status(Phase.LISTENING)
        }

    suspend fun run(port: Int): Unit =
        withContext(Dispatchers.IO) {
            try {
                mutableStatus.value =
                    Status(Phase.STARTING, synchronized(lock) { readCredential() != null })
                val server = SessionListener.bind(port)
                synchronized(lock) {
                    if (closed) {
                        server.close()
                        return@withContext
                    }
                    listener = server
                }
                acceptConnections(server)
            } catch (_: Exception) {
                synchronized(lock) {
                    if (!closed) mutableStatus.update { it.copy(phase = Phase.FAILED) }
                }
            } finally {
                synchronized(lock) {
                    listener?.close()
                    listener = null
                }
            }
        }

    private suspend fun acceptConnections(server: SessionListener) {
        while (synchronized(lock) { !closed }) {
            try {
                mutableStatus.update {
                    it.copy(
                        phase = Phase.LISTENING,
                        comparison = null,
                        approvalDeadline = null,
                        address = null,
                    )
                }
                server.accept().use { connection ->
                    synchronized(lock) {
                        if (closed) return
                        socket = connection
                    }
                    handle(connection, server.fingerprint())
                }
            } catch (_: Exception) {
                // Reject malformed or failed peers without exposing payloads or stopping the
                // listener.
                kotlinx.coroutines.delay(REJECT_DELAY_MS)
            } finally {
                synchronized(lock) {
                    mutableStatus.update { it.copy(comparison = null, approvalDeadline = null) }
                    decision?.cancel()
                    decision = null
                    delivery = null
                    socket = null
                }
            }
        }
    }

    private suspend fun handle(connection: Socket, leaf: ByteArray) {
        val peer =
            SessionPeer(
                DataInputStream(connection.getInputStream()),
                DataOutputStream(connection.getOutputStream()),
            )
        // The blocking enrollment callback waits only for one explicit user decision.
        val guard =
            deadlines.schedule(
                { runCatching { connection.close() } },
                ENROLLMENT_TIMEOUT_SECONDS,
                TimeUnit.SECONDS,
            )
        var approvalGuard: java.util.concurrent.ScheduledFuture<*>? = null
        try {
            val digest = synchronized(lock) { readCredential() }
            val opened =
                peer.open(
                    leaf,
                    digest,
                    { number ->
                        if (!guard.cancel(false) || !awaitDecision(connection, number)) false
                        else
                            synchronized(lock) {
                                val deadline =
                                    mutableStatus.value.approvalDeadline
                                        ?: return@synchronized false
                                val remaining = deadline - SystemClock.elapsedRealtime()
                                if (remaining <= 0 || connection.isClosed) false
                                else {
                                    connection.soTimeout = remaining.toInt()
                                    approvalGuard =
                                        deadlines.schedule(
                                            { runCatching { connection.close() } },
                                            remaining,
                                            TimeUnit.MILLISECONDS,
                                        )
                                    true
                                }
                            }
                    },
                ) { value ->
                    synchronized(lock) {
                        val deadline =
                            mutableStatus.value.approvalDeadline ?: return@synchronized false
                        if (
                            closed ||
                                connection.isClosed ||
                                SystemClock.elapsedRealtime() >= deadline ||
                                readCredential() != null
                        )
                            false
                        else {
                            saveCredential(value)
                            mutableStatus.update {
                                it.copy(paired = true, comparison = null, approvalDeadline = null)
                            }
                            true
                        }
                    }
                }
            if (opened != SessionPeer.Opening.Authenticated) return
            val ready = peer.expect("readiness").getBoolean("ready")
            if (!guard.cancel(false)) return
            serve(connection, peer, ready)
        } finally {
            approvalGuard?.cancel(false)
            guard.cancel(false)
        }
    }

    private fun awaitDecision(connection: Socket, number: String): Boolean =
        kotlinx.coroutines.runBlocking {
            val pending =
                synchronized(lock) {
                    val now = SystemClock.elapsedRealtime()
                    if (
                        closed ||
                            connection.isClosed ||
                            lastEnrollment?.let { now - it < REJECT_DELAY_MS } == true
                    ) {
                        return@runBlocking false
                    }
                    lastEnrollment = now
                    CompletableDeferred<Boolean>().also {
                        decision = it
                        mutableStatus.value =
                            Status(
                                Phase.PAIRING,
                                comparison = number,
                                comparisonId = ++comparisonId,
                                address = connection.inetAddress.hostAddress,
                            )
                    }
                }
            withTimeoutOrNull(APPROVAL_MS) { pending.await() } == true
        }

    private suspend fun serve(connection: Socket, peer: SessionPeer, ready: Boolean) {
        connection.soTimeout = 0
        connection.keepAlive = true
        val session =
            SessionDelivery(peer, connection) { kind, accepted ->
                synchronized(lock) {
                    if (!connection.isClosed)
                        mutableStatus.update {
                            it.copy(
                                forwarded =
                                    it.forwarded +
                                        if (kind == SessionDelivery.Kind.AUTOMATIC && accepted) 1
                                        else 0,
                                accepted = accepted,
                                outcomeTime = System.currentTimeMillis(),
                                testAccepted =
                                    if (kind == SessionDelivery.Kind.TEST) accepted
                                    else it.testAccepted,
                            )
                        }
                }
            }
        synchronized(lock) {
            if (closed || connection.isClosed) return
            mutableStatus.update {
                it.copy(
                    phase = if (ready) Phase.READY else Phase.UNAVAILABLE,
                    paired = true,
                    address = connection.inetAddress.hostAddress,
                    forwarded = 0,
                    testAccepted = null,
                )
            }
            if (ready) delivery = session
        }
        session.use { it.run() }
    }

    override fun close() =
        synchronized(lock) {
            closed = true
            decision?.complete(false)
            delivery?.close()
            runCatching { socket?.close() }
            listener?.close()
            mutableStatus.update {
                it.copy(
                    phase = Phase.STOPPED,
                    comparison = null,
                    approvalDeadline = null,
                    address = null,
                )
            }
        }

    private fun readCredential(): ByteArray? =
        try {
            credentials
                .openRead()
                .use { it.readNBytes(DIGEST_BYTES + 1) }
                .also {
                    check(it.size == DIGEST_BYTES) { "Invalid desktop credential" }
                }
        } catch (_: FileNotFoundException) {
            null
        }

    private fun saveCredential(value: ByteArray) {
        val stream = credentials.startWrite()
        try {
            stream.write(value)
            credentials.finishWrite(stream)
        } catch (failure: Exception) {
            credentials.failWrite(stream)
            throw failure
        }
    }

    companion object {
        private const val DIGEST_BYTES = 32
        private const val REJECT_DELAY_MS = 1_000L
        private const val APPROVAL_MS = 80_000L
        internal const val FINAL_APPROVAL_MS = 30_000L
        private const val ENROLLMENT_TIMEOUT_SECONDS = 120L
    }
}
