package social.bony.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import social.bony.nostr.Event

@Entity(tableName = "events")
data class EventEntity(
    @PrimaryKey val id: String,
    val pubkey: String,
    val createdAt: Long,
    val kind: Int,
    val tagsJson: String,
    val content: String,
    val sig: String,
)

private val json = Json { ignoreUnknownKeys = true }

fun Event.toEntity() = EventEntity(
    id = id,
    pubkey = pubkey,
    createdAt = createdAt,
    kind = kind,
    tagsJson = json.encodeToString(kotlinx.serialization.builtins.ListSerializer(JsonArray.serializer()), tags),
    content = content,
    sig = sig,
)

fun EventEntity.toEvent() = Event(
    id = id,
    pubkey = pubkey,
    createdAt = createdAt,
    kind = kind,
    tags = json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(JsonArray.serializer()), tagsJson),
    content = content,
    sig = sig,
)
