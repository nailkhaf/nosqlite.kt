package io.github.nailkhaf.nosqlite

import androidx.sqlite.SQLiteException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer

/**
 * A named set of [T] documents, stored as JSON in its own table. Declare one as an object with its indexes and list
 * it in `Database { }`:
 * ```
 * object Users : Collection<User>("users", User.serializer()) {
 *     val byEmail = unique<String>("email")
 *     val byAge = index("age")
 *     val byName = index("name.last", "name.first")
 * }
 *
 * val db = Database("app.db") { +Users }
 * ```
 */
public abstract class Collection<T>(public val name: String, internal val serializer: KSerializer<T>) {
    init {
        require(name.matches(Name)) { "Invalid collection name: '$name'" }
        require(!name.startsWith("sqlite_", ignoreCase = true)) { "Collection name is reserved by SQLite: '$name'" }
    }

    private val declaredIndexes = mutableListOf<Index<T>>()

    internal val indexes: List<Index<T>> get() = declaredIndexes

    /**
     * A unique index on [field]: documents can't share a value, and `index[key]` finds the one that has it.
     * With [where] only matching documents are indexed.
     */
    protected inline fun <reified K> unique(
        field: String,
        noinline where: (WhereScope.() -> Condition)? = null,
    ): UniqueIndex<T, K> = register(UniqueIndex(this, Path(field), where?.let(::where), serializer<K>()))

    /** A unique index on several fields together. */
    protected fun unique(
        first: String,
        second: String,
        vararg rest: String,
        where: (WhereScope.() -> Condition)? = null,
    ): Index<T> {
        val paths = (listOf(first, second) + rest).map(::Path)
        return register(Index(this, paths, isUnique = true, where?.let(::where)))
    }

    /** An index on [fields], in order; with [where] only matching documents are indexed. */
    protected fun index(
        vararg fields: String,
        where: (WhereScope.() -> Condition)? = null,
    ): Index<T> = register(Index(this, fields.map(::Path), isUnique = false, where?.let(::where)))

    @PublishedApi
    internal fun <I : Index<T>> register(index: I): I {
        require(declaredIndexes.none { it.name == index.name }) { "Collection '$name' declares $index more than once" }
        declaredIndexes += index
        return index
    }

    override fun toString(): String = "Collection($name)"

    // Collection names are SQL identifiers, so they are part of the SQL text, quoted; everything else is bound.
    internal val table: String get() = "\"$name\""

    private companion object {
        val Name = Regex("[A-Za-z_][A-Za-z0-9_]*")
    }
}

/** Runs [block] on the transaction's connection, with SQLite's errors about this collection made typed. */
context(_: ReadScope)
internal suspend fun <R> Collection<*>.withConnection(block: suspend context(Connection) () -> R): R =
    mapErrors { context(connection()) { block() } }

private val IndexName = Regex("index '([^']+)'")

/**
 * Only declared collections have a table, so SQLite not knowing this collection's table means it wasn't declared.
 * A unique constraint error names the index only when it is an expression index, which all of ours are.
 */
internal inline fun <R> Collection<*>.mapErrors(block: () -> R): R = try {
    block()
} catch (e: SQLiteException) {
    val message = e.message.orEmpty()
    when {
        "no such table: $name" in message -> throw NosqliteException.CollectionNotDeclared(name, e)

        "UNIQUE constraint failed" in message -> {
            val index = IndexName.find(message)?.groupValues?.get(1)
            throw NosqliteException.UniqueViolation(name, indexes.firstOrNull { it.name == index }, e)
        }

        else -> throw e
    }
}
