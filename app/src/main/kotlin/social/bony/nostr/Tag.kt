package social.bony.nostr

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * A Nostr tag is a list of strings: ["e", "<event-id>", "<relay-url>", "<marker>"]
 * The first element is the tag name; remaining elements are tag-specific values.
 */
@JvmInline
value class Tag(val values: List<String>) {

    val name: String get() = values.firstOrNull() ?: ""

    fun value(index: Int = 1): String? = values.getOrNull(index)

    companion object {
        fun fromJsonArray(array: JsonArray): Tag =
            Tag(array.map { it.jsonPrimitive.content })

        // Convenience constructors for common tag types
        fun event(eventId: String, relayHint: String = "", marker: String = ""): Tag =
            Tag(listOfNotNull("e", eventId, relayHint.ifEmpty { null }, marker.ifEmpty { null }))

        fun pubkey(pubkey: String, relayHint: String = "", petname: String = ""): Tag =
            Tag(listOfNotNull("p", pubkey, relayHint.ifEmpty { null }, petname.ifEmpty { null }))

        fun relay(url: String, marker: String = ""): Tag =
            Tag(listOfNotNull("r", url, marker.ifEmpty { null }))
    }
}

val List<Tag>.eventIds: List<String>
    get() = filter { it.name == "e" }.mapNotNull { it.value() }

val List<Tag>.pubkeys: List<String>
    get() = filter { it.name == "p" }.mapNotNull { it.value() }
