package io.github.nailkhaf.nosqlite

/**
 * Documents of an index with the given values for its fields, made by calling the index. A [Unique] one is the
 * document, if any, of a unique index with values for all its fields; a [Many] one any number of documents, with
 * values for leading fields alone matching their prefix:
 * ```
 * Users.byId("ada").get()
 * Users.byAge(36).list { orderBy { "name.last".asc } }
 * Users.byName("Lovelace").count()
 * ```
 */
public sealed class Lookup<T>(internal val index: Index<T>, internal val values: List<SqlValue>) {
    // A partial index's own condition is part of every lookup, so only the documents it indexes match.
    internal val condition: Where = index.paths.zip(values) { path, value ->
        if (value == SqlValue.Null) Where.IsNull(path) else Where.Compare(path, Comparison.Eq, value)
    }.plus(listOfNotNull(index.where)).reduce { left, right -> Where.And(left, right) }

    override fun toString(): String = "$index = ${values.map { it.literal }}"

    public class Many<T> internal constructor(index: Index<T>, values: List<SqlValue>) : Lookup<T>(index, values)

    public class Unique<T> internal constructor(index: UniqueIndex<T>, values: List<SqlValue>) :
        Lookup<T>(index, values)
}

/**
 * Looks up [values] of this index's fields, in order; leading fields alone match their prefix. A `null` value matches
 * a null or missing field.
 */
public operator fun <T> Index<T>.invoke(vararg values: Any?): Lookup.Many<T> {
    require(values.size in 1..paths.size) { "$this takes 1 to ${paths.size} values, not ${values.size}" }
    return Lookup.Many(this, values.map(::sqlValue))
}

/** Looks up [values] of all this unique index's fields, in order; none can be `null`, as nulls are never unique. */
public operator fun <T> UniqueIndex<T>.invoke(vararg values: Any?): Lookup.Unique<T> {
    require(values.size == paths.size) { "$this takes ${paths.size} values, not ${values.size}" }
    require(null !in values) { "$this can't look up null, any number of documents can have it" }
    return Lookup.Unique(this, values.map(::sqlValue))
}

internal fun sqlValue(value: Any?): SqlValue = when (value) {
    null -> SqlValue.Null
    is String -> SqlValue.Text(value)
    is Number -> value.sqlValue
    is Boolean -> SqlValue.Integer(if (value) 1 else 0)
    else -> throw IllegalArgumentException("Unsupported value: ${value::class.simpleName}")
}

/** The documents found that [query] selects; its condition, if any, must hold as well. */
context(_: ReadScope)
public suspend fun <T> Lookup.Many<T>.list(query: QueryScope.() -> Unit = {}): List<T> {
    val query = query(query)
    val condition = query.condition?.let { Where.And(condition, it) } ?: condition
    return index.collection.list(Query(condition, query.orderBy, query.limit, query.offset))
}

/** How many documents are found. */
context(_: ReadScope)
public suspend fun Lookup.Many<*>.count(): Long = index.collection.count { condition }

/** Replaces the documents found with [value]; the result is how many were replaced. */
context(_: WriteScope)
public suspend fun <T> Lookup.Many<T>.update(value: T): Int = index.collection.update(value) { condition }

/** Deletes the documents found; the result is how many were deleted. */
context(_: WriteScope)
public suspend fun Lookup.Many<*>.delete(): Int = index.collection.delete { condition }

/** The document found, `null` when there is none. */
context(_: ReadScope)
public suspend fun <T> Lookup.Unique<T>.get(): T? =
    index.collection.list(Query(condition, emptyList(), limit = 1, offset = 0)).firstOrNull()

/** Replaces the document found with [value]; the result is whether there was one. */
context(_: WriteScope)
public suspend fun <T> Lookup.Unique<T>.update(value: T): Boolean = index.collection.update(value) { condition } > 0

/** Deletes the document found; the result is whether there was one. */
context(_: WriteScope)
public suspend fun Lookup.Unique<*>.delete(): Boolean = index.collection.delete { condition } > 0

/**
 * Inserts [value], or replaces the document that has its values in this index. A conflict in another unique index
 * still fails with [NosqliteException.UniqueViolation].
 */
context(_: WriteScope)
public suspend fun <T> UniqueIndex<T>.upsert(value: T) {
    val json = collection.encode(value)
    collection.withConnection { Queries.upsert(this@upsert).execute(json) }
    collection.changed()
}
