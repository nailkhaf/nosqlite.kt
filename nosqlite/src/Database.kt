package io.github.nailkhaf.nosqlite

import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlin.concurrent.Volatile

/**
 * A document database. Every collection it serves is declared up front:
 * ```
 * val db = Database("app.db") {
 *     +Users
 *     +Notes
 * }
 * ```
 * Nothing touches the file until the first [read] or [write], which creates the collections' tables in one write
 * transaction before anything else runs.
 */
public class Database internal constructor(private val connections: ConnectionPool) {
    private val schemaLock = Mutex()

    @Volatile
    private var schemaCreated = false

    internal val catalog: Catalog get() = connections.catalog

    /** The connection pool, once the declared collections have their tables. */
    internal suspend fun pool(): ConnectionPool {
        if (!schemaCreated) {
            schemaLock.withLock {
                if (!schemaCreated) {
                    connections.write {
                        context(connection()) { catalog.collections.values.forEach { createSchema(it) } }
                    }
                    schemaCreated = true
                }
            }
        }
        return connections
    }

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

/** Opens the database at [location] with the collections [schema] declares. */
public fun Database(
    location: Location = Location.InMemory,
    driver: SQLiteDriver = BundledSQLiteDriver(),
    schema: SchemaBuilder.() -> Unit,
): Database {
    val catalog = Catalog(SchemaBuilder().apply(schema).collections)
    return Database(ConnectionPool(location, driver, catalog = catalog))
}

/** Opens the database file at [path] with the collections [schema] declares. */
public fun Database(path: String, schema: SchemaBuilder.() -> Unit): Database =
    Database(Location.File(path), schema = schema)

/** Marks the `Database { }` block, so its receiver doesn't leak into nested lambdas. */
@DslMarker
public annotation class SchemaDsl

/** Declares the collections of a database: `Database(path) { +Users }`. */
@SchemaDsl
public class SchemaBuilder internal constructor() {
    internal val collections = mutableListOf<Collection>()

    /** Declares this collection; a name declared twice fails when the database is opened. */
    public operator fun Collection.unaryPlus() {
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

context(_: Connection)
internal suspend fun createSchema(collection: Collection) {
    Queries.createTable(collection).execute()
}
