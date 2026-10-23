package io.github.nailkhaf.nosqlite

import androidx.sqlite.SQLiteException
import androidx.sqlite.SQLiteStatement
import kotlinx.serialization.KSerializer
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import kotlin.reflect.KProperty1

/**
 * A named set of JSON object documents, stored in its own table. The documents are the JSON itself: each read decodes
 * them into the type it asks for, and a typed write changes only the fields its type declares, so fields the app
 * doesn't know yet survive. Create one directly, or extend it to declare indexes, and list it in `NoSqlite { }`:
 * ```
 * val Notes = Collection("notes")
 *
 * object Users : Collection("users") {
 *     val byEmail = unique("email")
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
public open class Collection(public val name: String) {
    init {
        requireCollectionName(name)
    }

    internal val indexes: List<Index>
        field = mutableListOf()

    /** A unique index on [fields]: documents can't share their values; with [where] only matching ones count. */
    protected fun unique(vararg fields: String, where: (WhereScope.() -> Where)? = null): UniqueIndex =
        register(UniqueIndex(this, fields.map(::Path), where?.let(::where)))

    /** An index on [fields], in order; with [where] only matching documents are indexed. */
    protected fun index(vararg fields: String, where: (WhereScope.() -> Where)? = null): Index =
        register(Index(this, fields.map(::Path), isUnique = false, where?.let(::where)))

    /** A unique index on property [fields]; each must be a serial field of [T] under its property name. */
    protected inline fun <reified T> unique(
        vararg fields: Field<T, *>,
        noinline where: (WhereScope.() -> Where)? = null,
    ): UniqueIndex = unique(serializer<T>(), fields.toList(), where)

    protected inline fun <reified T> unique(
        vararg properties: KProperty1<T, *>,
        noinline where: (WhereScope.() -> Where)? = null,
    ): UniqueIndex = unique(serializer<T>(), properties.map { it.field }, where)

    /** An index on property [fields]; each must be a serial field of [T] under its property name. */
    protected inline fun <reified T> index(
        vararg fields: Field<T, *>,
        noinline where: (WhereScope.() -> Where)? = null,
    ): Index = index(serializer<T>(), fields.toList(), where)

    protected inline fun <reified T> index(
        vararg properties: KProperty1<T, *>,
        noinline where: (WhereScope.() -> Where)? = null,
    ): Index = index(serializer<T>(), properties.map { it.field }, where)

    @PublishedApi
    internal fun <T> unique(
        serializer: KSerializer<T>,
        fields: List<Field<T, *>>,
        where: (WhereScope.() -> Where)?,
    ): UniqueIndex = unique(*fields.map { it.checkedPath(serializer) }.toTypedArray(), where = where)

    @PublishedApi
    internal fun <T> index(
        serializer: KSerializer<T>,
        fields: List<Field<T, *>>,
        where: (WhereScope.() -> Where)?,
    ): Index = index(*fields.map { it.checkedPath(serializer) }.toTypedArray(), where = where)

    // A property renamed with @SerialName is stored under its serial name, which a property reference can't name.
    private fun Field<*, *>.checkedPath(serializer: KSerializer<*>): String {
        keys.fold(serializer.descriptor) { descriptor, key ->
            val index = descriptor.getElementIndex(key)
            require(index != CompositeDecoder.UNKNOWN_NAME) {
                "'$this' is not a serial field of '$name': name a field renamed with @SerialName by its serial name"
            }
            descriptor.getElementDescriptor(index)
        }
        return path
    }

    private fun <I : Index> register(index: I): I {
        require(indexes.none { it.name == index.name }) { "Collection '$name' declares $index more than once" }
        indexes += index
        return index
    }

    override fun toString(): String = "Collection($name)"
}

private val CollectionName = Regex("[A-Za-z_][A-Za-z0-9_]*")

internal fun requireCollectionName(name: String) {
    require(name.matches(CollectionName)) { "Invalid collection name: '$name'" }
    require(!name.startsWith("sqlite_", ignoreCase = true)) { "Collection name is reserved by SQLite: '$name'" }
}

/** Inserts [value] as a new document; it must encode to a JSON object. */
context(_: WriteScope)
public suspend inline fun <reified T> Collection.insert(value: T): Unit = insert(serializer(), value)

context(_: WriteScope)
public suspend fun <T> Collection.insert(serializer: KSerializer<T>, value: T) {
    val json = encode(serializer, value)
    withConnection { Queries.insert(this@insert).execute(json) }
    changed()
}

/** The documents [query] selects, as [T]; without one, every document in the order they were inserted. */
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
 * Applies [value] as a JSON merge patch to every document matching [condition], so match on a unique field to update
 * one: its fields replace theirs, a `null` removes one, and fields it doesn't have are kept. The result is how many
 * documents were updated.
 */
context(_: WriteScope)
public suspend inline fun <reified T> Collection.update(value: T, noinline condition: WhereScope.() -> Where): Int =
    update(serializer(), value, condition)

context(_: WriteScope)
public suspend fun <T> Collection.update(serializer: KSerializer<T>, value: T, condition: WhereScope.() -> Where): Int {
    val patch = encodePatch(serializer, value)
    return withConnection { Queries.update(this@update, patch, where(condition)).execute() }.changed(this)
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
    return withConnection { Queries.replace(this@replace, json, where(condition)).execute() }.changed(this)
}

/** Deletes every document matching [condition]; the result is how many were deleted. */
context(_: WriteScope)
public suspend fun Collection.delete(condition: WhereScope.() -> Where): Int =
    withConnection { Queries.delete(this@delete, where(condition)).execute() }.changed(this)

/** The number of documents a write touched, recording a change when there were any. */
context(_: WriteScope)
private fun Int.changed(collection: Collection): Int {
    if (this > 0) collection.changed()
    return this
}

// Collection names are SQL identifiers, so they are part of the SQL text, quoted; everything else is bound.
internal val Collection.table: String get() = identifier(name)

internal fun <T> Collection.encode(serializer: KSerializer<T>, value: T): String =
    NoSqlite.Json.encodeToString(serializer, value).also(::requireObject)

// A merge patch removes a field by its null, so nulls are spelled out instead of left out.
internal fun <T> Collection.encodePatch(serializer: KSerializer<T>, value: T): String =
    ExplicitNulls.encodeToString(serializer, value).also(::requireObject)

private fun Collection.requireObject(json: String) {
    require(json.startsWith('{')) { "A document of '$name' must be a JSON object, not $json" }
}

private val ExplicitNulls = Json(NoSqlite.Json) { explicitNulls = true }

/**
 * The document in the current row of a [Queries.selectSql] statement, as [serializer] reads it.
 * Custom serializers and init blocks fail with any exception, not only SerializationException.
 */
internal fun <T> Collection.decode(serializer: KSerializer<T>, row: SQLiteStatement): T = try {
    NoSqlite.Json.decodeFromString(serializer, row.getText(1))
} catch (e: Exception) {
    throw NoSqliteException.DecodingFailed(name, row.getLong(0), e)
}

/** Runs [block] on the transaction's connection, with SQLite's errors about this collection made typed. */
context(_: ReadScope)
internal suspend fun <R> Collection.withConnection(block: suspend context(Connection) () -> R): R =
    mapErrors(this) { context(connection()) { block() } }

private val IndexName = Regex("index '([^']+)'")

private val ErrorCode = Regex("^Error code: (\\d+)")

// SQLITE_BUSY and SQLITE_LOCKED, with their extended codes, which keep the primary code in the low byte.
private val BusyCodes = setOf(5, 6)

/**
 * Runs [block], making SQLite's errors typed: a locked database for any statement, and for one about [collection]
 * its missing table and unique constraints. Only declared collections have a table, so SQLite not knowing this
 * collection's table means it wasn't declared. A unique constraint error names the index only when it is an
 * expression index, which all of ours are. Both are matched by SQLite's message text, which IndexTest and WriteTest
 * pin for the bundled driver.
 */
internal inline fun <R> mapErrors(collection: Collection? = null, block: () -> R): R = try {
    block()
} catch (e: SQLiteException) {
    val message = e.message.orEmpty()
    val code = ErrorCode.find(message)?.groupValues?.get(1)?.toInt()
    when {
        code != null && (code and 0xFF) in BusyCodes -> throw NoSqliteException.Busy(e)

        collection == null -> throw e

        "no such table: ${collection.name}" in message ->
            throw NoSqliteException.CollectionNotDeclared(collection.name, e)

        "UNIQUE constraint failed" in message -> {
            val index = IndexName.find(message)?.groupValues?.get(1)
            val violated = collection.indexes.firstOrNull { it.name == index }
            throw NoSqliteException.UniqueViolation(collection.name, violated, e)
        }

        else -> throw e
    }
}
