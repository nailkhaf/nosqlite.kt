package io.github.nailkhaf.nosqlite

import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * A document database. Every collection it serves is declared up front:
 * ```
 * val db = Database("app.db") {
 *     +Users
 *     +Notes
 * }
 * ```
 * Opening it creates the collections' tables in one write transaction, so a [Database] is always ready to use.
 */
public class Database internal constructor(internal val pool: ConnectionPool) {
    /** Closes every connection that was opened; the database can't be used afterwards. */
    public suspend fun close(): Unit = pool.close()

    public companion object {
        /** How documents are encoded and decoded: unknown keys are ignored, so a smaller class reads a projection. */
        public val Json: Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }
    }
}

/** Opens the database at [location] with the collections [schema] declares, creating their tables. */
public suspend fun Database(
    location: Location = Location.InMemory,
    driver: SQLiteDriver = BundledSQLiteDriver(),
    schema: SchemaBuilder.() -> Unit,
): Database {
    val collections = SchemaBuilder().apply(schema).collections
    val pool = ConnectionPool(location, driver)
    try {
        pool.write { context(connection()) { collections.forEach { createSchema(it) } } }
    } catch (e: Throwable) {
        withContext(NonCancellable) { pool.close() }
        throw e
    }
    return Database(pool)
}

/** Opens the database file at [path] with the collections [schema] declares. */
public suspend fun Database(path: String, schema: SchemaBuilder.() -> Unit): Database =
    Database(Location.File(path), schema = schema)

/** Marks the `Database { }` block, so its receiver doesn't leak into nested lambdas. */
@DslMarker
public annotation class SchemaDsl

/** Declares the collections of a database: `Database(path) { +Users }`. */
@SchemaDsl
public class SchemaBuilder internal constructor() {
    internal val collections = mutableListOf<Collection>()

    /** Declares this collection; each name can be declared once. */
    public operator fun Collection.unaryPlus() {
        require(collections.none { it.name == name }) { "Collection '$name' is declared more than once" }
        collections += this
    }
}

/** Runs [block] in a read transaction: every read in it sees one snapshot. Inside [write] it runs in that write. */
public suspend fun <R> Database.read(block: suspend context(ReadScope) () -> R): R = pool.read(block)

/**
 * Runs [block] in a write transaction that commits when it returns and rolls back when it throws.
 * Inside another write it is a savepoint that rolls back alone.
 */
public suspend fun <R> Database.write(block: suspend context(WriteScope) () -> R): R = pool.write(block)

context(_: Connection)
internal suspend fun createSchema(collection: Collection) {
    Queries.createTable(collection).execute()
}
