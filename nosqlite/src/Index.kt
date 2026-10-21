package io.github.nailkhaf.nosqlite

import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer

/**
 * An SQLite index over document fields, declared in a [Collection]. Indexes are synced when the database opens:
 * each one's name hashes its definition, so a changed index is dropped and created again and a removed one dropped.
 */
public open class Index internal constructor(
    public val collection: Collection,
    internal val paths: List<Path<*, *>>,
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

    internal val name: String = "${INDEX_PREFIX}${collection.name}_${fnv1a64(definition)}"

    override fun toString(): String =
        "${if (isUnique) "unique " else ""}index ${collection.name}(${paths.joinToString(", ")})" +
            (where?.let { " where $it" } ?: "")
}

/** A unique index on one field, so a [K] key finds at most one document. */
public class UniqueIndex<K> internal constructor(
    collection: Collection,
    internal val path: Path<*, K>,
    where: Condition?,
    internal val keySerializer: KSerializer<K>,
) : Index(collection, listOf(path), isUnique = true, where) {

    /** The document whose key is [key], decoded as [T]; `null` when there is none. */
    context(_: ReadScope)
    public suspend inline operator fun <reified T> get(key: K): T? = find(key, serializer<T>())

    @PublishedApi
    context(_: ReadScope)
    internal suspend fun <T> find(key: K, serializer: KSerializer<T>): T? = context(collection.connection()) {
        Queries.selectWhere(collection, lookup(key), limit = 1).execute().firstOrNull()?.let {
            collection.decode(it, serializer)
        }
    }
}

// Names of the indexes nosqlite manages; others on its tables are left alone.
internal const val INDEX_PREFIX = "nsq_"

/** The 64-bit FNV-1a hash of [text] as 16 hex digits: short, stable on every platform, enough to tell definitions apart. */
internal fun fnv1a64(text: String): String {
    var hash = 0xcbf29ce484222325uL
    for (byte in text.encodeToByteArray()) {
        hash = (hash xor byte.toUByte().toULong()) * 0x100000001b3uL
    }
    return hash.toString(16).padStart(16, '0')
}

/** The condition that finds [key] in this index, the partial index's own condition included. */
@PublishedApi
internal fun <K> UniqueIndex<K>.lookup(key: K): Condition {
    val match = Condition.Compare(path, Comparison.Eq, sqlValue(keySerializer, key))
    return where?.let { Condition.And(match, it) } ?: match
}
