package io.github.nailkhaf.nosqlite

/**
 * An SQLite index over document fields, declared in a [Collection]. Indexes are synced when the database opens:
 * each one's name hashes its definition, so a changed index is dropped and created again and a removed one dropped.
 */
public open class Index internal constructor(
    /** The collection whose documents this index covers. */
    public val collection: BaseCollection,
    internal val paths: List<Path>,
    internal val isUnique: Boolean,
    internal val where: Where?,
) {
    init {
        require(paths.isNotEmpty()) { "Index without fields" }
    }

    /** The fields this index is on, in order, as dotted paths: `"name.last"`. */
    public val fields: List<String> get() = paths.map { it.field }

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

/** An index whose documents can't share their values for its fields; with a `where`, only the ones it indexes. */
public open class UniqueIndex internal constructor(collection: BaseCollection, paths: List<Path>, where: Where?) :
    Index(collection, paths, isUnique = true, where)

/** A [UniqueIndex] on one field whose value an update cannot change. */
public class PrimaryKey internal constructor(collection: BaseCollection, path: Path) :
    UniqueIndex(collection, listOf(path), where = null)

internal const val INDEX_PREFIX = "nsq_"

/** The 64-bit FNV-1a hash of [text] as 16 hex digits: short, and the same on every platform. */
internal fun fnv1a64(text: String): String {
    var hash = 0xcbf29ce484222325uL
    for (byte in text.encodeToByteArray()) {
        hash = (hash xor byte.toUByte().toULong()) * 0x100000001b3uL
    }
    return hash.toString(16).padStart(16, '0')
}
