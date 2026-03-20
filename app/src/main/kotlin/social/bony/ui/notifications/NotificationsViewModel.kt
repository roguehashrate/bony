package social.bony.ui.notifications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import social.bony.account.AccountRepository
import social.bony.nostr.Event
import social.bony.nostr.EventKind
import social.bony.nostr.Filter
import social.bony.nostr.ProfileContent
import social.bony.nostr.relay.RelayMessage
import social.bony.nostr.relay.RelayPool
import social.bony.profile.ProfileRepository
import social.bony.reactions.ReactionsRepository
import social.bony.settings.AppSettings
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

@HiltViewModel
class NotificationsViewModel @Inject constructor(
    private val pool: RelayPool,
    private val accountRepository: AccountRepository,
    private val profileRepository: ProfileRepository,
    private val reactionsRepository: ReactionsRepository,
    private val appSettings: AppSettings,
) : ViewModel() {

    private val _events = MutableStateFlow<List<Event>>(emptyList())
    val events: StateFlow<List<Event>> = _events.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    val profiles: StateFlow<Map<String, ProfileContent>> = profileRepository.profiles
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val reactions: StateFlow<Map<String, Set<String>>> = reactionsRepository.reactions
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val activeAccount = accountRepository.activeAccount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val activeSubIds: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private var notifSubId: String? = null
    private var collectJob: Job? = null

    // Buffer events until EOSE, then flush once — avoids N state updates and N recompositions
    private val pendingBuffer = mutableListOf<Event>()
    @Volatile private var settled = false

    init {
        viewModelScope.launch {
            accountRepository.activeAccount.collect { account ->
                if (account != null) load(account.pubkey)
            }
        }
    }

    private fun load(pubkey: String) {
        collectJob?.cancel()
        activeSubIds.clear()
        notifSubId?.let { pool.unsubscribe(it) }
        pendingBuffer.clear()
        settled = false
        _isLoading.update { true }
        _events.update { emptyList() }

        val oneWeekAgo = System.currentTimeMillis() / 1000 - (7 * 24 * 60 * 60)
        val subId = pool.subscribe(listOf(Filter.notifications(pubkey, limit = 50).copy(since = oneWeekAgo)))
        notifSubId = subId
        activeSubIds.add(subId)

        collectJob = viewModelScope.launch(Dispatchers.Default) {
            pool.messages.collect { poolMessage ->
                when (val msg = poolMessage.message) {
                    is RelayMessage.EventMessage -> {
                        if (msg.subscriptionId !in activeSubIds) return@collect
                        if (!msg.event.verify()) return@collect
                        when (msg.event.kind) {
                            EventKind.METADATA -> profileRepository.processEvent(msg.event)
                            else -> {
                                if (msg.event.pubkey == pubkey) return@collect
                                if (!settled) {
                                    synchronized(pendingBuffer) { pendingBuffer.add(msg.event) }
                                } else {
                                    addLive(msg.event)
                                }
                            }
                        }
                    }
                    is RelayMessage.EndOfStoredEvents -> {
                        when {
                            msg.subscriptionId == notifSubId && !settled -> {
                                settled = true
                                flushBuffer()
                            }
                            msg.subscriptionId != notifSubId && msg.subscriptionId in activeSubIds -> {
                                // One-shot metadata sub finished
                                activeSubIds.remove(msg.subscriptionId)
                                pool.unsubscribe(msg.subscriptionId)
                            }
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun flushBuffer() {
        val events = synchronized(pendingBuffer) {
            pendingBuffer.distinctBy { it.id }
                .sortedByDescending { it.createdAt }
                .also { pendingBuffer.clear() }
        }
        _events.update { events }
        _isLoading.update { false }
        if (events.isEmpty()) return
        fetchAuthorProfilesBatch(events.map { it.pubkey }.distinct())
    }

    private fun addLive(event: Event) {
        _events.update { current ->
            (listOf(event) + current)
                .distinctBy { it.id }
                .sortedByDescending { it.createdAt }
        }
        fetchAuthorProfilesBatch(listOf(event.pubkey))
    }

    private fun fetchAuthorProfilesBatch(pubkeys: List<String>) {
        val unknown = pubkeys.filter { profileRepository.profiles.value[it] == null }
        if (unknown.isEmpty()) return
        val subId = pool.subscribe(listOf(
            Filter(authors = unknown, kinds = listOf(EventKind.METADATA), limit = unknown.size)
        ))
        activeSubIds.add(subId)
    }

    fun markAllRead() {
        viewModelScope.launch {
            appSettings.setLastViewedNotificationsAt(System.currentTimeMillis() / 1000)
        }
    }

    override fun onCleared() {
        collectJob?.cancel()
        notifSubId?.let { pool.unsubscribe(it) }
        activeSubIds.forEach { pool.unsubscribe(it) }
    }
}
