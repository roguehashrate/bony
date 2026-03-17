package social.bony.profile

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import social.bony.db.ProfileDao
import social.bony.db.ProfileEntity
import social.bony.db.toProfileContent
import social.bony.nostr.Event
import social.bony.nostr.EventKind
import social.bony.nostr.ProfileContent
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProfileRepository @Inject constructor(private val dao: ProfileDao) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _profiles = MutableStateFlow<Map<String, ProfileContent>>(emptyMap())
    val profiles: StateFlow<Map<String, ProfileContent>> = _profiles.asStateFlow()

    // Tracks the createdAt of the newest event processed per pubkey (in-memory guard)
    private val latestTimestamp = mutableMapOf<String, Long>()

    init {
        scope.launch {
            val cached = dao.getAll()
            if (cached.isEmpty()) return@launch
            val map = cached.mapNotNull { entity ->
                val content = entity.toProfileContent() ?: return@mapNotNull null
                latestTimestamp[entity.pubkey] = entity.createdAt
                entity.pubkey to content
            }.toMap()
            _profiles.update { it + map }
        }
    }

    fun processEvent(event: Event) {
        if (event.kind != EventKind.METADATA) return
        val content = ProfileContent.parse(event.content) ?: return

        synchronized(latestTimestamp) {
            val existing = latestTimestamp[event.pubkey] ?: -1L
            if (event.createdAt <= existing) return
            latestTimestamp[event.pubkey] = event.createdAt
        }

        _profiles.update { it + (event.pubkey to content) }

        scope.launch {
            dao.upsert(ProfileEntity(event.pubkey, event.content, event.createdAt))
        }
    }

    fun getProfile(pubkey: String): ProfileContent? = _profiles.value[pubkey]
}
