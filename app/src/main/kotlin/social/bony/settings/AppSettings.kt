package social.bony.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppSettings @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val scope: CoroutineScope,
) {
    companion object {
        private val TOR_ENABLED = booleanPreferencesKey("tor_enabled")
        // False until the user (or auto-detect) explicitly sets the preference.
        // Lets first-run logic distinguish "never set" from "user chose off".
        private val TOR_EXPLICITLY_SET = booleanPreferencesKey("tor_explicitly_set")
        private val LAST_VIEWED_NOTIFICATIONS_AT = longPreferencesKey("last_viewed_notifications_at")
    }

    private val _torEnabled = MutableStateFlow(false)
    val torEnabled = _torEnabled.asStateFlow()

    private val _torExplicitlySet = MutableStateFlow(false)
    val torExplicitlySet = _torExplicitlySet.asStateFlow()

    private val _lastViewedNotificationsAt = MutableStateFlow(0L)
    val lastViewedNotificationsAt = _lastViewedNotificationsAt.asStateFlow()

    init {
        scope.launch {
            dataStore.data.collect { prefs ->
                _torExplicitlySet.value = prefs[TOR_EXPLICITLY_SET] == true
                _torEnabled.value = prefs[TOR_ENABLED] ?: false
                _lastViewedNotificationsAt.value = prefs[LAST_VIEWED_NOTIFICATIONS_AT] ?: 0L
            }
        }
    }

    suspend fun setTorEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[TOR_ENABLED] = enabled
            prefs[TOR_EXPLICITLY_SET] = true
        }
    }

    suspend fun setLastViewedNotificationsAt(epochSeconds: Long) {
        dataStore.edit { it[LAST_VIEWED_NOTIFICATIONS_AT] = epochSeconds }
    }
}
