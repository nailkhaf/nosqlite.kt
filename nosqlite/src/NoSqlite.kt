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
 * Nothing touches the file until the first [read] or [write]. Opening the write connection creates the declared
 * collections in one write transaction before anything else runs. A collection's name is permanent: a table that
 * already exists is left in place when its collection is not declared.
 * A tracked collection records each of its changes.
 * [close] closes every connection that was opened.
 */
public class NoSqlite(
    private val location: Location = Location.InMemory,
    driver: SQLiteDriver = BundledSQLiteDriver(),
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    schema: SchemaBuilder.() -> Unit,
) {
    public constructor(path: String, schema: SchemaBuilder.() -> Unit) : this(Location.File(path), schema = schema)

    private val tables = SchemaBuilder().apply(schema)

    private val connections = ConnectionPool(location, driver, dispatcher) { connection ->
        connection.transaction(Boundary.Write) { context(connection) { createSchema(tables) } }
    }

    // Opening the writer creates the schema, so a first read on a new file finds its tables.
    private val ready = suspendLazy {
        connections.withWriter {}
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
        /**
         * How documents are encoded and decoded: unknown keys are ignored, so a smaller class reads a projection, and
         * nulls are written, so an update's merge patch removes the fields set to null.
         */
        public val Json: Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}

@DslMarker
public annotation class SchemaDsl

@SchemaDsl
public class SchemaBuilder internal constructor() {
    internal val collections = mutableListOf<Collection>()

    /** Declares this collection; each name can be declared once. */
    public operator fun Collection.unaryPlus() {
        require(collections.none { it.name == name }) { "Collection '$name' is declared more than once" }
        collections += this
    }
}

/**
 * Runs [block] in a read transaction: every read in it sees one snapshot. Inside [write] it runs in that write.
 * The block runs on one connection and must stay sequential. Using the database from coroutines launched inside it
 * (`launch`, `async`, parallel flow operators) concurrently is unsupported and can crash; sequential suspension
 * inside the block is fine. Do parallel work outside the transaction, each coroutine in its own [read] or [write],
 * or gather results first and write them in one sequential block.
 */
public suspend fun <R> NoSqlite.read(block: suspend context(ReadScope) () -> R): R = pool().read(block)

/**
 * Runs [block] in a write transaction that commits when it returns and rolls back when it throws.
 * Inside another write it is a savepoint that rolls back alone. The block runs on one connection and must stay
 * sequential. Using the database from coroutines launched inside it (`launch`, `async`, parallel flow operators)
 * concurrently is unsupported and can crash; sequential suspension inside the block is fine. Do parallel work
 * outside the transaction, each coroutine in its own [read] or [write], or gather results first and write them in
 * one sequential block. Pass [untracked] to record none of the writes tracked collections do in the block.
 */
public suspend fun <R> NoSqlite.write(
    untracked: Boolean = false,
    block: suspend context(WriteScope) () -> R,
): R = pool().write(onCommit = ::committed, untracked = untracked, block = block)

// Creates the queue and the declared collections; safe to run again. Existing tables are never dropped.
// One read of every managed index, then each collection is checked against that.
context(_: Connection)
internal suspend fun createSchema(tables: SchemaBuilder) {
    Queries.createPendingChanges.execute()
    Queries.createTrackingState.execute()
    Queries.seedTrackingState.execute()
    val indexes = Queries.managedIndexes.execute()
    tables.collections.forEach { createSchema(it, indexes) }
}

// Indexes are matched by name, which hashes their definition: undeclared ones are dropped, new ones created.
// A tracked collection also gets its temp triggers on this connection. The id index is not one of these:
// [Queries.createTable] makes it, and this step only leaves its fixed name in place.
context(_: Connection)
internal suspend fun createSchema(
    collection: Collection,
    indexes: Map<String, List<String>>,
) {
    Queries.createTable(collection).forEach { it.execute() }
    val current = indexes[collection.name].orEmpty()
    val wanted = collection.indexes.associateBy { it.name }
    val idIndex = Queries.idIndexName(collection)
    current.filter { it != idIndex && it !in wanted }.forEach { Queries.dropIndex(it).execute() }
    wanted.filterKeys { it !in current }.values.forEach { Queries.createIndex(it).execute() }
    if (collection.tracked) Queries.trackingTriggers(collection).forEach { it.execute() }
}
