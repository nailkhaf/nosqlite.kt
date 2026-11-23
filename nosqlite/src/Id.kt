package io.github.nailkhaf.nosqlite

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.uuid.Uuid

/** The JSON key of the id every document carries, stored as [Uuid.toString]. */
internal const val ID_FIELD: String = "id"

/** [this] as a UUID when it is the lowercase canonical text [Uuid.toString] writes, `null` otherwise. */
internal fun String.canonicalUuidOrNull(): Uuid? {
    val uuid = try {
        Uuid.parse(this)
    } catch (_: IllegalArgumentException) {
        return null
    }
    return uuid.takeIf { it.toString() == this }
}

/** The id of [document]: its [ID_FIELD], which must be a canonical UUID string. */
internal fun documentId(collection: String, document: JsonObject): Uuid {
    val id = document[ID_FIELD]
    val text = (id as? JsonPrimitive)?.takeIf { it.isString }?.content
    return text?.canonicalUuidOrNull()
        ?: throw NoSqliteException.InvalidId(collection, IllegalArgumentException("Not a canonical UUID string: $id"))
}
