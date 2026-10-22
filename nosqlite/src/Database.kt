package io.github.nailkhaf.nosqlite

import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.serialization.json.Json

/**
 * A document database. Every collection it serves is declared up front:
 * ```
 * val db = Database("app.db") {
 *     +Users
 *     +Notes
 * }
 * ```
 * Nothing touches the file until the first [read] or [write], which creates the collections' tables in one write
 * transaction before anything else runs. [close] closes every connection that was opened.
 */
public class Database(
    location: Location = Location.InMemory,
    driver: SQLiteDriver = BundledSQLiteDriver(),
    schema: SchemaBuilder.() -> Unit,
) {
    public constructor(path: String, schema: SchemaBuilder.() -> Unit) : this(Location.File(path), schema = schema)

    private val connections = ConnectionPool(location, driver)

    private val collections = SchemaBuilder().apply(schema).collections.toList()

    private val ready = suspendLazy {
        connections.write { context(connection()) { collections.forEach { createSchema(it) } } }
        connections
    }

    internal suspend fun pool(): ConnectionPool = ready.get()

    /** Closes every connection that was opened; the database can't be used afterwards. */
    public suspend fun close(): Unit = connections.close()

    public companion object {
        /** How documents are encoded and decoded: unknown keys are ignored, so a smaller class reads a projection. */
        public val Json: Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }
    }
}

@DslMarker
public annotation class SchemaDsl

@SchemaDsl
public class SchemaBuilder internal constructor() {
    internal val collections = mutableListOf<Collection<*>>()

    /** Declares this collection; each name can be declared once. */
    public operator fun Collection<*>.unaryPlus() {
        require(collections.none { it.name == name }) { "Collection '$name' is declared more than once" }
        collections += this
    }
}

/** Runs [block] in a read transaction: every read in it sees one snapshot. Inside [write] it runs in that write. */
public suspend fun <R> Database.read(block: suspend context(ReadScope) () -> R): R = pool().read(block)

/**
 * Runs [block] in a write transaction that commits when it returns and rolls back when it throws.
 * Inside another write it is a savepoint that rolls back alone.
 */
public suspend fun <R> Database.write(block: suspend context(WriteScope) () -> R): R = pool().write(block)

// Indexes are matched by name, which hashes their definition: undeclared ones are dropped, new ones created.
context(_: Connection)
internal suspend fun createSchema(collection: Collection<*>) {
    Queries.createTable(collection).execute()
    val current = Queries.indexNames.execute(collection.name)
    val wanted = collection.indexes.associateBy { it.name }
    current.filter { it !in wanted }.forEach { Queries.dropIndex(it).execute() }
    wanted.filterKeys { it !in current }.values.forEach { index ->
        collection.mapErrors { Queries.createIndex(index).execute() }
    }
}
