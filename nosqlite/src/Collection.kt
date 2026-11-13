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
 * doesn't know yet survive. Create one directly, or extend it to declare indexes, and list it in `NoSqlite { }`.
 * Every document has an `id`, stored as [kotlin.uuid.Uuid.toString]: required, unique and immutable. Pass `tracked = true` to
 * record every insert, update and delete.
 * ```
 * val Notes = Collection("notes")
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
public open class Collection(public val name: String, public val tracked: Boolean = false) {
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
 * Inserts [document] into [collection], or merges it into the document with the same id, as [Collection.upsert] does.
 * Only inside `write(untracked = true)`. A name the schema does not have gets its table, id index and id trigger,
 * and the schema keeps that name once the write commits. [document] must be a JSON object whose `id` is a UUID.
 */
context(scope: WriteScope)
public suspend fun upsert(collection: String, document: JsonObject) {
    requireUntracked()
    if (scope.untracked) ensureTable(collection)
    Collection(collection).upsert(document)
}

/**
 * Deletes the document [id] from [collection], as [Id.delete] does. Only inside `write(untracked = true)`.
 * A name the schema does not have is left untouched. The result is whether a document was deleted.
 */
context(scope: WriteScope)
public suspend fun delete(collection: String, id: Uuid): Boolean {
    requireUntracked()
    requireCollectionName(collection)
    if (collection !in scope.pool.schema && collection !in scope.created) return false
    return Collection(collection).id(id).delete()
}

context(scope: WriteScope)
private fun requireUntracked() {
    check(scope.untracked) { "By-name changes are only allowed in an untracked write" }
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
 * Applies [value] as a JSON merge patch to every document matching [condition], so match on `id` to update one:
 * its fields replace theirs, a `null` removes one, and fields it doesn't have are kept. The result is how many
 * documents were updated.
 */
context(_: WriteScope)
public suspend inline fun <reified T> Collection.update(value: T, noinline condition: WhereScope.() -> Where): Int =
    update(serializer(), value, condition)

context(_: WriteScope)
public suspend fun <T> Collection.update(serializer: KSerializer<T>, value: T, condition: WhereScope.() -> Where): Int {
    val json = encode(serializer, value)
    val updated = withWriteConnection { Queries.update(this@update, json, where(condition)).execute() }
    if (updated > 0) changed()
    return updated
}

/** Replaces every document matching [condition] with [value]; the result is how many documents were replaced. */
context(_: WriteScope)
public suspend inline fun <reified T> Collection.replace(value: T, noinline condition: WhereScope.() -> Where): Int =
    replace(serializer(), value, condition)

context(_: WriteScope)
public suspend fun <T> Collection.replace(
    serializer: KSerializer<T>,
    value: T,
    condition: WhereScope.() -> Where,
): Int {
    val json = encode(serializer, value)
    val replaced = withWriteConnection { Queries.replace(this@replace, json, where(condition)).execute() }
    if (replaced > 0) changed()
    return replaced
}

/** Deletes every document matching [condition]; the result is how many were deleted. */
context(_: WriteScope)
public suspend fun Collection.delete(condition: WhereScope.() -> Where): Int {
    val deleted = withWriteConnection { Queries.delete(this@delete, where(condition)).execute() }
    if (deleted > 0) changed()
    return deleted
}

// Collection names are SQL identifiers, so they are part of the SQL text, quoted; everything else is bound.
internal val Collection.table: String get() = identifier(tableName(name))

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
    mapErrors(this) { context(connection()) { block() } }

/** A write, with SQLite's errors about this collection made typed. */
context(_: WriteScope)
internal suspend fun <R> Collection.withWriteConnection(block: suspend context(Connection) () -> R): R =
    mapErrors(this) { context(connection()) { block() } }

/**
 * Creates this collection's table, id index and id trigger when the schema does not have [name], and remembers the
 * name so the outermost write can register it after it commits. Callers decide whether to ask:
 * `if (scope.untracked) ensureTable(name)`. The statements are safe to repeat.
 */
context(scope: WriteScope)
internal suspend fun ensureTable(name: String) {
    if (name in scope.pool.schema) return
    val collection = Collection(name)
    context(connection()) {
        Queries.createTable(collection).forEach { it.execute() }
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
internal inline fun <R> mapErrors(collection: Collection? = null, block: () -> R): R = try {
    block()
} catch (e: SQLiteException) {
    val message = e.message.orEmpty()
    val code = ErrorCode.find(message)?.groupValues?.get(1)?.toInt()
    when {
        code != null && (code and 0xFF) in BusyCodes -> throw NoSqliteException.Busy(e)

        collection == null -> throw e

        "no such table: ${tableName(collection.name)}" in message ->
            throw NoSqliteException.CollectionNotDeclared(collection.name, e)

        "CHECK constraint failed: document_id" in message ->
            throw NoSqliteException.InvalidId(collection.name, e)

        "UNIQUE constraint failed" in message -> throw NoSqliteException.DuplicateId(collection.name, e)

        "id is immutable" in message -> throw NoSqliteException.ImmutableId(collection.name, e)

        else -> throw e
    }
}
