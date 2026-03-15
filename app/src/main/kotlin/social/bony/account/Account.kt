package social.bony.account

import kotlinx.serialization.Serializable

/**
 * Represents one Nostr identity stored in the app.
 * The private key is never stored here — signing is always delegated.
 */
@Serializable
data class Account(
    val pubkey: String,                          // hex-encoded 32-byte public key
    val displayName: String? = null,             // cached from kind-0 metadata
    val pictureUrl: String? = null,              // cached from kind-0 metadata
    val signerType: SignerType,
    val nsecBunkerConfig: NsecBunkerConfig? = null,
    val relays: List<String> = emptyList(),      // NIP-65 outbox relays
)

@Serializable
enum class SignerType {
    /** NIP-55: delegates to Amber (or any compatible signer app) via Android intents. */
    AMBER,

    /** NIP-46: delegates to a remote nsecBunker over a Nostr relay. */
    NSEC_BUNKER,

    /**
     * Local key stored in Android Keystore.
     * Last resort — only offered when no external signer is available.
     */
    LOCAL_KEY,
}

@Serializable
data class NsecBunkerConfig(
    val bunkerPubkey: String,   // hex pubkey of the bunker
    val relayUrl: String,       // relay the bunker is listening on
    val secret: String,         // optional shared secret for connect handshake
    val sessionPubkey: String,  // ephemeral session pubkey (hex) for this client
)
