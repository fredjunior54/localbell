package io.github.fredjunior54.localbell.network

import java.io.Closeable
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Owns a single authenticated session. It never dials or carries events across connections. */
internal class SessionDelivery(
    private val peer: SessionPeer,
    private val connection: Closeable,
    private val onOutcome: (Kind, Boolean) -> Unit,
) : Closeable {
    enum class Kind {
        AUTOMATIC,
        TEST,
        DISMISS,
    }

    private data class Event(val kind: Kind, val key: String, val payload: JSONObject?)

    private data class Pending(val id: Long, val reply: CompletableDeferred<Boolean>)

    private val queue = Channel<Event>(CAPACITY)
    private val pending = AtomicReference<Pending?>()

    fun offer(kind: Kind, key: String, payload: JSONObject? = null): Boolean {
        if (
            payload != null &&
                payload.toString().encodeToByteArray().size >
                    SessionFrame.MAX_PAYLOAD - FRAME_OVERHEAD
        )
            return false
        return queue.trySend(Event(kind, key, payload)).isSuccess
    }

    suspend fun run(): Unit = coroutineScope {
        val writer =
            launch(Dispatchers.IO) {
                try {
                    sendEvents()
                } finally {
                    close()
                }
            }
        try {
            while (true) {
                val ack = peer.read() ?: break
                val waiting = pending.get()
                if (
                    ack.getString("type") != "ack" ||
                        waiting == null ||
                        ack.getLong("id") != waiting.id ||
                        !waiting.reply.complete(ack.getBoolean("accepted"))
                ) {
                    throw IOException("Unexpected session acknowledgment")
                }
            }
        } finally {
            close()
            writer.cancel()
        }
    }

    private suspend fun sendEvents() {
        var next = 0L
        val delivered = mutableSetOf<String>()
        for (event in queue) {
            if (event.kind == Kind.DISMISS && event.key !in delivered) continue
            val id = ++next
            val reply = CompletableDeferred<Boolean>()
            pending.set(Pending(id, reply))
            val accepted = exchange(event, id, reply)
            pending.set(null)
            if (accepted && event.kind == Kind.AUTOMATIC) {
                if (delivered.size >= MAX_TRACKED && event.key !in delivered) delivered.clear()
                delivered.add(event.key)
            }
            if (accepted && event.kind == Kind.DISMISS) delivered.remove(event.key)
            onOutcome(event.kind, accepted)
        }
    }

    private suspend fun exchange(
        event: Event,
        id: Long,
        reply: CompletableDeferred<Boolean>,
    ): Boolean = coroutineScope {
        val timeout =
            launch(Dispatchers.Default) {
                delay(ACK_TIMEOUT_MS)
                close()
                reply.cancel()
            }
        try {
            if (event.kind == Kind.DISMISS) {
                peer.send("dismiss", "id" to id, "key" to event.key)
            } else {
                peer.send("notification", "id" to id, "payload" to checkNotNull(event.payload))
            }
            reply.await()
        } finally {
            timeout.cancel()
        }
    }

    override fun close() {
        queue.cancel()
        runCatching { connection.close() }
    }

    companion object {
        internal const val CAPACITY = 16
        private const val FRAME_OVERHEAD = 80
        private const val MAX_TRACKED = 4096
        private const val ACK_TIMEOUT_MS = 8_000L
    }
}
