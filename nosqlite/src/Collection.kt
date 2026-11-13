package io.github.nailkhaf.nosqlite

import androidx.sqlite.SQLiteException
import androidx.sqlite.SQLiteStatement
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer
import kotlin.reflect.KProperty1
import kotlin.uuid.Uuid

/**
 * A named set of JSON object documents, stored in its own table. The documents are the JSON itself: each read decodes
 * them into the type it asks for, and a typed write changes only the fields its type declares, so fields the app
 * doesn't know yet survive. Declare each collection once, as an object extending it, and list it in `NoSqlite { }`.
 * Every document has an `id`, stored as [kotlin.uuid.Uuid.toString]: required, unique and immutable. Pass `tracked = true` to
 * record every insert, update and delete.
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

    internal val indexes: List<Index>
        field = mutableListOf()

    /** An index on [fields], in order; with [where] only matching documents are indexed. */
    protected fun index(vararg fields: String, where: (WhereScope.() -> Where)? = null): Index =
        register(Index(this, fields.map(::Path), where?.let(::where)))

    /** An index on property [fields], named by their property names. */
    protected fun index(vararg fields: Field<*, *>, where: (WhereScope.() -> Where)? = null): Index =
        index(*fields.map { it.path }.toTypedArray(), where = where)

    protected fun index(vararg properties: KProperty1<*, *>, where: (WhereScope.() -> Where)? = null): Index =
        index(*properties.map { it.field }.toTypedArray(), where = where)

    private fun <I : Index> register(index: I): I {
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

/**
 * [Collection.upsert] addressed by the collection's name, for code that has only the name, such as a replicator,
 * usually inside `write(untracked = true)` so a tracked collection does not record it. With [merge] = false the
 * document replaces the stored one whole, so fields it leaves out are removed and `null` fields are kept. A name the
 * schema does not have gets its table, id index and id trigger, and the schema keeps that name once the write
 * commits. [document] must be a JSON object whose `id` is a UUID.
 */
context(_: WriteScope)
public suspend fun upsert(collection: String, document: JsonObject, merge: Boolean = true) {
    requireCollectionName(collection)
    ensureTable(collection)
    val json = NoSqlite.Json.encodeToString(serializer<JsonObject>(), document)
    withWriteConnection(collection) { Queries.upsert(collection, merge).execute(json) }
    changed(collection)
}

/**
 * [Id.delete] addressed by the collection's name: deletes the document [id] from [collection].
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

/** Inserts [value] as a new document; it must encode to a JSON object. */
context(_: WriteScope)
public suspend inline fun <reified T> Collection.insert(value: T): Unit = insert(serializer(), value)

context(_: WriteScope)
public suspend fun <T> Collection.insert(serializer: KSerializer<T>, value: T) {
    val json = encode(serializer, value)
    withWriteConnection { Queries.insert(this@insert).execute(json) }
    changed()
}

/** The documents [query] selects, as [T]; without one, every document in an unspecified order. */
context(_: ReadScope)
public suspend inline fun <reified T> Collection.list(noinline query: QueryScope.() -> Unit = {}): List<T> =
    list(serializer(), query)

context(_: ReadScope)
public suspend fun <T> Collection.list(serializer: KSerializer<T>, query: QueryScope.() -> Unit = {}): List<T> =
    list(serializer, query(query))

context(_: ReadScope)
internal suspend fun <T> Collection.list(serializer: KSerializer<T>, query: Query): List<T> =
    withConnection { Queries.select(this@list, serializer, query).execute() }

/** How many documents match [condition], or all of them without one. */
context(_: ReadScope)
public suspend fun Collection.count(condition: (WhereScope.() -> Where)? = null): Long =
    withConnection { Queries.count(this@count, condition?.let(::where)).execute() }

/**
 * Updates every document matching [condition] with [value], so match on `id` to update one. With [merge] (the
 * default) [value] is a JSON merge patch: its fields replace theirs, a `null` removes one, and fields it doesn't have
 * are kept. Without it [value] is stored as each whole document, so it must carry each match's id. The result is how
 * many documents were updated.
 */
context(_: WriteScope)
public suspend inline fun <reified T> Collection.update(
    value: T,
    merge: Boolean = true,
    noinline condition: WhereScope.() -> Where,
): Int = update(serializer(), value, merge, condition)

context(_: WriteScope)
public suspend fun <T> Collection.update(
    serializer: KSerializer<T>,
    value: T,
    merge: Boolean = true,
    condition: WhereScope.() -> Where,
): Int {
    val json = encode(serializer, value)
    val updated = withWriteConnection { Queries.update(this@update, json, merge, where(condition)).execute() }
    if (updated > 0) changed()
    return updated
}

/** Deletes every document matching [condition]; the result is how many were deleted. */
context(_: WriteScope)
public suspend fun Collection.delete(condition: WhereScope.() -> Where): Int {
    val deleted = withWriteConnection { Queries.delete(name, where(condition)).execute() }
    if (deleted > 0) changed()
    return deleted
}

// Collection names are SQL identifiers, so they are part of the SQL text, quoted; everything else is bound.
internal fun table(name: String): String = identifier(tableName(name))

internal val Collection.table: String get() = table(name)

internal fun <T> Collection.encode(serializer: KSerializer<T>, value: T): String {
    val json = NoSqlite.Json.encodeToString(serializer, value)
    require(json.startsWith('{')) { "A document of '$name' must be a JSON object, not $json" }
    return json
}

/**
 * The document in the current row of a [Queries.selectSql] statement, as [serializer] reads it.
 * Custom serializers and init blocks fail with any exception, not only SerializationException.
 */
internal fun <T> Collection.decode(serializer: KSerializer<T>, row: SQLiteStatement): T = try {
    NoSqlite.Json.decodeFromString(serializer, row.getText(0))
} catch (e: Exception) {
    throw NoSqliteException.DecodingFailed(name, e)
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
        Queries.createTable(name).forEach { it.execute() }
    }
    scope.created += name
}

private val ErrorCode = Regex("^Error code: (\\d+)")

// SQLITE_BUSY and SQLITE_LOCKED, with their extended codes, which keep the primary code in the low byte.
private val BusyCodes = setOf(5, 6)

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
        code != null && (code and 0xFF) in BusyCodes -> throw NoSqliteException.Busy(e)

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
