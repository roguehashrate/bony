package social.bony.account.signer

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import social.bony.account.AccountRepository
import social.bony.account.SignerType
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NostrSignerFactory @Inject constructor(
    private val accountRepository: AccountRepository,
    private val amberBridge: AmberSignerBridge,
    @ApplicationContext private val context: Context,
) {
    /**
     * Returns a [NostrSigner] for the currently active account,
     * or null if no account is active or the signer can't be constructed.
     */
    suspend fun forActiveAccount(): NostrSigner? {
        val account = accountRepository.activeAccount.first() ?: return null
        return when (account.signerType) {
            SignerType.AMBER ->
                AmberSigner(account.pubkey, amberBridge, context.packageName)

            SignerType.LOCAL_KEY -> {
                val encrypted = accountRepository.getEncryptedPrivkey(account.pubkey).first()
                    ?: return null
                LocalKeySigner(account.pubkey, encrypted)
            }

            SignerType.NSEC_BUNKER -> null // UI not yet implemented
        }
    }
}
