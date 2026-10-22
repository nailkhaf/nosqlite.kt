package io.github.nailkhaf.nosqlite

import kotlinx.serialization.json.JsonObject

/** Inserts [value] as a new document; it must encode to a JSON object. */
context(_: WriteScope)
public suspend fun <T> Collection<T>.insert(value: T) {
    val json = encode(value)
    withConnection { Queries.insert(this@insert).execute(json) }
}

/**
 * Up to [limit] documents matching [condition], or any documents without one, in the order they were inserted,
 * skipping the first [offset].
 */
context(_: ReadScope)
public suspend fun <T> Collection<T>.list(
    limit: Int = Int.MAX_VALUE,
    offset: Int = 0,
    condition: (WhereScope.() -> Condition)? = null,
): List<T> {
    require(limit > 0) { "Limit must be positive: $limit" }
    require(offset >= 0) { "Offset must not be negative: $offset" }
    return withConnection {
        Queries.select(this@list, condition?.let(::where), limit, offset).execute().map { decode(it) }
    }
}

/** Replaces every document matching [condition] with [value], so match on a unique field to replace one. */
context(_: WriteScope)
public suspend fun <T> Collection<T>.update(value: T, condition: WhereScope.() -> Condition): List<T> {
    val json = encode(value)
    return withConnection { Queries.update(this@update, json, where(condition)).execute().map { decode(it) } }
}

/** Deletes every document matching [condition]; the result is the documents deleted. */
context(_: WriteScope)
public suspend fun <T> Collection<T>.delete(condition: WhereScope.() -> Condition): List<T> =
    withConnection { Queries.delete(this@delete, where(condition)).execute().map { decode(it) } }

/**
 * Applies the JSON merge [patch] to every document matching [condition]: its fields replace theirs, and a `null`
 * removes one. The result is the documents as patched.
 */
context(_: WriteScope)
public suspend fun Collection<JsonObject>.patch(
    patch: JsonObject,
    condition: WhereScope.() -> Condition,
): List<JsonObject> = withConnection {
    Queries.patch(this@patch, patch.toString(), where(condition)).execute().map { decode(it) }
}
