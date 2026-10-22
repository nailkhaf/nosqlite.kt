package io.github.nailkhaf.nosqlite

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer

/** Inserts [value] as a new document; it must encode to a JSON object. */
context(_: WriteScope)
public suspend inline fun <reified T> Collection.insert(value: T): Unit = insert(serializer<T>(), value)

@PublishedApi
context(_: WriteScope)
internal suspend fun <T> Collection.insert(serializer: SerializationStrategy<T>, value: T) {
    val json = Database.Json.encodeToJsonElement(serializer, value)
    require(json is JsonObject) { "A document of '$name' must be a JSON object, not $json" }
    withConnection { Queries.insert(this).execute(json.toString()) }
}

/** Deletes the document [id]; the result is `false` when there was none. */
context(_: WriteScope)
internal suspend fun Collection.delete(id: DocumentId): Boolean = withConnection {
    Queries.deleteById(this).execute(id)
}

/** The document [id] decoded as [T], `null` when there is none. */
context(_: ReadScope)
internal suspend inline operator fun <reified T> Collection.get(id: DocumentId): T? = get(id, serializer<T>())

context(_: ReadScope)
internal suspend fun <T> Collection.get(id: DocumentId, serializer: KSerializer<T>): T? = withConnection {
    Queries.selectById(this).execute(id)?.let { decode(it, serializer, id) }
}
