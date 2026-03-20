package social.bony.reactions

import android.util.LruCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import social.bony.account.signer.NostrSignerFactory
import social.bony.nostr.Event
import social.bony.nostr.EventKind
import social.bony.nostr.Filter
import social.bony.nostr.UnsignedEvent
import social.bony.nostr.relay.RelayMessage
import social.bony.nostr.relay.RelayPool
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks NIP-25 reactions (kind-7, content="+") across the app.
 *
 * State is stored as `Map<eventId, Set<reactorPubkey>>` so callers can
 * derive both the count and whether the active user has reacted.
 *
 * Call [subscribeTo] whenever a new batch of events becomes visible.
 * Call [react] to post a "+" reaction (with optimistic update + rollback).
 */
@Singleton
class ReactionsRepository @Inject constructor(
    private val pool: RelayPool,
    private val signerFactory: NostrSignerFactory,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Caps reaction data at 5000 event IDs; oldest entries evicted automatically.
    private val reactionCache = LruCache<String, Set<String>>(5000)
    private val subscribedCache = LruCache<String, Unit>(5000)

    private val _reactions = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    val reactions: StateFlow<Map<String, Set<String>>> = _reactions.asStateFlow()

    init {
        scope.launch {
            pool.messages.collect { (_, msg) ->
                if (msg is RelayMessage.EventMessage
                    && msg.event.kind == EventKind.REACTION
                    && msg.event.content == "+"
                    && msg.event.verify()
                ) {
                    val targetId = msg.event.parsedTags
                        .lastOrNull { it.name == "e" }?.value() ?: return@collect
                    val updated = (reactionCache.get(targetId) ?: emptySet()) + msg.event.pubkey
                    reactionCache.put(targetId, updated)
                    _reactions.value = reactionCache.snapshot()
                }
            }
        }
    }

    /** Subscribe to reactions for any event IDs not yet tracked. */
    fun subscribeTo(eventIds: List<String>) {
        val newIds = eventIds.filter { subscribedCache.get(it) == null }
        if (newIds.isEmpty()) return
        newIds.forEach { subscribedCache.put(it, Unit) }
        val subId = pool.subscribe(listOf(Filter(eTags = newIds, kinds = listOf(EventKind.REACTION))))
        // Unsubscribe after EOSE — historical data loaded; live reactions handled by the init collector
        scope.launch {
            withTimeoutOrNull(30_000) {
                pool.messages.first { (_, msg) ->
                    msg is RelayMessage.EndOfStoredEvents && msg.subscriptionId == subId
                }
            }
            pool.unsubscribe(subId)
        }
    }

    /** Publish a "+" reaction. Updates state optimistically; rolls back on failure. */
    fun react(event: Event) {
        scope.launch {
            val signer = signerFactory.forActiveAccount() ?: return@launch
            val activePubkey = signer.pubkey

            val optimistic = (reactionCache.get(event.id) ?: emptySet()) + activePubkey
            reactionCache.put(event.id, optimistic)
            _reactions.value = reactionCache.snapshot()

            val unsigned = UnsignedEvent(
                pubkey = activePubkey,
                kind = EventKind.REACTION,
                content = "+",
                tags = listOf(
                    buildJsonArray { add("e"); add(event.id) },
                    buildJsonArray { add("p"); add(event.pubkey) },
                ),
            )
            signer.signEvent(unsigned)
                .onSuccess { pool.publish(it) }
                .onFailure { e ->
                    val rollback = (reactionCache.get(event.id) ?: emptySet()) - activePubkey
                    reactionCache.put(event.id, rollback)
                    _reactions.value = reactionCache.snapshot()
                    Timber.w(e, "React failed")
                }
        }
    }
}
