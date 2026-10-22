package io.github.nailkhaf.nosqlite

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer

/** Inserts [value] as a new document; the result is its id. */
context(_: WriteScope)
public suspend fun Collection.insert(value: JsonObject): DocumentId = withConnection {
    Queries.insert(this).execute(Database.Json.encodeToString(JsonObject.serializer(), value))
}

/** Deletes the document [id]; the result is `false` when there was none. */
context(_: WriteScope)
public suspend fun Collection.delete(id: DocumentId): Boolean = withConnection {
    Queries.deleteById(this).execute(id)
}

/** The document [id] decoded as [T], `null` when there is none. */
context(_: ReadScope)
public suspend inline operator fun <reified T> Collection.get(id: DocumentId): T? = get(id, serializer<T>())

@PublishedApi
context(_: ReadScope)
internal suspend fun <T> Collection.get(id: DocumentId, serializer: KSerializer<T>): T? = withConnection {
    Queries.selectById(this).execute(id)?.let { decode(it, serializer, id) }
}
