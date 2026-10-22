package io.github.nailkhaf.nosqlite

import kotlinx.serialization.json.JsonObject

/** Inserts [value] as a new document; it must encode to a JSON object. */
context(_: WriteScope)
public suspend fun <T> Collection<T>.insert(value: T) {
    val json = encode(value)
    withConnection { Queries.insert(this@insert).execute(json) }
}

/** The documents [query] selects; without one, every document in the order they were inserted. */
context(_: ReadScope)
public suspend fun <T> Collection<T>.list(query: QueryScope.() -> Unit = {}): List<T> = list(query(query))

context(_: ReadScope)
internal suspend fun <T> Collection<T>.list(query: Query): List<T> =
    withConnection { Queries.select(this@list, query).execute().map { decode(it) } }

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
