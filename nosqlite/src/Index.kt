package io.github.nailkhaf.nosqlite

/**
 * An SQLite index over document fields, declared in a [Collection]. Indexes are synced when the database opens.
 * Each one's name is `nsq_idx_<collection>_<hash>`, and the hash is of its definition, so a changed index is dropped
 * and created again and a removed one dropped.
 */
public open class Index internal constructor(
    internal val collection: Collection,
    internal val paths: List<Path>,
    internal val where: Where?,
) {
    init {
        require(paths.isNotEmpty()) { "Index without fields" }
    }

    /** What the index is made of, as SQL: the part of `CREATE INDEX` after its table. */
    internal val definition: String =
        paths.joinToString(", ", prefix = "(", postfix = ")") { it.sql } +
            (where?.let { " WHERE ${it.render(inline = true).sql}" } ?: "")

    internal val name: String = indexName(collection.name, fnv1a64(definition))

    override fun toString(): String =
        "index ${collection.name}(${paths.joinToString(", ")})" + (where?.let { " where $it" } ?: "")
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
