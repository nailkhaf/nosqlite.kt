package io.github.nailkhaf.nosqlite

/**
 * A named set of JSON documents, stored in its own table. Declare one as an object and list it in `Database { }`:
 * ```
 * object Users : Collection("users")
 *
 * val db = Database("app.db") { +Users }
 * ```
 */
public abstract class Collection(public val name: String) {
    init {
        require(name.matches(Name)) { "Invalid collection name: '$name'" }
        require(!name.startsWith("sqlite_", ignoreCase = true)) { "Collection name is reserved by SQLite: '$name'" }
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

// The connection to run collection's statements on, once it is known to belong to the transaction's database.
context(scope: ReadScope)
internal fun Collection.connection(): Connection {
    if (scope.pool.catalog.collections[name] !== this) throw NosqliteException.CollectionNotDeclared(name)
    return scope.connection
}

context(scope: WriteScope)
internal fun Collection.changed(affected: Int) {
    if (affected > 0) scope.changed(setOf(name))
}
