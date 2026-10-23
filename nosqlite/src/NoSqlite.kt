package io.github.nailkhaf.nosqlite

import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json

/**
 * A document database. Every collection it serves is declared up front:
 * ```
 * val db = NoSqlite("app.db") {
 *     +Users
 *     +Notes
 * }
 * ```
 * Nothing touches the file until the first [read] or [write], which drops the tables of removed collections and
 * creates the declared ones in one write transaction before anything else runs.
 * [close] closes every connection that was opened.
 */
public class NoSqlite(
    private val location: Location = Location.InMemory,
    driver: SQLiteDriver = BundledSQLiteDriver(),
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    schema: SchemaBuilder.() -> Unit,
) {
    public constructor(path: String, schema: SchemaBuilder.() -> Unit) : this(Location.File(path), schema = schema)

    private val connections = ConnectionPool(location, driver, dispatcher)

    private val tables = SchemaBuilder().apply(schema)

    private val ready = suspendLazy {
        connections.write {
            context(connection()) {
                tables.removed.forEach { Queries.dropTable(it).execute() }
                tables.collections.forEach { createSchema(it) }
            }
        }
        connections
    }

    /** How many committed writes changed each collection; one no write changed yet is missing. */
    internal val versions = MutableStateFlow(emptyMap<String, Long>())

    internal suspend fun pool(): ConnectionPool = ready.get()

    internal fun committed(changed: Set<String>) {
        if (changed.isEmpty()) return
        versions.update { current -> current + changed.associateWith { (current[it] ?: 0) + 1 } }
    }

    /** Closes every connection that was opened; the database can't be used afterwards. */
    public suspend fun close(): Unit = connections.close()

    /**
     * Closes the database and deletes its files, WAL sidecars included; it can't be used afterwards.
     * Run it when no [read] or [write] is in progress, as a connection still in use keeps writing to the deleted file.
     */
    public suspend fun delete() {
        close()
        location.delete()
    }

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
    internal val removed = mutableListOf<String>()

    /** Declares this collection; each name can be declared once. */
    public operator fun Collection<*>.unaryPlus() {
        require(collections.none { it.name == name }) { "Collection '$name' is declared more than once" }
        require(name !in removed) { "Collection '$name' is both declared and removed" }
        collections += this
    }

    /** Removes the collection with this name: its table and documents are dropped on open, if they exist. */
    public operator fun String.unaryMinus() {
        requireCollectionName(this)
        require(this !in removed) { "Collection '$this' is removed more than once" }
        require(collections.none { it.name == this }) { "Collection '$this' is both declared and removed" }
        removed += this
    }
}

/** Runs [block] in a read transaction: every read in it sees one snapshot. Inside [write] it runs in that write. */
public suspend fun <R> NoSqlite.read(block: suspend context(ReadScope) () -> R): R = pool().read(block)

/**
 * Runs [block] in a write transaction that commits when it returns and rolls back when it throws.
 * Inside another write it is a savepoint that rolls back alone.
 */
public suspend fun <R> NoSqlite.write(block: suspend context(WriteScope) () -> R): R =
    pool().write(onCommit = ::committed, block)

// Indexes are matched by name, which hashes their definition: undeclared ones are dropped, new ones created.
context(_: Connection)
internal suspend fun createSchema(collection: Collection<*>) {
    Queries.createTable(collection).execute()
    val columns = Queries.tableColumns.execute(collection.name)
    if (columns != Queries.TableColumns) throw NoSqliteException.IncompatibleTable(collection.name, columns)
    val current = Queries.indexNames.execute(collection.name)
    val wanted = collection.indexes.associateBy { it.name }
    current.filter { it !in wanted }.forEach { Queries.dropIndex(it).execute() }
    wanted.filterKeys { it !in current }.values.forEach { index ->
        collection.mapErrors { Queries.createIndex(index).execute() }
    }
}
