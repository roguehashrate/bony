package social.bony.ui.thread

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import social.bony.account.AccountRepository
import social.bony.account.signer.NostrSignerFactory
import social.bony.db.EventRepository
import social.bony.reactions.ReactionsRepository
import social.bony.nostr.Event
import social.bony.nostr.EventKind
import social.bony.nostr.Filter
import social.bony.nostr.ProfileContent
import social.bony.nostr.UnsignedEvent
import social.bony.nostr.quotedEventId
import social.bony.nostr.relay.PoolMessage
import social.bony.nostr.relay.RelayMessage
import social.bony.ui.feed.extractInlineQuoteId
import social.bony.nostr.relay.RelayPool
import social.bony.nostr.replyEventId
import social.bony.nostr.rootEventId
import social.bony.profile.ProfileRepository
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

data class ThreadUiState(
    val root: Event? = null,       // root of the thread (may be null if top-level or not found)
    val parent: Event? = null,     // direct parent of focused (null if root == parent or top-level)
    val focused: Event? = null,    // the note that was tapped
    val replies: List<Event> = emptyList(),
    val showGap: Boolean = false,  // true when root ≠ parent, indicating hidden intermediate replies
    val isLoading: Boolean = true,
    val focusedEventId: String = "",
)

@HiltViewModel
class ThreadViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val eventRepository: EventRepository,
    private val profileRepository: ProfileRepository,
    private val pool: RelayPool,
    private val signerFactory: NostrSignerFactory,
    private val reactionsRepository: ReactionsRepository,
    private val accountRepository: AccountRepository,
) : ViewModel() {

    private val eventId: String = checkNotNull(savedStateHandle["eventId"])

    private val _uiState = MutableStateFlow(ThreadUiState(focusedEventId = eventId))
    val uiState: StateFlow<ThreadUiState> = _uiState.asStateFlow()

    val profiles: StateFlow<Map<String, ProfileContent>> = profileRepository.profiles
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private val _quotedEvents = MutableStateFlow<Map<String, Event>>(emptyMap())
    val quotedEvents: StateFlow<Map<String, Event>> = _quotedEvents.asStateFlow()

    val reactions: StateFlow<Map<String, Set<String>>> = reactionsRepository.reactions
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val activePubkey: StateFlow<String?> = accountRepository.activeAccount
        .map { it?.pubkey }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private var collectJob: Job? = null
    private val activeSubIds: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val quoteSubIds: MutableSet<String> = ConcurrentHashMap.newKeySet()

    // Phase sub IDs
    private var focusedSubId: String? = null
    private var contextSubId: String? = null
    private var repliesSubId: String? = null

    // Context needed to route incoming events to the right fields
    @Volatile private var pendingRootId: String? = null
    @Volatile private var pendingParentId: String? = null

    init {
        collectJob = viewModelScope.launch(Dispatchers.Default) {
            startLoad()
            pool.messages.collect { handlePoolMessage(it) }
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

    // ── Loading ───────────────────────────────────────────────────────────────

    private suspend fun startLoad() {
        val focused = eventRepository.getById(eventId)
        if (focused != null) {
            _uiState.update { it.copy(focused = focused) }
            initContext(focused)
        } else {
            focusedSubId = pool.subscribe(listOf(
                Filter(ids = listOf(eventId), kinds = listOf(EventKind.TEXT_NOTE))
            )).also { activeSubIds.add(it) }
        }
    }

    private suspend fun initContext(focused: Event) {
        val rootId = focused.parsedTags.rootEventId
        val replyId = focused.parsedTags.replyEventId
        val parentId = replyId?.takeIf { it != rootId }
        val toFetch = listOfNotNull(rootId, parentId).distinct()

        if (toFetch.isEmpty()) {
            _uiState.update { it.copy(isLoading = false) }
            startRepliesAndReactions(focused)
            return
        }

        val cached = eventRepository.getByIds(toFetch).associateBy { it.id }
        _uiState.update { state ->
            state.copy(
                root = rootId?.let { cached[it] },
                parent = parentId?.let { cached[it] },
                showGap = rootId != null && parentId != null,
            )
        }

        val missing = toFetch.filter { it !in cached }
        if (missing.isEmpty()) {
            _uiState.update { it.copy(isLoading = false) }
            startRepliesAndReactions(focused)
            return
        }

        pendingRootId = rootId
        pendingParentId = parentId
        contextSubId = pool.subscribe(listOf(
            Filter(ids = missing, kinds = listOf(EventKind.TEXT_NOTE))
        )).also { activeSubIds.add(it) }
    }

    private fun startRepliesAndReactions(focused: Event) {
        val state = _uiState.value
        val allEvents = listOfNotNull(state.root, state.parent, focused)
        repliesSubId = pool.subscribe(listOf(
            Filter(eTags = listOf(eventId), kinds = listOf(EventKind.TEXT_NOTE), limit = 50)
        )).also { activeSubIds.add(it) }
        fetchProfiles(allEvents)
        fetchQuotesForEvents(allEvents)
        reactionsRepository.subscribeTo(allEvents.map { it.id })
    }

    // ── Single message collector ──────────────────────────────────────────────

    private suspend fun handlePoolMessage(poolMsg: PoolMessage) {
        val msg = poolMsg.message
        when {
            msg is RelayMessage.EventMessage -> when {
                msg.subscriptionId == focusedSubId
                    && msg.event.id == eventId
                    && msg.event.verify() -> {
                    viewModelScope.launch { eventRepository.save(msg.event, "") }
                    _uiState.update { it.copy(focused = msg.event) }
                }
                msg.subscriptionId == contextSubId && msg.event.verify() -> {
                    viewModelScope.launch { eventRepository.save(msg.event, "") }
                    _uiState.update { state ->
                        state.copy(
                            root = if (msg.event.id == pendingRootId) msg.event else state.root,
                            parent = if (msg.event.id == pendingParentId) msg.event else state.parent,
                        )
                    }
                }
                msg.subscriptionId == repliesSubId && msg.event.verify() -> {
                    viewModelScope.launch { eventRepository.save(msg.event, "") }
                    _uiState.update { state ->
                        val updated = (state.replies + msg.event)
                            .distinctBy { it.id }
                            .sortedBy { it.createdAt }
                        state.copy(replies = updated)
                    }
                    fetchQuotesForEvents(listOf(msg.event))
                    reactionsRepository.subscribeTo(listOf(msg.event.id))
                }
                msg.subscriptionId in quoteSubIds && msg.event.verify() -> {
                    handleQuoteEvent(msg.event)
                }
                msg.event.kind == EventKind.METADATA && msg.event.verify() -> {
                    profileRepository.processEvent(msg.event)
                }
            }
            msg is RelayMessage.EndOfStoredEvents -> when {
                msg.subscriptionId == focusedSubId -> {
                    activeSubIds.remove(msg.subscriptionId)
                    pool.unsubscribe(msg.subscriptionId)
                    focusedSubId = null
                    val focused = _uiState.value.focused
                    if (focused != null) initContext(focused)
                    else _uiState.update { it.copy(isLoading = false) }
                }
                msg.subscriptionId == contextSubId -> {
                    activeSubIds.remove(msg.subscriptionId)
                    pool.unsubscribe(msg.subscriptionId)
                    contextSubId = null
                    _uiState.update { it.copy(isLoading = false) }
                    val focused = _uiState.value.focused ?: return
                    startRepliesAndReactions(focused)
                }
                msg.subscriptionId in quoteSubIds -> {
                    quoteSubIds.remove(msg.subscriptionId)
                    pool.unsubscribe(msg.subscriptionId)
                }
            }
        }
    }

    private fun handleQuoteEvent(event: Event) {
        _quotedEvents.update { it + (event.id to event) }
        viewModelScope.launch { eventRepository.save(event, "") }
        fetchMetadataForAuthors(listOf(event.pubkey))
    }

    private fun fetchProfiles(events: List<Event>) {
        val pubkeys = events.map { it.pubkey }.distinct()
        pool.subscribe(listOf(Filter(authors = pubkeys, kinds = listOf(EventKind.METADATA))))
            .also { activeSubIds.add(it) }
    }

    fun fetchQuotesForEvents(events: List<Event>) {
        val toResolve = mutableListOf<String>()
        for (event in events) {
            when (event.kind) {
                EventKind.REPOST -> {
                    val embedded = runCatching { Event.fromJson(event.content) }.getOrNull()
                    if (embedded != null && embedded.verify()) {
                        _quotedEvents.update { it + (embedded.id to embedded) }
                        viewModelScope.launch { eventRepository.save(embedded, "") }
                    } else {
                        event.parsedTags.firstOrNull { it.name == "e" }?.value()
                            ?.takeIf { it !in _quotedEvents.value }
                            ?.let { toResolve.add(it) }
                    }
                }
                EventKind.TEXT_NOTE -> {
                    (event.parsedTags.quotedEventId ?: extractInlineQuoteId(event.content))
                        ?.takeIf { it !in _quotedEvents.value }
                        ?.let { toResolve.add(it) }
                }
                else -> Unit
            }
        }

        val missing = toResolve.distinct().filter { it !in _quotedEvents.value }
        if (missing.isEmpty()) return

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
        pool.subscribe(listOf(Filter(authors = unknown, kinds = listOf(EventKind.METADATA))))
            .also { activeSubIds.add(it) }
    }

    override fun onCleared() {
        collectJob?.cancel()
        activeSubIds.forEach { pool.unsubscribe(it) }
        quoteSubIds.forEach { pool.unsubscribe(it) }
    }
}
