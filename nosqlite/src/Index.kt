package io.github.nailkhaf.nosqlite

/**
 * An SQLite index over document fields, declared in a [Collection]. Indexes are synced when the database opens:
 * each one's name hashes its definition, so a changed index is dropped and created again and a removed one dropped.
 */
public class Index internal constructor(
    internal val collection: Collection<*>,
    internal val paths: List<Path>,
    public val isUnique: Boolean,
    internal val where: Condition?,
) {
    init {
        require(paths.isNotEmpty()) { "Index without fields" }
    }

    /** What the index is made of, as SQL: the part of `CREATE INDEX` after its table. */
    internal val definition: String =
        paths.joinToString(", ", prefix = "(", postfix = ")") { it.sql } +
            (where?.let { " WHERE ${it.render(inline = true).sql}" } ?: "")

    internal val name: String =
        "${INDEX_PREFIX}${collection.name}_${fnv1a64((if (isUnique) "UNIQUE " else "") + definition)}"

    override fun toString(): String =
        "${if (isUnique) "unique " else ""}index ${collection.name}(${paths.joinToString(", ")})" +
            (where?.let { " where $it" } ?: "")
}

internal const val INDEX_PREFIX = "nsq_"

/** The 64-bit FNV-1a hash of [text] as 16 hex digits: short, and the same on every platform. */
internal fun fnv1a64(text: String): String {
    var hash = 0xcbf29ce484222325uL
    for (byte in text.encodeToByteArray()) {
        hash = (hash xor byte.toUByte().toULong()) * 0x100000001b3uL
    }
    return hash.toString(16).padStart(16, '0')
}
