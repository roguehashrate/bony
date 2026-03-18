package social.bony.ui.compose

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import social.bony.account.signer.NostrSignerFactory
import social.bony.db.EventRepository
import social.bony.nostr.EventKind
import social.bony.nostr.UnsignedEvent
import social.bony.nostr.relay.RelayPool
import javax.inject.Inject

data class ComposeUiState(
    val isPublishing: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class ComposeViewModel @Inject constructor(
    private val pool: RelayPool,
    private val signerFactory: NostrSignerFactory,
    private val eventRepository: EventRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ComposeUiState())
    val uiState: StateFlow<ComposeUiState> = _uiState.asStateFlow()

    fun publish(content: String, onSuccess: () -> Unit) {
        val trimmed = content.trim()
        if (trimmed.isBlank()) return

        viewModelScope.launch {
            _uiState.update { it.copy(isPublishing = true, error = null) }

            val signer = signerFactory.forActiveAccount()
            if (signer == null) {
                _uiState.update { it.copy(isPublishing = false, error = "No active account") }
                return@launch
            }

            val unsigned = UnsignedEvent(
                pubkey = signer.pubkey,
                kind = EventKind.TEXT_NOTE,
                content = trimmed,
            )

            signer.signEvent(unsigned)
                .onSuccess { event ->
                    pool.publish(event)
                    eventRepository.save(event, signer.pubkey)
                    _uiState.update { it.copy(isPublishing = false) }
                    onSuccess()
                }
                .onFailure { e ->
                    _uiState.update { it.copy(isPublishing = false, error = e.message) }
                }
        }
    }

    fun clearError() = _uiState.update { it.copy(error = null) }
}
