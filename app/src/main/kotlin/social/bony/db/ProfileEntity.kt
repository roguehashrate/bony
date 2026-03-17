package social.bony.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import social.bony.nostr.ProfileContent

@Entity(tableName = "profiles")
data class ProfileEntity(
    @PrimaryKey val pubkey: String,
    val contentJson: String,
    val createdAt: Long,
)

fun ProfileEntity.toProfileContent(): ProfileContent? = ProfileContent.parse(contentJson)
