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
import social.bony.nostr.eventIds
import social.bony.nostr.relay.RelayMessage
import social.bony.nostr.relay.RelayPool
import social.bony.profile.ProfileRepository
import javax.inject.Inject

data class ThreadUiState(
    val thread: List<Event> = emptyList(),
    val focusedEventId: String = "",
    val isLoading: Boolean = true,
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

    init {
        viewModelScope.launch(Dispatchers.Default) { loadThread() }
    }

    private suspend fun loadThread() {
        val focusedEvent = eventRepository.getById(eventId) ?: run {
            // Not cached — fetch from relay
            fetchEventFromRelay(eventId)
            return
        }
        buildThread(focusedEvent)
    }

    private fun buildThread(focusedEvent: Event) {
        val parentIds = focusedEvent.parsedTags.eventIds
        if (parentIds.isEmpty()) {
            _uiState.update { it.copy(thread = listOf(focusedEvent), isLoading = false) }
            return
        }

        viewModelScope.launch {
            val localParents = eventRepository.getByIds(parentIds).associateBy { it.id }
            val missing = parentIds.filter { it !in localParents }

            val initialThread = (localParents.values + focusedEvent)
                .distinctBy { it.id }
                .sortedBy { it.createdAt }
            _uiState.update { it.copy(thread = initialThread) }

            if (missing.isEmpty()) {
                _uiState.update { it.copy(isLoading = false) }
                fetchProfiles(initialThread)
                return@launch
            }

            val subId = pool.subscribe(listOf(
                Filter(ids = missing, kinds = listOf(EventKind.TEXT_NOTE, EventKind.REPOST))
            ))

            pool.messages.collect { poolMsg ->
                val msg = poolMsg.message
                when {
                    msg is RelayMessage.EventMessage && msg.event.id in missing && msg.event.verify() -> {
                        eventRepository.save(msg.event, "")
                        _uiState.update { state ->
                            val updated = (state.thread + msg.event)
                                .distinctBy { it.id }
                                .sortedBy { it.createdAt }
                            state.copy(thread = updated)
                        }
                    }
                    msg is RelayMessage.EndOfStoredEvents -> {
                        _uiState.update { it.copy(isLoading = false) }
                        pool.unsubscribe(subId)
                        fetchProfiles(_uiState.value.thread)
                    }
                }
            }
        }
    }

    private fun fetchEventFromRelay(id: String) {
        val subId = pool.subscribe(listOf(
            Filter(ids = listOf(id), kinds = listOf(EventKind.TEXT_NOTE, EventKind.REPOST))
        ))
        viewModelScope.launch {
            pool.messages.collect { poolMsg ->
                val msg = poolMsg.message
                when {
                    msg is RelayMessage.EventMessage && msg.event.id == id && msg.event.verify() -> {
                        eventRepository.save(msg.event, "")
                        pool.unsubscribe(subId)
                        buildThread(msg.event)
                    }
                    msg is RelayMessage.EndOfStoredEvents -> {
                        _uiState.update { it.copy(isLoading = false) }
                        pool.unsubscribe(subId)
                    }
                }
            }
        }
    }

    private fun fetchProfiles(events: List<Event>) {
        val pubkeys = events.map { it.pubkey }.distinct()
        pool.subscribe(listOf(
            Filter(authors = pubkeys, kinds = listOf(EventKind.METADATA))
        ))
    }
}
