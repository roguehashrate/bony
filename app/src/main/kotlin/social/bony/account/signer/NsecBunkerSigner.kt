package social.bony.account.signer

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import social.bony.account.NsecBunkerConfig
import social.bony.nostr.Event
import social.bony.nostr.EventKind
import social.bony.nostr.Filter
import social.bony.nostr.UnsignedEvent
import social.bony.nostr.relay.ClientMessage
import social.bony.nostr.relay.PoolMessage
import social.bony.nostr.relay.RelayMessage
import social.bony.nostr.relay.RelayPool
import java.util.UUID

private const val TAG = "NsecBunkerSigner"
private const val REQUEST_TIMEOUT_MS = 30_000L

/**
 * NIP-46: delegates signing and encryption to a remote nsecBunker
 * communicating over a Nostr relay via encrypted kind-24133 events.
 *
 * The session keypair ([config.sessionPubkey]) is ephemeral and generated
 * at account setup time. The private half is stored in Android Keystore
 * (managed by [LocalKeySigner]) and used only for NIP-44 encryption of
 * the NIP-46 request/response channel — NOT for signing Nostr events.
 */
class NsecBunkerSigner(
    override val pubkey: String,
    private val config: NsecBunkerConfig,
    private val pool: RelayPool,
    private val sessionSigner: LocalKeySigner, // signs the NIP-46 wrapper events
) : NostrSigner {

    override suspend fun signEvent(event: UnsignedEvent): Result<Event> =
        request("sign_event", listOf(unsignedEventToJson(event)))
            .mapCatching { result -> Event.fromJson(result) }

    override suspend fun nip44Encrypt(plaintext: String, recipientPubkey: String): Result<String> =
        request("nip44_encrypt", listOf(plaintext, recipientPubkey))

    override suspend fun nip44Decrypt(ciphertext: String, senderPubkey: String): Result<String> =
        request("nip44_decrypt", listOf(ciphertext, senderPubkey))

    // ── NIP-46 wire protocol ──────────────────────────────────────────────────

    private suspend fun request(method: String, params: List<String>): Result<String> =
        runCatching {
            val requestId = UUID.randomUUID().toString().take(8)
            val requestJson = Json.encodeToString(
                Nip46Request.serializer(),
                Nip46Request(id = requestId, method = method, params = params),
            )

            // Encrypt request content for the bunker pubkey
            val encryptedContent = sessionSigner
                .nip44Encrypt(requestJson, config.bunkerPubkey)
                .getOrThrow()

            // Build and publish the NIP-46 request event
            val requestEvent = sessionSigner.signEvent(
                UnsignedEvent(
                    pubkey = config.sessionPubkey,
                    kind = EventKind.NOSTR_CONNECT,
                    tags = listOf(
                        kotlinx.serialization.json.buildJsonArray {
                            add(kotlinx.serialization.json.JsonPrimitive("p"))
                            add(kotlinx.serialization.json.JsonPrimitive(config.bunkerPubkey))
                        }
                    ),
                    content = encryptedContent,
                )
            ).getOrThrow()

            pool.addRelay(config.relayUrl)
            pool.subscribe(
                filters = listOf(
                    Filter(
                        kinds = listOf(EventKind.NOSTR_CONNECT),
                        authors = listOf(config.bunkerPubkey),
                        pTags = listOf(config.sessionPubkey),
                    )
                ),
            )

            pool.send(config.relayUrl, ClientMessage.Publish(requestEvent))

            // Await the response matching our requestId
            withTimeout(REQUEST_TIMEOUT_MS) {
                pool.messages
                    .filter { it.relayUrl == config.relayUrl }
                    .filter { it.message is RelayMessage.EventMessage }
                    .first { poolMessage ->
                        val event = (poolMessage.message as RelayMessage.EventMessage).event
                        parseResponse(event, requestId) != null
                    }
                    .let { poolMessage ->
                        val event = (poolMessage.message as RelayMessage.EventMessage).event
                        val response = parseResponse(event, requestId)!!
                        if (response.error.isNotEmpty()) {
                            error("nsecBunker error: ${response.error}")
                        }
                        response.result
                    }
            }
        }

    private fun parseResponse(event: Event, requestId: String): Nip46Response? = runCatching {
        val decrypted = sessionSigner
            .nip44DecryptSync(event.content, config.bunkerPubkey)
            ?: return null
        val response = Json.decodeFromString(Nip46Response.serializer(), decrypted)
        if (response.id == requestId) response else null
    }.getOrNull()

    private fun unsignedEventToJson(event: UnsignedEvent): String =
        Json.encodeToString(UnsignedEvent.serializer(), event)

    @Serializable
    private data class Nip46Request(
        val id: String,
        val method: String,
        val params: List<String>,
    )

    @Serializable
    private data class Nip46Response(
        val id: String,
        val result: String,
        val error: String = "",
    )
}
