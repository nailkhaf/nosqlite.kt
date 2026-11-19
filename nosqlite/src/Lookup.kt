package io.github.nailkhaf.nosqlite

import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer

/**
 * Documents of an index with the given values for its fields, made by calling the index: any number of documents,
 * with values for leading fields alone matching their prefix. One document is addressed by its id, [Id], rather than
 * by an index.
 * ```
 * Users.id(ada).get<User>()
 * Users.byAge(36).list<User> { orderBy { "name.last".asc } }
 * Users.byName("Lovelace").count()
 * ```
 */
public class Lookup internal constructor(internal val index: Index, internal val values: List<SqlValue>) {
    // A partial index's own condition is part of every lookup, so only the documents it indexes match.
    internal val condition: Where = index.paths.zip(values) { path, value ->
        if (value == SqlValue.Null) Where.IsNull(path) else Where.Compare(path, Comparison.Eq, value)
    }.plus(listOfNotNull(index.where)).reduce { left, right -> Where.And(left, right) }

    override fun toString(): String = "$index = ${values.map { it.literal }}"
}

/**
 * Looks up [values] of this index's fields, in order; leading fields alone match their prefix. A `null` value matches
 * a null or missing field.
 */
public operator fun Index.invoke(vararg values: Any?): Lookup {
    require(values.size in 1..paths.size) { "$this takes 1 to ${paths.size} values, not ${values.size}" }
    return Lookup(this, values.map(::sqlValue))
}

internal fun sqlValue(value: Any?): SqlValue = when (value) {
    null -> SqlValue.Null
    is String -> SqlValue.Text(value)
    is Number -> value.sqlValue
    is Boolean -> SqlValue.Integer(if (value) 1 else 0)
    else -> throw IllegalArgumentException("Unsupported value: ${value::class.simpleName}")
}

internal const val FLOAT_IS_NOT_AN_SQL_REAL =
    "Unsupported number: Float, it is not exact as a SQL real; pass a Double (0.1f does not match 0.1)"

/** Lookups and the typed where API convert a [Number] here, including the [Float] rejection. */
internal val Number.sqlValue: SqlValue
    get() = when (this) {
        is Int, is Long, is Short, is Byte -> SqlValue.Integer(toLong())
        // 0.1f.toDouble() is 0.10000000149011612, so it never equals the REAL 0.1 stored from JSON.
        is Float -> {
            require(isFinite()) { "Unsupported number: $this, SQLite has no NaN or infinity" }
            throw IllegalArgumentException(FLOAT_IS_NOT_AN_SQL_REAL)
        }
        is Double -> sqlReal
        else -> throw IllegalArgumentException("Unsupported number: ${this::class.simpleName}")
    }

/** The documents found that [query] selects, as [T]; its condition, if any, must hold as well. */
context(_: ReadScope)
public suspend inline fun <reified T> Lookup.list(noinline query: QueryBlock = {}): List<T> =
    list(serializer(), query)

/** As the reified overload, with an explicit [serializer]. */
context(_: ReadScope)
public suspend fun <T> Lookup.list(serializer: KSerializer<T>, query: QueryBlock = {}): List<T> =
    index.collection.list(serializer, withCondition(query(query)))

/** [query] restricted to the documents found. */
internal fun Lookup.withCondition(query: Query): Query =
    query.copy(condition = query.condition?.let { Where.And(condition, it) } ?: condition)

/** How many documents are found; with [condition], how many of them match it as well. */
context(_: ReadScope)
public suspend fun Lookup.count(condition: Condition? = null): Long {
    val found = this.condition
    val extra = condition?.let(::where) ?: return index.collection.count { found }
    return index.collection.count { Where.And(found, extra) }
}

/**
 * Runs [action] on every document found that [query] selects, as [Collection.forEach] does: one row at a time from
 * an open cursor, in the current transaction.
 */
context(_: ReadScope)
public suspend inline fun <reified T> Lookup.forEach(
    noinline query: QueryBlock = {},
    noinline action: suspend (T) -> Unit,
): Unit = forEach(serializer(), query, action)

/** As the reified overload, with an explicit [serializer]. */
context(_: ReadScope)
public suspend fun <T> Lookup.forEach(
    serializer: KSerializer<T>,
    query: QueryBlock = {},
    action: suspend (T) -> Unit,
): Unit = index.collection.forEach(serializer, withCondition(query(query)), action)

/** Updates the documents found with [value], as `update` does; the result is how many were updated. */
context(_: WriteScope)
public suspend inline fun <reified T> Lookup.update(value: T, merge: Boolean = true): Int =
    update(serializer(), value, merge)

/** As the reified overload, with an explicit [serializer]. */
context(_: WriteScope)
public suspend fun <T> Lookup.update(serializer: KSerializer<T>, value: T, merge: Boolean = true): Int =
    index.collection.update(serializer, value, merge) { condition }

/** Deletes the documents found; the result is how many were deleted. */
context(_: WriteScope)
public suspend fun Lookup.delete(): Int = index.collection.delete { condition }
