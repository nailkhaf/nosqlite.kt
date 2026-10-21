package io.github.nailkhaf.nosqlite

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer

/** Inserts [value] as a new document; the result is its id. */
context(_: WriteScope)
public suspend inline fun <reified T> Collection.insert(value: T): DocumentId = insert(serializer<T>(), value)

/** Replaces the document [id] with [value]; the result is the number of documents replaced, 0 or 1. */
context(_: WriteScope)
public suspend inline fun <reified T> Collection.update(id: DocumentId, value: T): Int =
    update(idCondition(id), serializer<T>(), value)

/** Replaces every document matching [where] with [value]; the result is the number of documents replaced. */
context(_: WriteScope)
public suspend inline fun <reified T> Collection.update(value: T, where: WhereScope.() -> Condition): Int =
    update(WhereScope().where(), serializer<T>(), value)

/** Applies the JSON merge [patch] to the document [id]: `null` removes a field. The result is 0 or 1. */
context(_: WriteScope)
public suspend fun Collection.patch(id: DocumentId, patch: JsonObject): Int = patch(idCondition(id), patch)

/** Applies the JSON merge [patch] to every document matching [where]; the result is the number of documents patched. */
context(_: WriteScope)
public suspend inline fun Collection.patch(patch: JsonObject, where: WhereScope.() -> Condition): Int =
    patch(WhereScope().where(), patch)

/** Deletes the document [id]; the result is the number of documents deleted, 0 or 1. */
context(_: WriteScope)
public suspend fun Collection.delete(id: DocumentId): Int = delete(idCondition(id))

/** Deletes every document matching [where]; the result is the number of documents deleted. */
context(_: WriteScope)
public suspend inline fun Collection.delete(where: WhereScope.() -> Condition): Int = delete(WhereScope().where())

/** Replaces the document whose key is [key] with [value]; the result is 0 or 1. */
context(_: WriteScope)
public suspend inline fun <K, reified T> UniqueIndex<K>.update(key: K, value: T): Int =
    collection.update(lookup(key), serializer<T>(), value)

/** Applies the JSON merge [patch] to the document whose key is [key]; the result is 0 or 1. */
context(_: WriteScope)
public suspend fun <K> UniqueIndex<K>.patch(key: K, patch: JsonObject): Int = collection.patch(lookup(key), patch)

/** Deletes the document whose key is [key]; the result is 0 or 1. */
context(_: WriteScope)
public suspend fun <K> UniqueIndex<K>.delete(key: K): Int = collection.delete(lookup(key))

/** The document [id] decoded as [T], `null` when there is none. */
context(_: ReadScope)
public suspend inline operator fun <reified T> Collection.get(id: DocumentId): T? = get(id, serializer<T>())

@PublishedApi
context(_: ReadScope)
internal suspend fun <T> Collection.get(id: DocumentId, serializer: KSerializer<T>): T? = context(connection()) {
    Queries.selectWhere(this, idCondition(id), limit = 1).execute().firstOrNull()?.let { decode(it, serializer) }
}

@PublishedApi
context(_: WriteScope)
internal suspend fun <T> Collection.insert(serializer: SerializationStrategy<T>, value: T): DocumentId =
    context(connection()) {
        val json = encode(serializer, value)
        mapConstraintErrors { Queries.insert(this).execute(json) }.also { changed(1) }
    }

@PublishedApi
context(_: WriteScope)
internal suspend fun <T> Collection.update(condition: Condition, serializer: SerializationStrategy<T>, value: T): Int =
    context(connection()) {
        val json = encode(serializer, value)
        mapConstraintErrors { Queries.update(this, condition).execute(json) }.also { changed(it) }
    }

@PublishedApi
context(_: WriteScope)
internal suspend fun Collection.patch(condition: Condition, patch: JsonObject): Int = context(connection()) {
    val json = Database.Json.encodeToString(JsonObject.serializer(), patch)
    mapConstraintErrors { Queries.patch(this, condition).execute(json) }.also { changed(it) }
}

@PublishedApi
context(_: WriteScope)
internal suspend fun Collection.delete(condition: Condition): Int = context(connection()) {
    Queries.delete(this, condition).execute().also { changed(it) }
}

/** [value] as a document's JSON text; anything but a JSON object is rejected. */
private fun <T> Collection.encode(serializer: SerializationStrategy<T>, value: T): String {
    val json = Database.Json.encodeToJsonElement(serializer, value)
    require(json is JsonObject) { "A document of '$name' must be a JSON object, not $json" }
    return json.toString()
}
