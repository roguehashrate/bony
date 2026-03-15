package social.bony.nostr.relay

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import social.bony.nostr.Filter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "RelayPool"
private const val RECONNECT_DELAY_MS = 5_000L
private const val MAX_RECONNECT_DELAY_MS = 60_000L

/**
 * Manages a set of [RelayConnection]s and multiplexes their messages into a
 * single [messages] flow.
 *
 * Subscriptions opened via [subscribe] are automatically re-sent to a relay
 * when it reconnects. Call [unsubscribe] or cancel the returned [Job] to stop.
 */
class RelayPool(
    private val scope: CoroutineScope,
    private val connectionFactory: (url: String) -> RelayConnection,
) {
    private val connections = ConcurrentHashMap<String, RelayEntry>()
    private val activeSubscriptions = ConcurrentHashMap<String, Subscription>()

    private val _messages = MutableSharedFlow<PoolMessage>(extraBufferCapacity = 256)
    val messages: SharedFlow<PoolMessage> = _messages.asSharedFlow()

    // ── Relay management ──────────────────────────────────────────────────────

    fun addRelay(url: String) {
        if (connections.containsKey(url)) return
        val connection = connectionFactory(url)
        val job = scope.launch { connectWithRetry(url, connection) }
        connections[url] = RelayEntry(connection, job)
        Log.d(TAG, "Added relay: $url")
    }

    fun removeRelay(url: String) {
        connections.remove(url)?.job?.cancel()
        Log.d(TAG, "Removed relay: $url")
    }

    fun relayUrls(): Set<String> = connections.keys.toSet()

    // ── Subscriptions ─────────────────────────────────────────────────────────

    /**
     * Opens a subscription on all current (and future) relays.
     * Returns the subscription ID so callers can filter [messages] by it.
     */
    fun subscribe(filters: List<Filter>, id: String = newSubId()): String {
        val sub = Subscription(id, filters)
        activeSubscriptions[id] = sub
        val req = ClientMessage.Req(id, filters)
        connections.values.forEach { it.connection.send(req) }
        Log.d(TAG, "Subscribed: $id with ${filters.size} filter(s)")
        return id
    }

    /** Sends a message to a specific relay by URL. Returns false if not connected. */
    fun send(relayUrl: String, message: ClientMessage): Boolean =
        connections[relayUrl]?.connection?.send(message) ?: false

    fun unsubscribe(subscriptionId: String) {
        activeSubscriptions.remove(subscriptionId) ?: return
        val close = ClientMessage.Close(subscriptionId)
        connections.values.forEach { it.connection.send(close) }
        Log.d(TAG, "Unsubscribed: $subscriptionId")
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private suspend fun connectWithRetry(url: String, connection: RelayConnection) {
        var delayMs = RECONNECT_DELAY_MS
        while (true) {
            Log.d(TAG, "Connecting: $url")
            try {
                connection.messages.collect { message ->
                    delayMs = RECONNECT_DELAY_MS // reset backoff on successful message
                    _messages.emit(PoolMessage(url, message))
                }
            } catch (e: Exception) {
                Log.w(TAG, "Collection error on $url", e)
            }

            // Flow completed (connection dropped) — check if relay is still wanted
            if (!connections.containsKey(url)) break

            Log.d(TAG, "Reconnecting $url in ${delayMs}ms")
            delay(delayMs)
            delayMs = (delayMs * 2).coerceAtMost(MAX_RECONNECT_DELAY_MS)

            // Re-send all active subscriptions after reconnect
            activeSubscriptions.values.forEach { sub ->
                connection.send(ClientMessage.Req(sub.id, sub.filters))
            }
        }
    }

    private fun newSubId(): String = UUID.randomUUID().toString().take(8)

    // ── Data classes ──────────────────────────────────────────────────────────

    private data class RelayEntry(val connection: RelayConnection, val job: Job)
    private data class Subscription(val id: String, val filters: List<Filter>)
}

/**
 * A relay message tagged with the relay URL it arrived from.
 */
data class PoolMessage(
    val relayUrl: String,
    val message: RelayMessage,
)
