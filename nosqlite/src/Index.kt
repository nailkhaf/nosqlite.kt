package io.github.nailkhaf.nosqlite

/**
 * An SQLite index over document fields, declared in a [Collection]. Indexes are synced when the database opens.
 * Each one's name is `nsq_idx_<collection>_<hash>`, and the hash is of its definition, so a changed index is dropped
 * and created again and a removed one dropped.
 */
public open class Index internal constructor(
    internal val collection: Collection,
    internal val keys: List<Order>,
    internal val where: Where?,
) {
    init {
        require(keys.isNotEmpty()) { "Index without fields" }
    }

    internal val paths: List<Path> = keys.map { it.path }

    /** What the index is made of, as SQL: the part of `CREATE INDEX` after its table. */
    internal val definition: String =
        keys.joinToString(", ", prefix = "(", postfix = ")") {
            if (it.descending) "${it.path.sql} DESC" else it.path.sql
        } + (where?.let { " WHERE ${it.render(inline = true).sql}" } ?: "")

    internal val name: String = indexName(collection.name, fnv1a64(definition))

    override fun toString(): String {
        val fields = keys.joinToString(", ") { if (it.descending) "${it.path} desc" else "${it.path}" }
        return "index ${collection.name}($fields)" + (where?.let { " where $it" } ?: "")
    }
}

/**
 * The built-in unique index on every document's `id`, exposed as [Collection.byId].
 * Call it with a [kotlin.uuid.Uuid] to address one document, or use it in `where` and `orderBy`.
 */
public class IdIndex internal constructor(internal val collection: Collection) {
    override fun toString(): String = "index ${collection.name}($ID)"
}

internal fun Index.lookupValues(values: Array<out Any?>): List<SqlValue> {
    require(values.size in 1..paths.size) { "$this takes 1 to ${paths.size} values, not ${values.size}" }
    return values.map(::sqlValue)
}

// A partial index's own condition is part of every lookup, so only the documents it indexes match.
internal fun Index.lookupCondition(values: List<SqlValue>): Where =
    paths.zip(values) { path, value ->
        if (value == SqlValue.Null) Where.IsNull(path) else Where.Compare(path, Comparison.Eq, value)
    }.plus(listOfNotNull(where)).reduce { left, right -> Where.And(left, right) }

/**
 * A range condition in this index's order (`after` = `>`/`>=`, otherwise `<`/`<=`), expanded into leading
 * inclusive bounds so SQLite seeks the expression index even for multi-column and mixed-direction indexes.
 */
internal fun Index.rangeCondition(values: List<Any>, after: Boolean, inclusive: Boolean): Where {
    require(values.size in 1..keys.size) { "$this takes 1 to ${keys.size} values, not ${values.size}" }
    val sqlValues = values.map(::sqlValue)
    val last = sqlValues.lastIndex
    var result: Where = run {
        val key = keys[last]
        val forward = after != key.descending
        val comparison = when {
            forward && inclusive -> Comparison.Gte
            forward -> Comparison.Gt
            inclusive -> Comparison.Lte
            else -> Comparison.Lt
        }
        Where.Compare(key.path, comparison, sqlValues[last])
    }
    for (i in last - 1 downTo 0) {
        val key = keys[i]
        val forward = after != key.descending
        val inclusiveOp = if (forward) Comparison.Gte else Comparison.Lte
        val strictOp = if (forward) Comparison.Gt else Comparison.Lt
        result = Where.And(
            Where.Compare(key.path, inclusiveOp, sqlValues[i]),
            Where.Or(Where.Compare(key.path, strictOp, sqlValues[i]), result),
        )
    }
    return where?.let { Where.And(result, it) } ?: result
}

/** Prefix of every SQLite object nosqlite creates. */
internal const val PREFIX: String = "nsq_"

/** Prefix of every index. A collection name cannot start with `idx`, so no table is `nsq_idx...`. */
internal const val INDEX_PREFIX: String = "${PREFIX}idx_"

/** [INDEX_PREFIX], the collection name, `_`, then [suffix]. Quote the result with [identifier] in SQL. */
internal fun indexName(collection: String, suffix: String): String = "$INDEX_PREFIX${collection}_$suffix"

/** [PREFIX], the collection name, `_`, then [suffix]. Triggers have their own namespace. */
internal fun triggerName(collection: String, suffix: String): String = "$PREFIX${collection}_$suffix"

/** [PREFIX] plus the collection name: the table, unquoted. */
internal fun tableName(collection: String): String = "$PREFIX$collection"

/** The 64-bit FNV-1a hash of [text] as 16 hex digits: short, and the same on every platform. */
internal fun fnv1a64(text: String): String {
    var hash = 0xcbf29ce484222325uL
    for (byte in text.encodeToByteArray()) {
        hash = (hash xor byte.toUByte().toULong()) * 0x100000001b3uL
    }
    return hash.toString(16).padStart(16, '0')
}
