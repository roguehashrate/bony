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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import social.bony.account.Account
import social.bony.account.AccountRepository
import social.bony.db.EventRepository
import social.bony.reactions.ReactionsRepository
import social.bony.settings.AppSettings
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

enum class FeedTab { HOME, GLOBAL }

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
    private val reactionsRepository: ReactionsRepository,
    private val appSettings: AppSettings,
) : ViewModel() {

    private val _uiState = MutableStateFlow(FeedUiState())
    val uiState: StateFlow<FeedUiState> = _uiState.asStateFlow()

    private val _scrollToTop = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val scrollToTop: SharedFlow<Unit> = _scrollToTop.asSharedFlow()

    val profiles: StateFlow<Map<String, ProfileContent>> = profileRepository.profiles
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private val _currentFeed = MutableStateFlow(FeedTab.HOME)
    val currentFeed: StateFlow<FeedTab> = _currentFeed.asStateFlow()

    private val _quotedEvents = MutableStateFlow<Map<String, Event>>(emptyMap())
    val quotedEvents: StateFlow<Map<String, Event>> = _quotedEvents.asStateFlow()

    val reactions: StateFlow<Map<String, Set<String>>> = reactionsRepository.reactions
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val torEnabled: StateFlow<Boolean> = appSettings.torEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)


    val activeAccount: StateFlow<Account?> = accountRepository.activeAccount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val relayStatuses: StateFlow<Map<String, RelayStatus>> = pool.relayStatuses
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val accounts = accountRepository.accounts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // Tracks quote IDs already requested so we don't issue duplicate subscriptions
    private val requestedQuoteIds: MutableSet<String> = ConcurrentHashMap.newKeySet()

    // One-shot subscriptions for fetching quoted/reposted events — handled in collectMessages()
    private val quoteSubIds: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private var collectJob: Job? = null
    private var feedSubId: String? = null
    private var followSubId: String? = null
    private var metadataSubId: String? = null
    private var relayListSubId: String? = null
    @Volatile private var followsRelayListSubId: String? = null
    @Volatile private var followsForRelaySelection: List<String> = emptyList()
    private var lastLoadedPubkey: String? = null

    // outbox model: relay URL → set of follow pubkeys that write there
    private val followRelayMap: ConcurrentHashMap<String, MutableSet<String>> = ConcurrentHashMap()

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
            // Only reload the feed when the active pubkey changes, not on every
            // account property update (relays, follows, displayName, etc.).
            // Incremental updates like relay selection and follow list persist
            // to the account but must not restart the feed.
            accountRepository.activeAccount
                .distinctUntilChanged { old, new -> old?.pubkey == new?.pubkey }
                .collect { account ->
                    if (account != null) loadFeed(account) else clearFeed()
                }
        }
    }

    fun switchAccount(pubkey: String) {
        viewModelScope.launch { accountRepository.setActiveAccount(pubkey) }
    }

    fun switchFeed(tab: FeedTab) {
        if (_currentFeed.value == tab) return
        _currentFeed.update { tab }
        val account = activeAccount.value ?: return
        when (tab) {
            FeedTab.HOME -> loadFeed(account)
            FeedTab.GLOBAL -> loadGlobalFeed(account)
        }
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

    fun react(event: Event) = reactionsRepository.react(event)

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
        unsub(followsRelayListSubId); followsRelayListSubId = null
        followsForRelaySelection = emptyList()
        followRelayMap.clear()

        feedSettled = false
        followsReceived = false
        pendingBuffer.clear()
        requestedQuoteIds.clear()
        quoteSubIds.forEach { pool.unsubscribe(it) }
        quoteSubIds.clear()

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
                    fetchProfilesForEvents(cached)
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

    private fun loadGlobalFeed(account: Account) {
        collectJob?.cancel()
        activeSubIds.clear()
        unsub(feedSubId); feedSubId = null
        unsub(followSubId); followSubId = null
        unsub(metadataSubId); metadataSubId = null
        unsub(relayListSubId); relayListSubId = null
        unsub(followsRelayListSubId); followsRelayListSubId = null
        followsForRelaySelection = emptyList()
        followRelayMap.clear()

        feedSettled = false
        pendingBuffer.clear()
        requestedQuoteIds.clear()
        quoteSubIds.forEach { pool.unsubscribe(it) }
        quoteSubIds.clear()

        // Reset so returning to HOME skips the cache preload — global events must not
        // bleed into the home feed cache.
        lastLoadedPubkey = null

        _uiState.update { it.copy(events = emptyList(), isLoading = true, error = null) }

        val relays = account.relays.ifEmpty { DEFAULT_RELAYS }
        relays.forEach { pool.addRelay(it) }

        feedSubId = sub(listOf(
            Filter(kinds = listOf(EventKind.TEXT_NOTE, EventKind.REPOST), limit = 100)
        ))

        // No follow-list phase — mark followsReceived so EOSE on feedSubId triggers flush
        followsReceived = true

        collectJob = viewModelScope.launch(Dispatchers.Default) { collectMessages() }

        viewModelScope.launch {
            delay(15_000)
            if (!feedSettled) { feedSettled = true; flushBuffer() }
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
                    when {
                        msg.subscriptionId in activeSubIds -> {
                            if (msg.event.verify()) handleEvent(msg.event)
                        }
                        msg.subscriptionId in quoteSubIds -> {
                            if (msg.event.verify()) handleQuoteEvent(msg.event)
                        }
                    }
                }
                is RelayMessage.EndOfStoredEvents -> {
                    when {
                        msg.subscriptionId in quoteSubIds -> {
                            quoteSubIds.remove(msg.subscriptionId)
                            pool.unsubscribe(msg.subscriptionId)
                        }
                        msg.subscriptionId !in activeSubIds -> return@collect
                        msg.subscriptionId == followsRelayListSubId -> {
                            unsub(followsRelayListSubId); followsRelayListSubId = null
                            val follows = followsForRelaySelection
                            if (follows.isNotEmpty()) selectAndApplyRelays(follows)
                        }
                        // Expanded feed (after follow list) is ready — show everything.
                        msg.subscriptionId == feedSubId && followsReceived && !feedSettled -> {
                            feedSettled = true
                            flushBuffer()
                            _uiState.update { it.copy(isLoading = false) }
                        }
                        // Follow subscription returned nothing (user has no follows) —
                        // fall back to showing own notes.
                        msg.subscriptionId == followSubId && !followsReceived && !feedSettled -> {
                            feedSettled = true
                            flushBuffer()
                            _uiState.update { it.copy(isLoading = false) }
                        }
                        else -> _uiState.update { it.copy(isLoading = false) }
                    }
                }
                else -> Unit
            }
        }
    }

    private fun handleQuoteEvent(event: Event) {
        _quotedEvents.update { it + (event.id to event) }
        viewModelScope.launch { eventRepository.save(event, "") }
        fetchMetadataForAuthors(listOf(event.pubkey))
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
            EventKind.RELAY_LIST  -> {
                val selfPubkey = activeAccount.value?.pubkey ?: return
                if (event.pubkey == selfPubkey) handleRelayList(event)
                else handleFollowRelayList(event)
            }
            EventKind.TEXT_NOTE   -> {
                // Skip replies — notes with an e-tag are responses to other notes
                if (event.parsedTags.any { it.name == "e" }) return
                addToFeed(event)
            }
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

        // Persist follow list so ProfileViewModel can read it without re-fetching
        viewModelScope.launch {
            activeAccount.value?.let { account ->
                accountRepository.updateAccount(account.copy(follows = followed))
            }
        }

        // Outbox model: fetch kind-10002 for all follows to discover their write relays,
        // then run greedy set cover to select the ~7 relays that cover the most follows.
        unsub(followsRelayListSubId)
        followRelayMap.clear()
        followsForRelaySelection = followed
        followsRelayListSubId = sub(listOf(
            Filter(authors = followed, kinds = listOf(EventKind.RELAY_LIST))
        ))
        viewModelScope.launch {
            delay(5_000)
            if (followsRelayListSubId != null) {
                unsub(followsRelayListSubId); followsRelayListSubId = null
                selectAndApplyRelays(followed)
            }
        }
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
        // Only persist home feed events — global feed is a live view and must not
        // pollute the cache that getRecentFeedEvents returns on next home load.
        if (_currentFeed.value == FeedTab.HOME) {
            val accountPubkey = activeAccount.value?.pubkey ?: return
            viewModelScope.launch { eventRepository.save(event, accountPubkey) }
        }
        if (!feedSettled) {
            pendingBuffer.add(event)
            return
        }
        _uiState.update { state ->
            val updated = (state.events + event)
                .distinctBy { it.id }
                .sortedByDescending { it.createdAt }
                .take(MAX_FEED_EVENTS)
            state.copy(events = updated)
        }
        fetchQuotesForEvents(listOf(event))
        fetchProfilesForEvents(listOf(event))
        reactionsRepository.subscribeTo(listOf(event.id))
    }

    /** Flush buffered events into the UI all at once, sorted by recency, then scroll to top. */
    private fun flushBuffer() {
        val events = pendingBuffer.toList().also { pendingBuffer.clear() }
        if (events.isEmpty()) return
        _uiState.update { state ->
            val merged = (state.events + events)
                .distinctBy { it.id }
                .sortedByDescending { it.createdAt }
                .take(MAX_FEED_EVENTS)
            state.copy(events = merged)
        }
        _scrollToTop.tryEmit(Unit)
        fetchQuotesForEvents(events)
        fetchProfilesForEvents(events)
        reactionsRepository.subscribeTo(events.map { it.id })
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
            quoteSubIds.add(subId)
        }
    }

    private fun fetchMetadataForAuthors(pubkeys: List<String>) {
        val unknown = pubkeys.distinct().filter { profileRepository.profiles.value[it] == null }
        if (unknown.isEmpty()) return
        sub(listOf(Filter(authors = unknown, kinds = listOf(EventKind.METADATA))))
    }

    /** Fetch kind-0 for note authors and any pubkeys mentioned via p-tags. */
    private fun fetchProfilesForEvents(events: List<Event>) {
        val pubkeys = events.flatMap { event ->
            buildList {
                add(event.pubkey)
                if (event.kind == EventKind.TEXT_NOTE) addAll(event.parsedTags.pubkeys)
            }
        }
        fetchMetadataForAuthors(pubkeys)
    }

    private fun clearFeed() {
        collectJob?.cancel()
        activeSubIds.clear()
        unsub(feedSubId); feedSubId = null
        unsub(followSubId); followSubId = null
        unsub(metadataSubId); metadataSubId = null
        unsub(relayListSubId); relayListSubId = null
        unsub(followsRelayListSubId); followsRelayListSubId = null
        followsForRelaySelection = emptyList()
        followRelayMap.clear()
        quoteSubIds.forEach { pool.unsubscribe(it) }
        quoteSubIds.clear()
        _uiState.update { FeedUiState(isLoading = false) }
    }

    /**
     * NIP-65: collect write relays for a follow so we can run outbox relay selection.
     */
    private fun handleFollowRelayList(event: Event) {
        val writeRelays = event.parsedTags
            .filter { it.name == "r" }
            .mapNotNull { tag ->
                val url = tag.value() ?: return@mapNotNull null
                val marker = tag.value(2)
                if (marker == null || marker == "write") url else null
            }
        writeRelays.forEach { relay ->
            followRelayMap.getOrPut(relay) { ConcurrentHashMap.newKeySet() }.add(event.pubkey)
        }
    }

    /**
     * Greedy set cover: selects up to [maxRelays] relay URLs that collectively cover
     * the most follow pubkeys, favouring relays with the broadest reach first.
     */
    private fun greedyRelaySelection(follows: List<String>, maxRelays: Int = MAX_OUTBOX_RELAYS): List<String> {
        if (followRelayMap.isEmpty()) return emptyList()
        val uncovered = follows.toMutableSet()
        val selected = mutableListOf<String>()
        while (uncovered.isNotEmpty() && selected.size < maxRelays) {
            val best = followRelayMap.entries
                .filter { it.key !in selected }
                .maxByOrNull { entry -> entry.value.count { it in uncovered } }
                ?: break
            if (best.value.none { it in uncovered }) break
            selected.add(best.key)
            uncovered.removeAll(best.value)
        }
        Timber.d("Relay selection: ${selected.size} relays cover ${follows.size - uncovered.size}/${follows.size} follows")
        return selected
    }

    /**
     * Applies the greedy relay selection: adds new relays, removes stale ones,
     * and persists the result to the account.
     */
    private fun selectAndApplyRelays(follows: List<String>) {
        val outbox = greedyRelaySelection(follows)
        // Always include DEFAULT_RELAYS as a floor; outbox relays are additive when available.
        val newRelays = (outbox + DEFAULT_RELAYS).distinct()
        val current = pool.relayUrls()
        newRelays.forEach { pool.addRelay(it) }
        (current - newRelays.toSet()).forEach { pool.removeRelay(it) }
        Timber.d("Applied relay set (${newRelays.size}): $newRelays")
        viewModelScope.launch {
            activeAccount.value?.let { account ->
                accountRepository.updateAccount(account.copy(relays = newRelays))
            }
        }
    }

    companion object {
        private const val MAX_FEED_EVENTS = 300
        private const val MAX_OUTBOX_RELAYS = 7

        val DEFAULT_RELAYS = listOf(
            "wss://relay.damus.io",
            "wss://relay.nostr.band",
            "wss://nos.lol",
        )
    }
}
