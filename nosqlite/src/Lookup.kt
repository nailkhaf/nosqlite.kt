package io.github.nailkhaf.nosqlite

import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import kotlin.uuid.Uuid

/**
 * Documents addressed in one collection: by an index's values, [Many], or by id, [Id].
 * ```
 * Users.id(ada).get<User>()
 * Users.byAge(36).list<User> { orderBy { "name.last".asc } }
 * Users.byName("Lovelace", "Ada").count()
 * ```
 */
public sealed class Lookup(internal val collection: Collection) {
    internal abstract val condition: Where

    /**
     * Documents of an index with the given values for its fields, made by calling the index: any number of
     * documents, in id order.
     */
    public class Many internal constructor(
        internal val index: Index,
        internal val values: List<SqlValue>,
    ) : Lookup(index.collection) {
        // A partial index's own condition is part of every lookup, so only the documents it indexes match.
        override val condition: Where = index.paths.zip(values) { path, value ->
            if (value == SqlValue.Null) Where.IsNull(path) else Where.Compare(path, Comparison.Eq, value)
        }.plus(listOfNotNull(index.where)).reduce { left, right -> Where.And(left, right) }

        override fun toString(): String = "$index = ${values.map { it.literal }}"
    }

    /** The document whose id is [value]; there is at most one. Its `id` field stores [Uuid.toString]. */
    public class Id internal constructor(collection: Collection, public val value: Uuid) : Lookup(collection) {
        override val condition: Where = idCondition(value)

        override fun toString(): String = "${collection.name} id $value"
    }
}

/**
 * Looks up [values] of this index's fields, one per field and in order. A `null` value matches a null or missing
 * field. The index walks its matches in id order, since every index entry ends with the id.
 */
public operator fun Index.invoke(vararg values: Any?): Lookup.Many {
    require(values.size == paths.size) { "$this takes ${paths.size} values, not ${values.size}" }
    return Lookup.Many(this, values.map(::sqlValue))
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
public suspend inline fun <reified T> Lookup.Many.list(noinline query: QueryBlock = {}): List<T> =
    list(serializer(), query)

/** As the reified overload, with an explicit [serializer]. */
context(_: ReadScope)
public suspend fun <T> Lookup.Many.list(serializer: KSerializer<T>, query: QueryBlock = {}): List<T> =
    collection.list(serializer, withCondition(query(query)))

/** [query] restricted to the documents found. */
internal fun Lookup.Many.withCondition(query: Query): Query =
    query.copy(condition = query.condition?.let { Where.And(condition, it) } ?: condition)

/** How many documents are found; with [condition], how many of them match it as well. */
context(_: ReadScope)
public suspend fun Lookup.Many.count(condition: Condition? = null): Long {
    val found = this.condition
    val extra = condition?.let(::where) ?: return collection.count { found }
    return collection.count { Where.And(found, extra) }
}

/**
 * Runs [action] on every document found that [query] selects, as [Collection.forEach] does: one row at a time from
 * an open cursor, in the current transaction.
 */
context(_: ReadScope)
public suspend inline fun <reified T> Lookup.Many.forEach(
    noinline query: QueryBlock = {},
    noinline action: suspend (T) -> Unit,
): Unit = forEach(serializer(), query, action)

/** As the reified overload, with an explicit [serializer]. */
context(_: ReadScope)
public suspend fun <T> Lookup.Many.forEach(
    serializer: KSerializer<T>,
    query: QueryBlock = {},
    action: suspend (T) -> Unit,
): Unit = collection.forEach(serializer, withCondition(query(query)), action)

/** Updates the documents found with [value], as `update` does; the result is how many were updated. */
context(_: WriteScope)
public suspend inline fun <reified T> Lookup.Many.update(value: T, merge: Boolean = true): Int =
    update(serializer(), value, merge)

/** As the reified overload, with an explicit [serializer]. */
context(_: WriteScope)
public suspend fun <T> Lookup.Many.update(serializer: KSerializer<T>, value: T, merge: Boolean = true): Int =
    collection.update(serializer, value, merge) { condition }

/** Deletes the documents found; the result is how many were deleted. */
context(_: WriteScope)
public suspend fun Lookup.Many.delete(): Int = collection.delete { condition }

internal fun idCondition(value: Uuid): Where =
    Where.Compare(Path(ID), Comparison.Eq, SqlValue.Text(value.toString()))

/** The document of this collection with [value] as its id. */
public fun Collection.id(value: Uuid): Lookup.Id = Lookup.Id(this, value)

/** The document as [T], `null` when there is none. */
context(_: ReadScope)
public suspend inline fun <reified T> Lookup.Id.get(): T? = get(serializer<T>())

/** As the reified overload, with an explicit [serializer]. */
context(_: ReadScope)
public suspend fun <T> Lookup.Id.get(serializer: KSerializer<T>): T? =
    collection.list(serializer, Query(condition, emptyList(), limit = 1, offset = 0)).firstOrNull()

/**
 * Updates the document with [value], as `update` does: a merge patch with [merge], the whole document without it, and
 * then [value] must carry the same id. The result is whether there was one.
 */
context(_: WriteScope)
public suspend inline fun <reified T> Lookup.Id.update(value: T, merge: Boolean = true): Boolean =
    update(serializer(), value, merge)

/** As the reified overload, with an explicit [serializer]. */
context(_: WriteScope)
public suspend fun <T> Lookup.Id.update(serializer: KSerializer<T>, value: T, merge: Boolean = true): Boolean =
    collection.update(serializer, value, merge) { condition } > 0

/** Deletes the document; the result is whether there was one. */
context(_: WriteScope)
public suspend fun Lookup.Id.delete(): Boolean = collection.delete { condition } > 0
