package io.github.nailkhaf.nosqlite

import kotlinx.serialization.json.JsonObject

/** Inserts [value] as a new document; it must encode to a JSON object. */
context(_: WriteScope)
public suspend fun <T> Collection<T>.insert(value: T) {
    val json = Database.Json.encodeToJsonElement(serializer, value)
    require(json is JsonObject) { "A document of '$name' must be a JSON object, not $json" }
    withConnection { Queries.insert(this).execute(json.toString()) }
}

/** Deletes the document [id]; the result is `false` when there was none. */
context(_: WriteScope)
internal suspend fun Collection<*>.delete(id: DocumentId): Boolean = withConnection {
    Queries.deleteById(this).execute(id)
}

/** The document [id], `null` when there is none. */
context(_: ReadScope)
internal suspend operator fun <T> Collection<T>.get(id: DocumentId): T? = withConnection {
    Queries.selectById(this).execute(id)?.let { decode(it, id) }
}
