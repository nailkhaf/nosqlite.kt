package io.github.nailkhaf.nosqlite

import androidx.sqlite.SQLiteException
import androidx.sqlite.SQLiteStatement
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer
import kotlin.reflect.KProperty1
import kotlin.uuid.Uuid

/**
 * A named set of JSON object documents in its own table. Each read decodes into the type it names, so one collection
 * serves any class. Declare it as an object and list it in `NoSqlite { }`. Every document has a unique, immutable
 * `id`, a [kotlin.uuid.Uuid] string. With [tracked], every insert, update and delete is recorded.
 * ```
 * object Notes : Collection("notes")
 *
 * object Users : Collection("users") {
 *     val byAge = index(User::age)
 *     val byName = index("name.last", "name.first")
 * }
 *
 * val db = NoSqlite("app.db") {
 *     +Notes
 *     +Users
 * }
 * ```
 */
public abstract class Collection(public val name: String, public val tracked: Boolean = false) {
    init {
        requireCollectionName(name)
    }

    /** The built-in unique index on every document's `id`. */
    public val byId: Index.Id = Index.Id(this)

    internal val indexes: List<Index.Many>
        field = mutableListOf()

    /** An index on [first] and [rest], in order; with [where] only matching documents are indexed. */
    protected fun index(first: String, vararg rest: String, where: Condition? = null): Index.Many = register(
        Index.Many(this, (listOf(first) + rest).map { Order(Path(it), descending = false) }, where?.let(::where)),
    )

    /** An index on property [first] and [rest], named by their property names. */
    protected fun index(first: Field<*, *>, vararg rest: Field<*, *>, where: Condition? = null): Index.Many =
        index(first.path, *rest.map { it.path }.toTypedArray(), where = where)

    /** An index on [first] and [rest], named by their property names. */
    protected fun index(first: KProperty1<*, *>, vararg rest: KProperty1<*, *>, where: Condition? = null): Index.Many =
        index(first.field, *rest.map { it.field }.toTypedArray(), where = where)

    /**
     * An index on the sort [keys] built in [OrderScope], each with its direction (`Task::done.asc`, `Task::id.desc`);
     * with [where] only matching documents are indexed.
     */
    protected fun index(where: Condition? = null, keys: OrderScope.() -> Unit): Index.Many =
        register(Index.Many(this, OrderScope().apply(keys).keys.toList(), where?.let(::where)))

    private fun register(index: Index.Many): Index.Many {
        require(indexes.none { it.name == index.name }) { "Collection '$name' declares $index more than once" }
        indexes += index
        return index
    }

    override fun toString(): String = "Collection($name)"
}

private val CollectionName = Regex("[A-Za-z_][A-Za-z0-9_]*")

internal fun requireCollectionName(name: String) {
    require(name.matches(CollectionName) && !name.startsWith("idx")) { "Invalid collection name: '$name'" }
    require(!name.startsWith("sqlite_", ignoreCase = true)) { "Collection name is reserved by SQLite: '$name'" }
    require(tableName(name) != PENDING_CHANGES && tableName(name) != TRACKING_STATE) {
        "Collection name is reserved: '$name'"
    }
}

/** Inserts [value] as a new document; it must encode to a JSON object. */
context(_: WriteScope)
public suspend inline fun <reified T> Collection.insert(value: T): Unit = insert(serializer(), value)

/** As the reified overload, with an explicit [serializer]. */
context(_: WriteScope)
public suspend fun <T> Collection.insert(serializer: KSerializer<T>, value: T) {
    val json = encode(serializer, value)
    withWriteConnection { Queries.insert(name).execute(json) }
    changed()
}

/** The documents [query] selects, as [T]; without one, every document in an unspecified order. */
context(_: ReadScope)
public suspend inline fun <reified T> Collection.list(noinline query: QueryBlock = {}): List<T> =
    list(serializer(), query)

/** As the reified overload, with an explicit [serializer]. */
context(_: ReadScope)
public suspend fun <T> Collection.list(serializer: KSerializer<T>, query: QueryBlock = {}): List<T> =
    list(serializer, query(query))

context(_: ReadScope)
internal suspend fun <T> Collection.list(serializer: KSerializer<T>, query: Query): List<T> =
    withConnection { Queries.select(name, serializer, query).execute() }

/** How many documents match [condition], or all of them without one. */
context(_: ReadScope)
public suspend fun Collection.count(condition: Condition? = null): Long =
    withConnection { Queries.count(name, condition?.let(::where)).execute() }

/** Whether any document matches [condition], or whether the collection has any without one. */
context(_: ReadScope)
public suspend fun Collection.exists(condition: Condition? = null): Boolean =
    withConnection { Queries.exists(name, condition?.let(::where)).execute() }

/**
 * Updates every document matching [condition] with [value]. With [merge], [value] is a JSON merge patch: its fields
 * replace theirs, a `null` removes one, and the rest are kept. Without it, [value] replaces each document whole and
 * must carry its id. Returns how many were updated.
 */
context(_: WriteScope)
public suspend inline fun <reified T> Collection.update(
    value: T,
    merge: Boolean = true,
    noinline condition: Condition,
): Int = update(serializer(), value, merge, condition)

/** As the reified overload, with an explicit [serializer]. */
context(_: WriteScope)
public suspend fun <T> Collection.update(
    serializer: KSerializer<T>,
    value: T,
    merge: Boolean = true,
    condition: Condition,
): Int {
    val json = encode(serializer, value)
    val updated = withWriteConnection { Queries.update(name, json, merge, where(condition)).execute() }
    if (updated > 0) changed()
    return updated
}

/**
 * Inserts [value], or changes the document with its id: with [merge] it applies [value] as a JSON merge patch, as
 * `update` does, and without it stores [value] as the whole document. [value] must carry an id.
 */
context(_: WriteScope)
public suspend inline fun <reified T> Collection.upsert(value: T, merge: Boolean = true): Unit =
    upsert(serializer(), value, merge)

/** As the reified overload, with an explicit [serializer]. */
context(_: WriteScope)
public suspend fun <T> Collection.upsert(serializer: KSerializer<T>, value: T, merge: Boolean = true) {
    val json = encode(serializer, value)
    withWriteConnection { Queries.upsert(name, merge).execute(json) }
    changed()
}

/**
 * [Collection.upsert] by the collection's name, for code that has only the name, such as a replicator. A name the
 * schema doesn't have yet gets its table. [document] must have a UUID `id`. Any valid name is written to, so check
 * [collection] against the names you expect when it comes from a source you don't trust, such as a remote peer.
 */
context(_: WriteScope)
public suspend fun upsert(collection: String, document: JsonObject, merge: Boolean = true) {
    requireCollectionName(collection)
    ensureTable(collection)
    val json = NoSqlite.Json.encodeToString(serializer<JsonObject>(), document)
    withWriteConnection(collection) { Queries.upsert(collection, merge).execute(json) }
    changed(collection)
}

/** Deletes every document matching [condition]; the result is how many were deleted. */
context(_: WriteScope)
public suspend fun Collection.delete(condition: Condition): Int {
    val deleted = withWriteConnection { Queries.delete(name, where(condition)).execute() }
    if (deleted > 0) changed()
    return deleted
}

/**
 * [Lookup.Id.delete] addressed by the collection's name: deletes the document [id] from [collection].
 * A collection without a table is left untouched. The result is whether a document was deleted.
 */
context(_: WriteScope)
public suspend fun delete(collection: String, id: Uuid): Boolean {
    requireCollectionName(collection)
    // The table may exist without the schema knowing it, created by name before the database reopened.
    val deleted = try {
        withWriteConnection(collection) { Queries.delete(collection, idCondition(id)).execute() }
    } catch (_: NoSqliteException.CollectionNotDeclared) {
        return false
    }
    if (deleted > 0) changed(collection)
    return deleted > 0
}

// Collection names are SQL identifiers, so they are part of the SQL text, quoted; everything else is bound.
internal fun table(name: String): String = identifier(tableName(name))

internal fun <T> Collection.encode(serializer: KSerializer<T>, value: T): String {
    val json = NoSqlite.Json.encodeToString(serializer, value)
    require(json.startsWith('{')) { "A document of '$name' must be a JSON object, not $json" }
    return json
}

/**
 * The document in the current row of a [Queries.selectSql] statement, as [serializer] reads it.
 * Custom serializers and init blocks fail with any exception, not only SerializationException.
 */
internal fun <T> decode(collection: String, serializer: KSerializer<T>, row: SQLiteStatement): T = try {
    NoSqlite.Json.decodeFromString(serializer, row.getText(0))
} catch (e: Exception) {
    throw NoSqliteException.DecodingFailed(collection, e)
}

/** Runs [block] on the transaction's connection, with SQLite's errors about this collection made typed. */
context(_: ReadScope)
internal suspend fun <R> Collection.withConnection(block: suspend context(Connection) () -> R): R =
    mapErrors(name) { context(connection()) { block() } }

/** A write, with SQLite's errors about this collection made typed. */
context(_: WriteScope)
internal suspend fun <R> Collection.withWriteConnection(block: suspend context(Connection) () -> R): R =
    withWriteConnection(name, block)

/** A write addressed by collection [name], with SQLite's errors about that name made typed. */
context(_: WriteScope)
internal suspend fun <R> withWriteConnection(name: String, block: suspend context(Connection) () -> R): R =
    mapErrors(name) { context(connection()) { block() } }

/**
 * Creates this collection's table, id index and id trigger when the schema does not have [name] and this write has
 * not created it yet, and remembers the name so the outermost write can register it after it commits. A savepoint
 * hands the names it created to the enclosing write when it releases.
 */
context(scope: WriteScope)
internal suspend fun ensureTable(name: String) {
    if (name in scope.pool.schema || name in scope.created) return
    context(connection()) {
        for (command in Queries.createTable(name)) command.execute()
    }
    scope.created += name
}

private val ErrorCode = Regex("^Error code: (\\d+)")

// SQLITE_BUSY and SQLITE_LOCKED, with their extended codes, which keep the primary code in the low byte.
private val BUSY_CODES = setOf(5, 6)

/**
 * Runs [block], making SQLite's errors typed: a locked database for any statement, and for one about [collection]
 * its missing table, an id that is not a lowercase canonical UUID, a duplicate id and an id change. Only declared
 * collections have a table, so SQLite not knowing this collection's table means it wasn't declared.
 * A unique constraint is a duplicate id. IdTest pins the message text for the bundled driver.
 */
internal inline fun <R> mapErrors(collection: String? = null, block: () -> R): R = try {
    block()
} catch (e: SQLiteException) {
    val message = e.message.orEmpty()
    val code = ErrorCode.find(message)?.groupValues?.get(1)?.toInt()
    when {
        code != null && (code and 0xFF) in BUSY_CODES -> throw NoSqliteException.Busy(e)

        collection == null -> throw e

        "no such table: ${tableName(collection)}" in message ->
            throw NoSqliteException.CollectionNotDeclared(collection, e)

        "CHECK constraint failed: document_id" in message ->
            throw NoSqliteException.InvalidId(collection, e)

        "UNIQUE constraint failed" in message -> throw NoSqliteException.DuplicateId(collection, e)

        "id is immutable" in message -> throw NoSqliteException.ImmutableId(collection, e)

        else -> throw e
    }
}
