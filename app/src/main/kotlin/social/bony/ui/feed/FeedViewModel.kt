package social.bony.ui.feed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import social.bony.account.Account
import social.bony.account.AccountRepository
import social.bony.db.EventRepository
import social.bony.nostr.Event
import social.bony.nostr.EventKind
import social.bony.nostr.Filter
import social.bony.nostr.ProfileContent
import social.bony.nostr.pubkeys
import social.bony.nostr.relay.RelayMessage
import social.bony.nostr.relay.RelayPool
import social.bony.profile.ProfileRepository
import java.util.concurrent.ConcurrentHashMap
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
) : ViewModel() {

    private val _uiState = MutableStateFlow(FeedUiState())
    val uiState: StateFlow<FeedUiState> = _uiState.asStateFlow()

    val profiles: StateFlow<Map<String, ProfileContent>> = profileRepository.profiles
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val activeAccount: StateFlow<Account?> = accountRepository.activeAccount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val accounts = accountRepository.accounts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var collectJob: Job? = null
    private var feedSubId: String? = null
    private var followSubId: String? = null
    private var metadataSubId: String? = null
    private var relayListSubId: String? = null
    private var lastLoadedPubkey: String? = null

    // Only process events belonging to current subscriptions — prevents stale events
    // from old subscriptions bleeding into a newly loaded feed.
    private val activeSubIds: MutableSet<String> = ConcurrentHashMap.newKeySet()

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
                    val cachedPubkeys = cached.map { it.pubkey }.distinct()
                    unsub(metadataSubId)
                    metadataSubId = sub(listOf(
                        Filter(authors = cachedPubkeys, kinds = listOf(EventKind.METADATA))
                    ))
                }
            }
        }

        val relays = account.relays.ifEmpty { DEFAULT_RELAYS }
        relays.forEach { pool.addRelay(it) }

        // Show own notes immediately while the follow list loads
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

        // Safety net: clear spinner after 15s regardless
        viewModelScope.launch {
            delay(15_000)
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

        unsub(feedSubId)
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
        _uiState.update { state ->
            val updated = (state.events + event)
                .distinctBy { it.id }
                .sortedByDescending { it.createdAt }
            state.copy(events = updated)
        }
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
