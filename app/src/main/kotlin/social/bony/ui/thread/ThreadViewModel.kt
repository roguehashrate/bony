package social.bony.ui.thread

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import social.bony.db.EventRepository
import social.bony.nostr.Event
import social.bony.nostr.EventKind
import social.bony.nostr.Filter
import social.bony.nostr.ProfileContent
import social.bony.nostr.quotedEventId
import social.bony.nostr.relay.RelayMessage
import social.bony.nostr.relay.RelayPool
import social.bony.nostr.replyEventId
import social.bony.nostr.rootEventId
import social.bony.profile.ProfileRepository
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
) : ViewModel() {

    private val eventId: String = checkNotNull(savedStateHandle["eventId"])

    private val _uiState = MutableStateFlow(ThreadUiState(focusedEventId = eventId))
    val uiState: StateFlow<ThreadUiState> = _uiState.asStateFlow()

    val profiles: StateFlow<Map<String, ProfileContent>> = profileRepository.profiles
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private val _quotedEvents = MutableStateFlow<Map<String, Event>>(emptyMap())
    val quotedEvents: StateFlow<Map<String, Event>> = _quotedEvents.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.Default) { loadThread() }
    }

    // ── Loading ───────────────────────────────────────────────────────────────

    private suspend fun loadThread() {
        val focused = eventRepository.getById(eventId) ?: run {
            fetchFocusedFromRelay()
            return
        }
        _uiState.update { it.copy(focused = focused) }
        loadContext(focused)
    }

    /** Fetch the focused event from relay when it's not in the local cache. */
    private suspend fun fetchFocusedFromRelay() {
        val subId = pool.subscribe(listOf(
            Filter(ids = listOf(eventId), kinds = listOf(EventKind.TEXT_NOTE))
        ))
        pool.messages.collect { poolMsg ->
            val msg = poolMsg.message
            when {
                msg is RelayMessage.EventMessage
                    && msg.subscriptionId == subId
                    && msg.event.id == eventId
                    && msg.event.verify() -> {
                    pool.unsubscribe(subId)
                    eventRepository.save(msg.event, "")
                    _uiState.update { it.copy(focused = msg.event) }
                    loadContext(msg.event)
                }
                msg is RelayMessage.EndOfStoredEvents && msg.subscriptionId == subId -> {
                    pool.unsubscribe(subId)
                    _uiState.update { it.copy(isLoading = false) }
                }
            }
        }
    }

    /**
     * Loads the thread context for the focused event:
     * - root: the root of the conversation (NIP-10 "root" marker)
     * - parent: the direct reply target (NIP-10 "reply" marker), if different from root
     *
     * Also starts the live replies subscription.
     */
    private suspend fun loadContext(focused: Event) {
        val rootId = focused.parsedTags.rootEventId
        val replyId = focused.parsedTags.replyEventId

        // Treat parent as distinct from root only if they differ
        val parentId = replyId?.takeIf { it != rootId }
        val toFetch = listOfNotNull(rootId, parentId).distinct()

        if (toFetch.isEmpty()) {
            // Top-level note — no context above it
            _uiState.update { it.copy(isLoading = false) }
            subscribeReplies()
            fetchProfiles(listOf(focused))
            fetchQuotesForEvents(listOf(focused))
            return
        }

        // Seed from cache
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
            subscribeReplies()
            val allEvents = listOfNotNull(_uiState.value.root, _uiState.value.parent, focused)
            fetchProfiles(allEvents)
            fetchQuotesForEvents(allEvents)
            return
        }

        val subId = pool.subscribe(listOf(
            Filter(ids = missing, kinds = listOf(EventKind.TEXT_NOTE))
        ))
        pool.messages.collect { poolMsg ->
            val msg = poolMsg.message
            when {
                msg is RelayMessage.EventMessage
                    && msg.subscriptionId == subId
                    && msg.event.id in missing
                    && msg.event.verify() -> {
                    eventRepository.save(msg.event, "")
                    _uiState.update { state ->
                        state.copy(
                            root = if (msg.event.id == rootId) msg.event else state.root,
                            parent = if (msg.event.id == parentId) msg.event else state.parent,
                        )
                    }
                }
                msg is RelayMessage.EndOfStoredEvents && msg.subscriptionId == subId -> {
                    pool.unsubscribe(subId)
                    _uiState.update { it.copy(isLoading = false) }
                    val state = _uiState.value
                    subscribeReplies()
                    val allEvents = listOfNotNull(state.root, state.parent, focused)
                    fetchProfiles(allEvents)
                    fetchQuotesForEvents(allEvents)
                }
            }
        }
    }

    /** Subscribe to live replies targeting the focused event. */
    private fun subscribeReplies() {
        val subId = pool.subscribe(listOf(
            Filter(eTags = listOf(eventId), kinds = listOf(EventKind.TEXT_NOTE), limit = 50)
        ))
        viewModelScope.launch {
            pool.messages.collect { poolMsg ->
                val msg = poolMsg.message
                if (msg is RelayMessage.EventMessage
                    && msg.subscriptionId == subId
                    && msg.event.verify()
                ) {
                    eventRepository.save(msg.event, "")
                    _uiState.update { state ->
                        val updated = (state.replies + msg.event)
                            .distinctBy { it.id }
                            .sortedBy { it.createdAt }
                        state.copy(replies = updated)
                    }
                    fetchQuotesForEvents(listOf(msg.event))
                }
            }
        }
    }

    private fun fetchProfiles(events: List<Event>) {
        val pubkeys = events.map { it.pubkey }.distinct()
        pool.subscribe(listOf(Filter(authors = pubkeys, kinds = listOf(EventKind.METADATA))))
    }

    fun fetchQuotesForEvents(events: List<Event>) {
        val quoteIds = events.mapNotNull { event ->
            when (event.kind) {
                EventKind.REPOST -> {
                    val embedded = runCatching { Event.fromJson(event.content) }.getOrNull()
                    if (embedded != null && embedded.verify()) {
                        _quotedEvents.update { it + (embedded.id to embedded) }
                        viewModelScope.launch { eventRepository.save(embedded, "") }
                        null
                    } else {
                        event.parsedTags.firstOrNull { it.name == "e" }?.value()
                    }
                }
                EventKind.TEXT_NOTE -> event.parsedTags.quotedEventId
                else -> null
            }
        }.distinct().filter { it !in _quotedEvents.value }

        if (quoteIds.isEmpty()) return

        viewModelScope.launch {
            val cached = eventRepository.getByIds(quoteIds).associateBy { it.id }
            if (cached.isNotEmpty()) _quotedEvents.update { it + cached }
            val missing = quoteIds.filter { it !in cached }
            if (missing.isEmpty()) return@launch

            val subId = pool.subscribe(listOf(Filter(ids = missing, kinds = listOf(EventKind.TEXT_NOTE))))
            pool.messages.collect { poolMsg ->
                val msg = poolMsg.message
                if (msg is RelayMessage.EventMessage
                    && msg.subscriptionId == subId
                    && msg.event.id in missing
                    && msg.event.verify()
                ) {
                    eventRepository.save(msg.event, "")
                    _quotedEvents.update { it + (msg.event.id to msg.event) }
                }
                if (msg is RelayMessage.EndOfStoredEvents && msg.subscriptionId == subId) {
                    pool.unsubscribe(subId)
                }
            }
        }
    }
}
