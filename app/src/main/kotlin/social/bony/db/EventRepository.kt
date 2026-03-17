package social.bony.db

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import social.bony.nostr.Event
import social.bony.nostr.EventKind
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EventRepository @Inject constructor(private val dao: EventDao) {

    val feedEvents: Flow<List<Event>> = dao.observeByKinds(
        listOf(EventKind.TEXT_NOTE, EventKind.REPOST)
    ).map { it.map(EventEntity::toEvent) }

    suspend fun getRecentFeedEvents(limit: Int = 300): List<Event> =
        dao.getRecentByKinds(listOf(EventKind.TEXT_NOTE, EventKind.REPOST), limit)
            .map(EventEntity::toEvent)

    suspend fun save(event: Event) = dao.upsert(event.toEntity())

    suspend fun getById(id: String): Event? = dao.getById(id)?.toEvent()

    suspend fun getByIds(ids: List<String>): List<Event> =
        dao.getByIds(ids).map(EventEntity::toEvent)
}
