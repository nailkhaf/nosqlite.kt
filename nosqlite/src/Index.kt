package io.github.nailkhaf.nosqlite

import kotlin.uuid.Uuid

/**
 * An SQLite index on a [Collection]: either a declared index over document fields ([Many]), or the built-in unique
 * index on every document's `id` ([Id]). Declared indexes are synced when the database opens; each one's name is
 * `nsq_idx_<collection>_<hash>`, so a changed index is dropped and created again and a removed one dropped.
 */
public sealed class Index(
    internal val collection: Collection,
    internal val keys: List<Order>,
    internal val where: Where?,
) {
    internal abstract val name: String

    internal val paths: List<Path> = keys.map { it.path }

    override fun toString(): String {
        val fields = keys.joinToString(", ") { if (it.descending) "${it.path} desc" else "${it.path}" }
        return "index ${collection.name}($fields)" + (where?.let { " where $it" } ?: "")
    }

    /** A non-unique index over declared document fields; calling it returns a [Lookup.Many]. */
    public class Many internal constructor(
        collection: Collection,
        keys: List<Order>,
        where: Where?,
    ) : Index(collection, keys, where) {
        init {
            require(keys.isNotEmpty()) { "Index without fields" }
        }

        /** What the index is made of, as SQL: the part of `CREATE INDEX` after its table. */
        internal val definition: String =
            keys.joinToString(", ", prefix = "(", postfix = ")") {
                if (it.descending) "${it.path.sql} DESC" else it.path.sql
            } + (where?.let { " WHERE ${it.render(inline = true).sql}" } ?: "")

        override val name: String = indexName(collection.name, fnv1a64(definition))

        /**
         * Looks up [values] of this index's leading fields, in order; no values matches every indexed document.
         * A `null` value matches a null or missing field. Reads without `orderBy` follow this index's remaining keys,
         * or their reverse when [reversed].
         */
        public operator fun invoke(vararg values: Any?, reversed: Boolean = false): Lookup.Many {
            require(values.size <= paths.size) { "$this takes at most ${paths.size} values, not ${values.size}" }
            return Lookup.Many(this, values.map(::sqlValue), reversed)
        }
    }

    /**
     * The built-in unique index on every document's `id`, exposed as [Collection.byId]; calling it with a [Uuid]
     * returns a [Lookup.Id], and calling it with no values returns a [Lookup.Many] ordered by `id`.
     */
    public class Id internal constructor(collection: Collection) :
        Index(collection, listOf(Order(Path(ID), descending = false)), where = null) {
        override val name: String = indexName(collection.name, ID)

        /** The document of this collection with [value] as its id. */
        public operator fun invoke(value: Uuid): Lookup.Id = Lookup.Id(collection, value)

        /**
         * Every document of this collection, ordered by `id` (or `id` descending when [reversed]) when no `orderBy` is
         * set.
         */
        public operator fun invoke(reversed: Boolean = false): Lookup.Many = Lookup.Many(this, emptyList(), reversed)
    }
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
