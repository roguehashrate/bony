package social.bony.nostr.relay

import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

private const val TAG = "RelayConnection"
private const val NORMAL_CLOSURE = 1000

/**
 * A single WebSocket connection to one Nostr relay.
 *
 * [messages] is a cold Flow — a new WebSocket is opened each time it is collected
 * and closed when the collector cancels. Use [RelayPool] to manage multiple connections.
 *
 * Outbound messages are sent via [send]. The connection must be collected (open)
 * before sending will succeed.
 */
class RelayConnection(
    val url: String,
    private val client: OkHttpClient,
) {
    private var webSocket: WebSocket? = null

    val messages: Flow<RelayMessage> = callbackFlow {
        val request = Request.Builder().url(url).build()

        val listener = object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.d(TAG, "Connected: $url")
                webSocket = ws
            }

            override fun onMessage(ws: WebSocket, text: String) {
                trySend(RelayMessage.parse(text))
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "Failure on $url: ${t.message}")
                close(t)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "Closed: $url — $reason")
                webSocket = null
                close()
            }
        }

        val ws = client.newWebSocket(request, listener)

        awaitClose {
            ws.close(NORMAL_CLOSURE, "collector cancelled")
            webSocket = null
        }
    }.catch { t ->
        Log.e(TAG, "Flow error on $url", t)
    }

    /**
     * Sends a message to the relay. Returns false if the socket is not open.
     */
    fun send(message: ClientMessage): Boolean {
        val json = message.toJson()
        Log.v(TAG, "→ $url: $json")
        return webSocket?.send(json) ?: false
    }

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .pingInterval(30, TimeUnit.SECONDS)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS) // WebSocket — no read timeout
            .build()
    }
}
