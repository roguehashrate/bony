package social.bony.ui.feed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import social.bony.account.Account
import social.bony.account.AccountRepository
import social.bony.db.EventRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import social.bony.account.signer.NostrSignerFactory
import social.bony.nostr.Event
import social.bony.nostr.EventKind
import social.bony.nostr.Filter
import social.bony.nostr.UnsignedEvent
import social.bony.nostr.ProfileContent
import social.bony.nostr.pubkeys
import social.bony.nostr.quotedEventId
import social.bony.ui.feed.extractInlineQuoteId
import social.bony.nostr.relay.RelayMessage
import social.bony.nostr.relay.RelayPool
import social.bony.nostr.relay.RelayStatus
import timber.log.Timber
import social.bony.profile.ProfileRepository
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import javax.inject.Inject

data class FeedUiState(
    val events: List<Event> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
)

@HiltViewModel
class FeedViewModel @Inject constructor(
    private val pool: RelayPool,
    private val accountRepository: AccountRepository,
    private val profileRepository: ProfileRepository,
    private val eventRepository: EventRepository,
    private val signerFactory: NostrSignerFactory,
) : ViewModel() {

    private val _uiState = MutableStateFlow(FeedUiState())
    val uiState: StateFlow<FeedUiState> = _uiState.asStateFlow()

    private val _scrollToTop = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val scrollToTop: SharedFlow<Unit> = _scrollToTop.asSharedFlow()

    val profiles: StateFlow<Map<String, ProfileContent>> = profileRepository.profiles
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private val _quotedEvents = MutableStateFlow<Map<String, Event>>(emptyMap())
    val quotedEvents: StateFlow<Map<String, Event>> = _quotedEvents.asStateFlow()

    val activeAccount: StateFlow<Account?> = accountRepository.activeAccount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val relayStatuses: StateFlow<Map<String, RelayStatus>> = pool.relayStatuses
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val accounts = accountRepository.accounts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // Tracks quote IDs already requested so we don't issue duplicate subscriptions
    private val requestedQuoteIds: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private var collectJob: Job? = null
    private var feedSubId: String? = null
    private var followSubId: String? = null
    private var metadataSubId: String? = null
    private var relayListSubId: String? = null
    private var lastLoadedPubkey: String? = null

    // Only process events belonging to current subscriptions — prevents stale events
    // from old subscriptions bleeding into a newly loaded feed.
    private val activeSubIds: MutableSet<String> = ConcurrentHashMap.newKeySet()

    // Events are buffered until the feed is "settled" (follow list processed + EOSE
    // on the expanded subscription), then flushed all at once so the feed snaps into
    // place rather than trickling in one-by-one. After settling, live events stream in.
    @Volatile private var feedSettled = false
    @Volatile private var followsReceived = false
    private val pendingBuffer = ConcurrentLinkedQueue<Event>()

    init {
        viewModelScope.launch {
            accountRepository.activeAccount.collect { account ->
                if (account != null) loadFeed(account) else clearFeed()
            }
        }
    }

    fun switchAccount(pubkey: String) {
        viewModelScope.launch { accountRepository.setActiveAccount(pubkey) }
    }

    fun boost(event: Event) {
        viewModelScope.launch {
            val signer = signerFactory.forActiveAccount() ?: return@launch
            val unsigned = UnsignedEvent(
                pubkey = signer.pubkey,
                kind = EventKind.REPOST,
                content = Json.encodeToString(Event.serializer(), event),
                tags = listOf(
                    buildJsonArray { add("e"); add(event.id); add(""); add("mention") },
                    buildJsonArray { add("p"); add(event.pubkey) },
                ),
            )
            signer.signEvent(unsigned)
                .onSuccess { pool.publish(it) }
                .onFailure { e -> Timber.w(e, "Boost failed") }
        }
    }

    // ── Feed loading ──────────────────────────────────────────────────────────

    fun refresh() {
        val account = activeAccount.value ?: return
        loadFeed(account, clearEvents = false)
    }

    private fun loadFeed(account: Account, clearEvents: Boolean = true) {
        collectJob?.cancel()
        activeSubIds.clear()
        unsub(feedSubId); feedSubId = null
        unsub(followSubId); followSubId = null
        unsub(metadataSubId); metadataSubId = null
        unsub(relayListSubId); relayListSubId = null

        feedSettled = false
        followsReceived = false
        pendingBuffer.clear()
        requestedQuoteIds.clear()

        _uiState.update {
            if (clearEvents) it.copy(events = emptyList(), isLoading = true, error = null)
            else it.copy(isLoading = true, error = null)
        }

        // Preload cached events on restart (same account). Skip on account switch to
        // avoid showing stale events from a different account's follow graph.
        val isSameAccount = lastLoadedPubkey == account.pubkey
        lastLoadedPubkey = account.pubkey
        if (isSameAccount) {
            viewModelScope.launch {
                val cached = eventRepository.getRecentFeedEvents(account.pubkey)
                if (cached.isNotEmpty()) {
                    _uiState.update { state ->
                        val merged = (state.events + cached)
                            .distinctBy { it.id }
                            .sortedByDescending { it.createdAt }
                        state.copy(events = merged)
                    }
                    val cachedPubkeys = (cached.map { it.pubkey } +
                        cached.filter { it.kind == EventKind.TEXT_NOTE }
                            .flatMap { it.parsedTags.pubkeys }
                        ).distinct()
                    unsub(metadataSubId)
                    metadataSubId = sub(listOf(
                        Filter(authors = cachedPubkeys, kinds = listOf(EventKind.METADATA))
                    ))
                }
            }
        }

        val relays = account.relays.ifEmpty { DEFAULT_RELAYS }
        relays.forEach { pool.addRelay(it) }

        // Own notes first; events buffer until EOSE, then the follow list subscription
        // replaces this and buffers again until its own EOSE — feed appears atomically.
        feedSubId = sub(listOf(
            Filter(
                authors = listOf(account.pubkey),
                kinds = listOf(EventKind.TEXT_NOTE, EventKind.REPOST),
                limit = 50,
            )
        ))

        // Fetch follow list (kind-3), own profile (kind-0), and relay list (kind-10002) in parallel
        followSubId = sub(listOf(
            Filter(authors = listOf(account.pubkey), kinds = listOf(EventKind.FOLLOW_LIST), limit = 1)
        ))
        metadataSubId = sub(listOf(
            Filter(authors = listOf(account.pubkey), kinds = listOf(EventKind.METADATA), limit = 1)
        ))
        relayListSubId = sub(listOf(
            Filter(authors = listOf(account.pubkey), kinds = listOf(EventKind.RELAY_LIST), limit = 1)
        ))

        collectJob = viewModelScope.launch(Dispatchers.Default) { collectMessages() }

        // Safety net: flush buffer and clear spinner after 15s regardless
        viewModelScope.launch {
            delay(15_000)
            if (!feedSettled) {
                feedSettled = true
                flushBuffer()
            }
            _uiState.update { if (it.isLoading) it.copy(isLoading = false) else it }
        }
    }

    /** Subscribe and track the ID so we can filter events by it. */
    private fun sub(filters: List<Filter>): String =
        pool.subscribe(filters).also { activeSubIds.add(it) }

    /** Unsubscribe and stop tracking the ID. */
    private fun unsub(id: String?) {
        id ?: return
        activeSubIds.remove(id)
        pool.unsubscribe(id)
    }

    private suspend fun collectMessages() {
        pool.messages.collect { poolMessage ->
            when (val msg = poolMessage.message) {
                is RelayMessage.EventMessage -> {
                    if (msg.subscriptionId !in activeSubIds) return@collect
                    if (msg.event.verify()) handleEvent(msg.event)
                }
                is RelayMessage.EndOfStoredEvents -> {
                    if (msg.subscriptionId !in activeSubIds) return@collect
                    when {
                        // Expanded feed (after follow list) is ready — show everything.
                        msg.subscriptionId == feedSubId && followsReceived && !feedSettled -> {
                            feedSettled = true
                            flushBuffer()
                        }
                        // Follow subscription returned nothing (user has no follows) —
                        // fall back to showing own notes.
                        msg.subscriptionId == followSubId && !followsReceived && !feedSettled -> {
                            feedSettled = true
                            flushBuffer()
                        }
                    }
                    _uiState.update { it.copy(isLoading = false) }
                }
                else -> Unit
            }
        }
    }

    private fun handleEvent(event: Event) {
        when (event.kind) {
            EventKind.FOLLOW_LIST -> expandFeedToFollows(event)
            EventKind.METADATA    -> {
                profileRepository.processEvent(event)
                val account = activeAccount.value ?: return
                if (event.pubkey == account.pubkey) {
                    val content = ProfileContent.parse(event.content) ?: return
                    viewModelScope.launch {
                        accountRepository.updateAccount(
                            account.copy(
                                displayName = content.bestName,
                                pictureUrl = content.picture,
                            )
                        )
                    }
                }
            }
            EventKind.RELAY_LIST  -> handleRelayList(event)
            EventKind.TEXT_NOTE,
            EventKind.REPOST      -> addToFeed(event)
        }
    }

    /**
     * Called when the follow list (kind-3) arrives.
     * Replaces the narrow own-notes subscription with a full home feed
     * and kicks off a metadata fetch for all followed pubkeys.
     */
    private fun expandFeedToFollows(event: Event) {
        val followed = event.parsedTags.pubkeys
        if (followed.isEmpty()) return

        unsub(followSubId); followSubId = null

        val selfPubkey = activeAccount.value?.pubkey ?: return
        val allPubkeys = (followed + selfPubkey).distinct()

        // Follow list received — expand to full feed and wait for its EOSE.
        followsReceived = true
        unsub(feedSubId)
        feedSettled = false
        feedSubId = sub(listOf(
            Filter(
                authors = allPubkeys,
                kinds = listOf(EventKind.TEXT_NOTE, EventKind.REPOST),
                limit = 200,
            )
        ))

        unsub(metadataSubId)
        metadataSubId = sub(listOf(
            Filter(authors = allPubkeys, kinds = listOf(EventKind.METADATA))
        ))
    }

    /**
     * NIP-65: when the user's relay list arrives, connect to their preferred relays
     * and persist the list so it's used as the default on next startup.
     */
    private fun handleRelayList(event: Event) {
        val readRelays = event.parsedTags
            .filter { it.name == "r" }
            .mapNotNull { tag ->
                val url = tag.value() ?: return@mapNotNull null
                val marker = tag.value(2)
                if (marker == null || marker == "read") url else null
            }
        if (readRelays.isEmpty()) return

        unsub(relayListSubId); relayListSubId = null

        readRelays.forEach { pool.addRelay(it) }

        viewModelScope.launch {
            activeAccount.value?.let { account ->
                accountRepository.updateAccount(account.copy(relays = readRelays))
            }
        }
    }

    private fun addToFeed(event: Event) {
        val accountPubkey = activeAccount.value?.pubkey ?: return
        viewModelScope.launch { eventRepository.save(event, accountPubkey) }
        if (!feedSettled) {
            pendingBuffer.add(event)
            return
        }
        _uiState.update { state ->
            val updated = (state.events + event)
                .distinctBy { it.id }
                .sortedByDescending { it.createdAt }
            state.copy(events = updated)
        }
        fetchQuotesForEvents(listOf(event))
        fetchProfilesForMentions(listOf(event))
    }

    /** Flush buffered events into the UI all at once, sorted by recency, then scroll to top. */
    private fun flushBuffer() {
        val events = pendingBuffer.toList().also { pendingBuffer.clear() }
        if (events.isEmpty()) return
        _uiState.update { state ->
            val merged = (state.events + events)
                .distinctBy { it.id }
                .sortedByDescending { it.createdAt }
            state.copy(events = merged)
        }
        _scrollToTop.tryEmit(Unit)
        fetchQuotesForEvents(events)
        fetchProfilesForMentions(events)
    }

    private fun fetchQuotesForEvents(events: List<Event>) {
        val toResolve = mutableListOf<String>()

        for (event in events) {
            when (event.kind) {
                EventKind.REPOST -> {
                    // Kind-6: try parsing the embedded event from content first
                    val embedded = runCatching { Event.fromJson(event.content) }.getOrNull()
                    if (embedded != null && embedded.verify()) {
                        _quotedEvents.update { it + (embedded.id to embedded) }
                        viewModelScope.launch { eventRepository.save(embedded, "") }
                        fetchMetadataForAuthors(listOf(embedded.pubkey))
                    } else {
                        // Fall back to fetching by e tag
                        val refId = event.parsedTags.firstOrNull { it.name == "e" }?.value()
                        if (refId != null && refId !in requestedQuoteIds) toResolve.add(refId)
                    }
                }
                EventKind.TEXT_NOTE -> {
                    val qId = event.parsedTags.quotedEventId
                        ?: extractInlineQuoteId(event.content)
                    if (qId != null && qId !in requestedQuoteIds) toResolve.add(qId)
                }
            }
        }

        val missing = toResolve.distinct().filter { it !in _quotedEvents.value }
        if (missing.isEmpty()) return
        missing.forEach { requestedQuoteIds.add(it) }

        viewModelScope.launch {
            val cached = eventRepository.getByIds(missing).associateBy { it.id }
            if (cached.isNotEmpty()) {
                _quotedEvents.update { it + cached }
                fetchMetadataForAuthors(cached.values.map { it.pubkey })
            }
            val stillMissing = missing.filter { it !in cached }
            if (stillMissing.isEmpty()) return@launch

            val subId = pool.subscribe(listOf(Filter(ids = stillMissing, kinds = listOf(EventKind.TEXT_NOTE))))
            pool.messages.collect { poolMsg ->
                val msg = poolMsg.message
                if (msg is RelayMessage.EventMessage
                    && msg.subscriptionId == subId
                    && msg.event.id in stillMissing
                    && msg.event.verify()
                ) {
                    eventRepository.save(msg.event, "")
                    _quotedEvents.update { it + (msg.event.id to msg.event) }
                    fetchMetadataForAuthors(listOf(msg.event.pubkey))
                }
                if (msg is RelayMessage.EndOfStoredEvents && msg.subscriptionId == subId) {
                    pool.unsubscribe(subId)
                }
            }
        }
    }

    private fun fetchMetadataForAuthors(pubkeys: List<String>) {
        val unknown = pubkeys.distinct().filter { profileRepository.profiles.value[it] == null }
        if (unknown.isEmpty()) return
        sub(listOf(Filter(authors = unknown, kinds = listOf(EventKind.METADATA))))
    }

    /** Fetch kind-0 for any pubkeys mentioned via p-tags in text notes. */
    private fun fetchProfilesForMentions(events: List<Event>) {
        val mentioned = events
            .filter { it.kind == EventKind.TEXT_NOTE }
            .flatMap { it.parsedTags.pubkeys }
        fetchMetadataForAuthors(mentioned)
    }

    private fun clearFeed() {
        collectJob?.cancel()
        activeSubIds.clear()
        unsub(feedSubId); feedSubId = null
        unsub(followSubId); followSubId = null
        unsub(metadataSubId); metadataSubId = null
        unsub(relayListSubId); relayListSubId = null
        _uiState.update { FeedUiState(isLoading = false) }
    }

    companion object {
        val DEFAULT_RELAYS = listOf(
            "wss://relay.damus.io",
            "wss://relay.nostr.band",
            "wss://nos.lol",
        )
    }
}
