package io.github.nailkhaf.nosqlite

import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import kotlin.uuid.Uuid

/**
 * Documents of an index with the given values for its fields, made by calling the index. A [Many] one is any number
 * of documents, with values for leading fields alone matching their prefix. One document is addressed by its id,
 * [Id], rather than by an index.
 * ```
 * Users.id(ada).get<User>()
 * Users.byAge(36).list { orderBy { "name.last".asc } }
 * Users.byName("Lovelace").count()
 * ```
 */
public sealed class Lookup(internal val index: Index, internal val values: List<SqlValue>) {
    // A partial index's own condition is part of every lookup, so only the documents it indexes match.
    internal val condition: Where = index.paths.zip(values) { path, value ->
        if (value == SqlValue.Null) Where.IsNull(path) else Where.Compare(path, Comparison.Eq, value)
    }.plus(listOfNotNull(index.where)).reduce { left, right -> Where.And(left, right) }

    override fun toString(): String = "$index = ${values.map { it.literal }}"

    public class Many internal constructor(index: Index, values: List<SqlValue>) : Lookup(index, values)
}

/**
 * Looks up [values] of this index's fields, in order; leading fields alone match their prefix. A `null` value matches
 * a null or missing field.
 */
public operator fun Index.invoke(vararg values: Any?): Lookup.Many {
    require(values.size in 1..paths.size) { "$this takes 1 to ${paths.size} values, not ${values.size}" }
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
public suspend inline fun <reified T> Lookup.Many.list(noinline query: QueryScope.() -> Unit = {}): List<T> =
    list(serializer(), query)

context(_: ReadScope)
public suspend fun <T> Lookup.Many.list(serializer: KSerializer<T>, query: QueryScope.() -> Unit = {}): List<T> {
    val query = query(query)
    val condition = query.condition?.let { Where.And(condition, it) } ?: condition
    return index.collection.list(serializer, Query(condition, query.orderBy, query.limit, query.offset))
}

/** How many documents are found. */
context(_: ReadScope)
public suspend fun Lookup.Many.count(): Long = index.collection.count { condition }

/** Applies [value] as a merge patch to the documents found, as `update` does; the result is how many were updated. */
context(_: WriteScope)
public suspend inline fun <reified T> Lookup.Many.update(value: T): Int = update(serializer(), value)

context(_: WriteScope)
public suspend fun <T> Lookup.Many.update(serializer: KSerializer<T>, value: T): Int =
    index.collection.update(serializer, value) { condition }

/** Replaces the documents found with [value]; the result is how many were replaced. */
context(_: WriteScope)
public suspend inline fun <reified T> Lookup.Many.replace(value: T): Int = replace(serializer(), value)

context(_: WriteScope)
public suspend fun <T> Lookup.Many.replace(serializer: KSerializer<T>, value: T): Int =
    index.collection.replace(serializer, value) { condition }

/** Deletes the documents found; the result is how many were deleted. */
context(_: WriteScope)
public suspend fun Lookup.Many.delete(): Int = index.collection.delete { condition }

/**
 * The document of a collection whose id is [value]. There is at most one.
 * The document's `id` field stores [Uuid.toString].
 * ```
 * Users.id(ada).get<User>()
 * Users.id(ada).update(ada.copy(age = 37))
 * Users.upsert(ada)
 * ```
 */
public class Id internal constructor(internal val collection: Collection, public val value: Uuid) {
    internal val condition: Where = Where.Compare(Path(ID), Comparison.Eq, SqlValue.Text(value.toString()))

    override fun toString(): String = "${collection.name} id $value"
}

/** The document of this collection with [value] as its id. */
public fun Collection.id(value: Uuid): Id = Id(this, value)

/** The document as [T], `null` when there is none. */
context(_: ReadScope)
public suspend inline fun <reified T> Id.get(): T? = get(serializer<T>())

context(_: ReadScope)
public suspend fun <T> Id.get(serializer: KSerializer<T>): T? =
    collection.list(serializer, Query(condition, emptyList(), limit = 1, offset = 0)).firstOrNull()

/** Applies [value] as a merge patch to the document, as `update` does; the result is whether there was one. */
context(_: WriteScope)
public suspend inline fun <reified T> Id.update(value: T): Boolean = update(serializer(), value)

context(_: WriteScope)
public suspend fun <T> Id.update(serializer: KSerializer<T>, value: T): Boolean =
    collection.update(serializer, value) { condition } > 0

/** Replaces the document with [value]; the result is whether there was one. [value] must carry the same id. */
context(_: WriteScope)
public suspend inline fun <reified T> Id.replace(value: T): Boolean = replace(serializer(), value)

context(_: WriteScope)
public suspend fun <T> Id.replace(serializer: KSerializer<T>, value: T): Boolean =
    collection.replace(serializer, value) { condition } > 0

/** Deletes the document; the result is whether there was one. */
context(_: WriteScope)
public suspend fun Id.delete(): Boolean = collection.delete { condition } > 0

/**
 * Inserts [value], or applies it as a JSON merge patch to the document with its id, as `update` does.
 * [value] must carry an id. An id already stored is merged; a different id is a different document.
 */
context(_: WriteScope)
public suspend inline fun <reified T> Collection.upsert(value: T): Unit = upsert(serializer(), value)

context(_: WriteScope)
public suspend fun <T> Collection.upsert(serializer: KSerializer<T>, value: T) {
    val json = encode(serializer, value)
    withWriteConnection { Queries.upsert(this@upsert).execute(json) }
    changed()
}
