package io.github.nailkhaf.nosqlite

import kotlinx.serialization.json.JsonObject

/** Inserts [value] as a new document; it must encode to a JSON object. */
context(_: WriteScope)
public suspend fun <T> Collection<T>.insert(value: T) {
    val json = encode(value)
    withConnection { Queries.insert(this@insert).execute(json) }
}

/**
 * Up to [limit] documents matching [condition], or any documents without one, sorted by [orderBy] and then in the
 * order they were inserted, skipping the first [offset].
 */
context(_: ReadScope)
public suspend fun <T> Collection<T>.list(
    limit: Int = Int.MAX_VALUE,
    offset: Int = 0,
    orderBy: List<Order> = emptyList(),
    condition: (WhereScope.() -> Condition)? = null,
): List<T> {
    require(limit > 0) { "Limit must be positive: $limit" }
    require(offset >= 0) { "Offset must not be negative: $offset" }
    return withConnection {
        Queries.select(this@list, condition?.let(::where), orderBy, limit, offset).execute().map { decode(it) }
    }
}

/** How many documents match [condition], or all of them without one. */
context(_: ReadScope)
public suspend fun Collection<*>.count(condition: (WhereScope.() -> Condition)? = null): Long =
    withConnection { Queries.count(this@count, condition?.let(::where)).execute() }

/**
 * Replaces every document matching [condition] with [value], so match on a unique field to replace one.
 * The result is how many documents were replaced.
 */
context(_: WriteScope)
public suspend fun <T> Collection<T>.update(value: T, condition: WhereScope.() -> Condition): Int {
    val json = encode(value)
    return withConnection { Queries.update(this@update, json, where(condition)).execute().size }
}

/** Deletes every document matching [condition]; the result is how many were deleted. */
context(_: WriteScope)
public suspend fun Collection<*>.delete(condition: WhereScope.() -> Condition): Int =
    withConnection { Queries.delete(this@delete, where(condition)).execute().size }

/**
 * Applies the JSON merge [patch] to every document matching [condition]: its fields replace theirs, and a `null`
 * removes one. The result is how many documents were patched.
 */
context(_: WriteScope)
public suspend fun Collection<JsonObject>.patch(patch: JsonObject, condition: WhereScope.() -> Condition): Int =
    withConnection { Queries.patch(this@patch, patch.toString(), where(condition)).execute().size }
