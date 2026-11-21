package io.github.fredjunior54.localbell.network

import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocket

/** A blocking listener. The owning service must run accept off the main thread. */
internal class SessionListener
private constructor(
    private val listener: ServerSocket,
    private val identity: SessionTls,
) : Closeable {
    private val lock = Any()
    private var pending: Socket? = null
    private val deadlines = ScheduledThreadPoolExecutor(1).apply { removeOnCancelPolicy = true }

    fun fingerprint(): ByteArray = identity.fingerprint()

    /**
     * Call serially. The returned socket belongs to the caller, even after this listener closes.
     */
    fun accept(): SSLSocket {
        val socket = listener.accept()
        try {
            val deadline =
                synchronized(lock) {
                    ensureOpen()
                    pending = socket
                    deadlines.schedule(
                        { runCatching { socket.close() } },
                        HANDSHAKE_SECONDS,
                        TimeUnit.SECONDS,
                    )
                }
            try {
                val tls = identity.accept(socket)
                if (!deadline.cancel(false)) throw IOException("TLS handshake timed out")
                return synchronized(lock) {
                    ensureOpen()
                    pending = null
                    tls
                }
            } finally {
                deadline.cancel(false)
            }
        } catch (failure: Exception) {
            synchronized(lock) {
                if (pending === socket) pending = null
            }
            runCatching { socket.close() }
            throw failure
        }
    }

    private fun ensureOpen() {
        if (listener.isClosed) throw IOException("Listener is closed")
    }

    override fun close() {
        synchronized(lock) {
            runCatching { listener.close() }
            runCatching { pending?.close() }
            deadlines.shutdownNow()
        }
    }

    companion object {
        private const val HANDSHAKE_SECONDS = 5L
        private const val BACKLOG = 8

        fun bind(port: Int): SessionListener {
            require(port in 1..65535) { "Invalid listener port" }
            val identity = SessionTls.load()
            val listener = ServerSocket()
            try {
                listener.reuseAddress = true
                listener.bind(InetSocketAddress("0.0.0.0", port), BACKLOG)
                return SessionListener(listener, identity)
            } catch (failure: Exception) {
                runCatching { listener.close() }
                throw failure
            }
        }
    }
}
