package io.github.nailkhaf.nosqlite

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer

/** Inserts [value] as a new document; the result is its id. */
context(_: WriteScope)
public suspend fun Collection.insert(value: JsonObject): DocumentId =
    insertJson(Database.Json.encodeToString(JsonObject.serializer(), value))

context(_: WriteScope)
internal suspend fun Collection.insertJson(json: String): DocumentId = context(connection()) {
    mapConstraintErrors { Queries.insert(this).execute(json) }.also { changed(1) }
}

/** Deletes the document [id]; the result is the number of documents deleted, 0 or 1. */
context(_: WriteScope)
public suspend fun Collection.delete(id: DocumentId): Int = context(connection()) {
    Queries.deleteById(this).execute(id).also { changed(it) }
}

/** The document [id] decoded as [T], `null` when there is none. */
context(_: ReadScope)
public suspend inline operator fun <reified T> Collection.get(id: DocumentId): T? = get(id, serializer<T>())

@PublishedApi
context(_: ReadScope)
internal suspend fun <T> Collection.get(id: DocumentId, serializer: KSerializer<T>): T? = context(connection()) {
    Queries.selectById(this).execute(id)?.let { decode(it, serializer, id) }
}
