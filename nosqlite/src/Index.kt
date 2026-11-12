package io.github.nailkhaf.nosqlite

/**
 * An SQLite index over document fields, declared in a [Collection]. Indexes are synced when the database opens.
 * Each one's name is `nsq_<collection>:<hash>`, and the hash is of its definition, so a changed index is dropped and
 * created again and a removed one dropped. `:` cannot occur in a collection name, so the name cannot be a table's.
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

    internal val name: String = objectName(collection.name, fnv1a64(definition))

    override fun toString(): String =
        "index ${collection.name}(${paths.joinToString(", ")})" + (where?.let { " where $it" } ?: "")
}

/** Prefix of every SQLite object nosqlite creates. */
internal const val PREFIX: String = "nsq_"

/**
 * Between a collection name and an index or trigger suffix. A collection name cannot contain it, and tables and
 * indexes share one namespace, so `nsq_<collection>:<suffix>` cannot be a table.
 */
internal const val SEPARATOR: String = ":"

/** [PREFIX], the collection name, [SEPARATOR], then [suffix]. Quote the result with [identifier] in SQL. */
internal fun objectName(collection: String, suffix: String): String = "$PREFIX$collection$SEPARATOR$suffix"

/** The 64-bit FNV-1a hash of [text] as 16 hex digits: short, and the same on every platform. */
internal fun fnv1a64(text: String): String {
    var hash = 0xcbf29ce484222325uL
    for (byte in text.encodeToByteArray()) {
        hash = (hash xor byte.toUByte().toULong()) * 0x100000001b3uL
    }
    return hash.toString(16).padStart(16, '0')
}
