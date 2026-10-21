package io.github.nailkhaf.nosqlite

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.serializer
import kotlin.reflect.KProperty1

/**
 * A named set of JSON documents, stored in its own table. Declare one as an object with its indexes and list it
 * in `Database { }`:
 * ```
 * object Users : Collection("users") {
 *     val byId = unique(User::id)
 *     val byEmail = unique(User::email)
 *     val byAge = index(User::age)
 *     val byName = index(User::name / Name::last, User::name / Name::first)
 * }
 *
 * val db = Database("app.db") { +Users }
 * ```
 * Index fields are checked against the document type's serial names when declared, so a property renamed with
 * `@SerialName` fails right there; [path] with the serial name is the way around it.
 */
public abstract class Collection(public val name: String) {
    init {
        require(name.matches(Name)) { "Invalid collection name: '$name'" }
        require(!name.startsWith("sqlite_", ignoreCase = true)) { "Collection name is reserved by SQLite: '$name'" }
    }

    private val declaredIndexes = mutableListOf<Index>()

    /** The indexes this collection declares, in order. */
    internal val indexes: List<Index> = declaredIndexes

    /** A unique index on [property]: documents can't share a value, and `index[key]` finds the one that has it. */
    protected inline fun <reified T, reified V> unique(
        property: KProperty1<T, V>,
        noinline where: (WhereScope.() -> Condition)? = null,
    ): UniqueIndex<V> = unique(property.toPath(), where)

    /** A unique index on [path]: documents can't share a value, and `index[key]` finds the one that has it. */
    protected inline fun <reified T, reified V> unique(
        path: Path<T, V>,
        noinline where: (WhereScope.() -> Condition)? = null,
    ): UniqueIndex<V> = addUnique(path, serializer<V>(), where) { serializer<T>().descriptor }

    /** A unique index on several fields together. */
    protected inline fun <reified T> unique(
        first: KProperty1<T, *>,
        second: KProperty1<T, *>,
        vararg rest: KProperty1<T, *>,
        noinline where: (WhereScope.() -> Condition)? = null,
    ): Index = unique(first.toPath(), second.toPath(), *rest.map { it.toPath() }.toTypedArray(), where = where)

    /** A unique index on several fields together. */
    protected inline fun <reified T> unique(
        first: Path<T, *>,
        second: Path<T, *>,
        vararg rest: Path<T, *>,
        noinline where: (WhereScope.() -> Condition)? = null,
    ): Index = add(listOf(first, second, *rest), unique = true, where) { serializer<T>().descriptor }

    /** An index on [properties], in order; with [where] only matching documents are indexed. */
    protected inline fun <reified T> index(
        vararg properties: KProperty1<T, *>,
        noinline where: (WhereScope.() -> Condition)? = null,
    ): Index = index(*properties.map { it.toPath() }.toTypedArray(), where = where)

    /** An index on [paths], in order; with [where] only matching documents are indexed. */
    protected inline fun <reified T> index(
        vararg paths: Path<T, *>,
        noinline where: (WhereScope.() -> Condition)? = null,
    ): Index = add(paths.toList(), unique = false, where) { serializer<T>().descriptor }

    @PublishedApi
    internal fun <V> addUnique(
        path: Path<*, V>,
        keySerializer: KSerializer<V>,
        where: (WhereScope.() -> Condition)?,
        descriptor: () -> SerialDescriptor,
    ): UniqueIndex<V> {
        check(path, descriptor)
        return register(UniqueIndex(this, path, where?.let(::where), keySerializer))
    }

    @PublishedApi
    internal fun add(
        paths: List<Path<*, *>>,
        unique: Boolean,
        where: (WhereScope.() -> Condition)?,
        descriptor: () -> SerialDescriptor,
    ): Index {
        paths.forEach { check(it, descriptor) }
        return register(Index(this, paths, unique, where?.let(::where)))
    }

    private fun check(path: Path<*, *>, descriptor: () -> SerialDescriptor) {
        if (!path.serialNames) checkPath(path, descriptor())
    }

    private fun <I : Index> register(index: I): I {
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

/** The collections a database declares, by name. */
internal class Catalog(collections: List<Collection>) {
    val collections: Map<String, Collection> = buildMap {
        for (collection in collections) {
            require(put(collection.name, collection) == null) {
                "Collection '${collection.name}' is declared more than once"
            }
        }
    }
}

/** The connection to run this collection's statements on, once it is known to belong to the transaction's database. */
context(scope: ReadScope)
internal fun Collection.connection(): Connection {
    if (scope.pool.catalog.collections[name] !== this) throw NosqliteException.CollectionNotDeclared(name)
    return scope.connection
}

context(scope: WriteScope)
internal fun Collection.changed(affected: Int) {
    if (affected > 0) scope.changed(setOf(name))
}
