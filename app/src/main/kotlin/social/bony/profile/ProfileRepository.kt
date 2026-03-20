package social.bony.profile

import android.util.LruCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    // LruCache is the authoritative in-memory store; capped at 1000 entries.
    // The StateFlow emits snapshots of it so Compose can observe changes.
    private val cache = LruCache<String, ProfileContent>(1000)

    // Tracks the createdAt of the newest event processed per pubkey (in-memory guard)
    private val latestTimestamp = mutableMapOf<String, Long>()

    private val _profiles = MutableStateFlow<Map<String, ProfileContent>>(emptyMap())
    val profiles: StateFlow<Map<String, ProfileContent>> = _profiles.asStateFlow()

    init {
        scope.launch {
            val cached = dao.getAll()
            if (cached.isEmpty()) return@launch
            cached.forEach { entity ->
                val content = entity.toProfileContent() ?: return@forEach
                latestTimestamp[entity.pubkey] = entity.createdAt
                cache.put(entity.pubkey, content)
            }
            _profiles.value = cache.snapshot()
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

        cache.put(event.pubkey, content)
        _profiles.value = cache.snapshot()

        scope.launch {
            dao.upsert(ProfileEntity(event.pubkey, event.content, event.createdAt))
        }
    }

    fun getProfile(pubkey: String): ProfileContent? = cache.get(pubkey)
}
