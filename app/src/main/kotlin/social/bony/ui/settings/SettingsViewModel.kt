package social.bony.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import social.bony.account.Account
import social.bony.account.AccountRepository
import social.bony.logging.LogRepository
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val accountRepository: AccountRepository,
    val logRepository: LogRepository,
) : ViewModel() {

    val accounts: StateFlow<List<Account>> = accountRepository.accounts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val activeAccount: StateFlow<Account?> = accountRepository.activeAccount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun removeAccount(pubkey: String) {
        viewModelScope.launch { accountRepository.removeAccount(pubkey) }
    }
}
