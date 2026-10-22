package io.github.nailkhaf.nosqlite

import androidx.sqlite.SQLiteException
import kotlinx.serialization.serializer
import kotlin.reflect.KProperty1

/**
 * A named set of JSON documents, stored in its own table. Declare one as an object with its indexes and list it
 * in `Database { }`:
 * ```
 * object Users : Collection("users") {
 *     val byEmail = unique(User::email)
 *     val byAge = index(User::age)
 *     val byName = index(User::name / Name::last, User::name / Name::first)
 * }
 *
 * val db = Database("app.db") { +Users }
 * ```
 */
public abstract class Collection(public val name: String) {
    init {
        require(name.matches(Name)) { "Invalid collection name: '$name'" }
        require(!name.startsWith("sqlite_", ignoreCase = true)) { "Collection name is reserved by SQLite: '$name'" }
    }

    private val declaredIndexes = mutableListOf<Index>()

    internal val indexes: List<Index> get() = declaredIndexes

    /** A unique index on [property]: documents can't share a value, and `index[key]` finds the one that has it. */
    protected inline fun <T, reified V> unique(
        property: KProperty1<T, V>,
        noinline where: (WhereScope<T>.() -> Condition)? = null,
    ): UniqueIndex<V> = register(UniqueIndex(this, property.toPath(), where?.let(::where), serializer<V>()))

    /** A unique index on the field at [path]. */
    protected inline fun <reified V> unique(path: Path<V>): UniqueIndex<V> =
        register(UniqueIndex(this, path, where = null, serializer<V>()))

    /** A unique index on several fields together. */
    protected fun <T> unique(
        first: KProperty1<T, *>,
        second: KProperty1<T, *>,
        vararg rest: KProperty1<T, *>,
        where: (WhereScope<T>.() -> Condition)? = null,
    ): Index {
        val paths = (listOf(first, second) + rest).map { it.toPath() }
        return register(Index(this, paths, isUnique = true, where?.let(::where)))
    }

    /** An index on [properties], in order; with [where] only matching documents are indexed. */
    protected fun <T> index(
        vararg properties: KProperty1<T, *>,
        where: (WhereScope<T>.() -> Condition)? = null,
    ): Index = register(Index(this, properties.map { it.toPath() }, isUnique = false, where?.let(::where)))

    /** An index on the fields at [paths], in order. */
    protected fun index(vararg paths: Path<*>): Index =
        register(Index(this, paths.toList(), isUnique = false, where = null))

    @PublishedApi
    internal fun <I : Index> register(index: I): I {
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
internal suspend fun <R> Collection.withConnection(block: suspend context(Connection) () -> R): R =
    mapErrors { context(connection()) { block() } }

private val IndexName = Regex("index '([^']+)'")

/**
 * Only declared collections have a table, so SQLite not knowing this collection's table means it wasn't declared.
 * A unique constraint error names the index only when it is an expression index, which all of ours are.
 */
internal inline fun <R> Collection.mapErrors(block: () -> R): R = try {
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
