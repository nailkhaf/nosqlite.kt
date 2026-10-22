package io.github.nailkhaf.nosqlite

/**
 * The documents of an index with the given values for its first fields, made by calling the index:
 * ```
 * Users.byId("ada").get()
 * Users.byAge(36).list { orderBy(asc("name.last")) }
 * Users.byName("Lovelace").count()
 * ```
 * A partial index's own condition is part of the key, so only the documents it indexes match.
 */
public class Key<T> internal constructor(internal val index: Index<T>, internal val values: List<SqlValue>) {
    internal val condition: Condition = index.paths.zip(values) { path, value ->
        Condition.Compare(path, Comparison.Eq, value)
    }.plus(listOfNotNull(index.where)).reduce { left, right -> Condition.And(left, right) }

    override fun toString(): String = "$index = ${values.map { it.literal }}"
}

/** The key for [values] of this index's fields, in order; leading fields alone match their prefix. */
public operator fun <T> Index<T>.invoke(vararg values: Any): Key<T> {
    require(values.size in 1..paths.size) { "$this takes 1 to ${paths.size} values, not ${values.size}" }
    return Key(this, values.map(::keyValue))
}

private fun keyValue(value: Any): SqlValue = when (value) {
    is String -> SqlValue.Text(value)
    is Number -> value.sqlValue
    is Boolean -> SqlValue.Integer(if (value) 1 else 0)
    else -> throw IllegalArgumentException("Unsupported key value: ${value::class.simpleName}")
}

/** The document with this key of a unique index, `null` when there is none. */
context(_: ReadScope)
public suspend fun <T> Key<T>.get(): T? {
    require(index.isUnique && values.size == index.paths.size) { "get needs every field of a unique index: $this" }
    return index.collection.list(Query(condition, emptyList(), limit = 1, offset = 0)).firstOrNull()
}

/** The documents with this key that [query] selects; its condition, if any, must hold as well. */
context(_: ReadScope)
public suspend fun <T> Key<T>.list(query: QueryScope.() -> Unit = {}): List<T> {
    val query = query(query)
    val condition = query.condition?.let { Condition.And(condition, it) } ?: condition
    return index.collection.list(Query(condition, query.orderBy, query.limit, query.offset))
}

/** How many documents have this key. */
context(_: ReadScope)
public suspend fun Key<*>.count(): Long = index.collection.count { condition }

/** Replaces the documents with this key with [value]; the result is how many were replaced. */
context(_: WriteScope)
public suspend fun <T> Key<T>.update(value: T): Int = index.collection.update(value) { condition }

/** Deletes the documents with this key; the result is how many were deleted. */
context(_: WriteScope)
public suspend fun Key<*>.delete(): Int = index.collection.delete { condition }

/**
 * Inserts [value], or replaces the document that has its key in this unique index. A conflict in another unique
 * index still fails with [NosqliteException.UniqueViolation].
 */
context(_: WriteScope)
public suspend fun <T> Index<T>.upsert(value: T) {
    require(isUnique) { "upsert needs a unique index: $this" }
    val json = collection.encode(value)
    collection.withConnection { Queries.upsert(this@upsert).execute(json) }
}
