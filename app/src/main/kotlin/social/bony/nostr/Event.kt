package social.bony.nostr

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.encodeToJsonElement
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.MessageDigest
import java.security.Security

/**
 * A Nostr event as defined in NIP-01.
 *
 * All fields are immutable. Events signed by an external signer (Amber, nsecBunker)
 * arrive fully formed. Local draft events (pre-signing) use [UnsignedEvent].
 */
@Serializable
data class Event(
    val id: String,
    val pubkey: String,
    @SerialName("created_at") val createdAt: Long,
    val kind: Int,
    val tags: List<JsonArray>,
    val content: String,
    val sig: String,
) {
    /** Parsed tag wrappers for ergonomic access. */
    val parsedTags: List<Tag> by lazy { tags.map { Tag.fromJsonArray(it) } }

    /**
     * Verifies the event id and signature.
     * Returns false if either check fails — never throws.
     */
    fun verify(): Boolean = runCatching {
        verifyId() && verifySignature()
    }.getOrDefault(false)

    private fun verifyId(): Boolean {
        val serialized = serializeForId(pubkey, createdAt, kind, tags, content)
        val hash = sha256(serialized)
        return hash == id
    }

    private fun verifySignature(): Boolean {
        ensureBouncyCastle()
        return Crypto.verifySchnorr(
            message = id.hexToBytes(),
            signature = sig.hexToBytes(),
            pubkey = pubkey.hexToBytes(),
        )
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun fromJson(raw: String): Event = json.decodeFromString(raw)

        /**
         * Computes the canonical serialization used for the event id.
         * [0, pubkey, created_at, kind, tags, content]
         */
        fun serializeForId(
            pubkey: String,
            createdAt: Long,
            kind: Int,
            tags: List<JsonArray>,
            content: String,
        ): ByteArray {
            val array = buildJsonArray {
                add(Json.encodeToJsonElement(0))
                add(Json.encodeToJsonElement(pubkey))
                add(Json.encodeToJsonElement(createdAt))
                add(Json.encodeToJsonElement(kind))
                add(JsonArray(tags.map { it as JsonElement }))
                add(Json.encodeToJsonElement(content))
            }
            return array.toString().toByteArray(Charsets.UTF_8)
        }

        fun sha256(input: ByteArray): String =
            MessageDigest.getInstance("SHA-256")
                .digest(input)
                .toHex()

        private fun ensureBouncyCastle() {
            if (Security.getProvider("BC") == null) {
                Security.addProvider(BouncyCastleProvider())
            }
        }
    }
}

/**
 * A Nostr event that has not yet been signed.
 * Passed to a [social.bony.signer.NostrSigner] to produce a signed [Event].
 */
@Serializable
data class UnsignedEvent(
    val pubkey: String,
    @SerialName("created_at") val createdAt: Long = System.currentTimeMillis() / 1000,
    val kind: Int,
    val tags: List<JsonArray> = emptyList(),
    val content: String,
) {
    fun computeId(): String {
        val serialized = Event.serializeForId(pubkey, createdAt, kind, tags, content)
        return Event.sha256(serialized)
    }
}

// ── Hex utilities ─────────────────────────────────────────────────────────────

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

fun String.hexToBytes(): ByteArray {
    check(length % 2 == 0) { "Hex string must have even length" }
    return ByteArray(length / 2) { i ->
        ((Character.digit(this[i * 2], 16) shl 4) + Character.digit(this[i * 2 + 1], 16)).toByte()
    }
}
