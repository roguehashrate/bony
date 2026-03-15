package social.bony.account.signer

import android.content.Intent
import android.net.Uri
import kotlinx.serialization.json.Json
import social.bony.nostr.Event
import social.bony.nostr.UnsignedEvent

/**
 * NIP-55: delegates signing and encryption to Amber (or any compatible
 * external signer app) via Android intents.
 *
 * Intent flow:
 *   1. Build an intent with URI `nostrsigner:<payload>` and extras
 *   2. Post to [AmberSignerBridge] and suspend
 *   3. MainActivity launches the intent via ActivityResultLauncher
 *   4. Amber returns the result; bridge completes the deferred
 *   5. This signer resumes and returns the result
 */
class AmberSigner(
    override val pubkey: String,
    private val bridge: AmberSignerBridge,
    private val callerPackage: String,
) : NostrSigner {

    override suspend fun signEvent(event: UnsignedEvent): Result<Event> {
        val payload = Json.encodeToString(
            UnsignedEventSerializer,
            event.copy(pubkey = pubkey),
        )
        val intent = buildIntent(payload, "sign_event")
        return bridge.request(intent).mapCatching { json ->
            Event.fromJson(json).also { signed ->
                check(signed.verify()) { "Amber returned an event with invalid signature" }
            }
        }
    }

    override suspend fun nip44Encrypt(plaintext: String, recipientPubkey: String): Result<String> {
        val intent = buildIntent(plaintext, "nip44_encrypt").apply {
            putExtra("pubKey", recipientPubkey)
        }
        return bridge.request(intent)
    }

    override suspend fun nip44Decrypt(ciphertext: String, senderPubkey: String): Result<String> {
        val intent = buildIntent(ciphertext, "nip44_decrypt").apply {
            putExtra("pubKey", senderPubkey)
        }
        return bridge.request(intent)
    }

    private fun buildIntent(payload: String, type: String): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse("nostrsigner:$payload")).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("type", type)
            putExtra("pubKey", pubkey)
            putExtra("package", callerPackage)
        }

    companion object {
        const val REQUEST_CODE = 9001
    }
}

// ── Serializer shim ───────────────────────────────────────────────────────────
// UnsignedEvent → JSON matching the NIP-01 event shape Amber expects.

private val UnsignedEventSerializer = kotlinx.serialization.json.Json.serializersModule
    .let {
        // Manual serialization — UnsignedEvent mirrors Event minus id/sig
        object : kotlinx.serialization.KSerializer<UnsignedEvent> {
            override val descriptor = kotlinx.serialization.descriptors.buildClassSerialDescriptor("UnsignedEvent")

            override fun serialize(
                encoder: kotlinx.serialization.encoding.Encoder,
                value: UnsignedEvent,
            ) {
                val json = encoder as? kotlinx.serialization.json.JsonEncoder
                    ?: error("Only JSON encoding supported")
                json.encodeJsonElement(
                    kotlinx.serialization.json.buildJsonObject {
                        put("pubkey", kotlinx.serialization.json.JsonPrimitive(value.pubkey))
                        put("created_at", kotlinx.serialization.json.JsonPrimitive(value.createdAt))
                        put("kind", kotlinx.serialization.json.JsonPrimitive(value.kind))
                        put("tags", kotlinx.serialization.json.JsonArray(value.tags))
                        put("content", kotlinx.serialization.json.JsonPrimitive(value.content))
                    }
                )
            }

            override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder): UnsignedEvent =
                error("Deserialization not needed")
        }
    }
