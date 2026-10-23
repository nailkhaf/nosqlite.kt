package io.github.nailkhaf.nosqlite

import androidx.sqlite.SQLiteException
import androidx.sqlite.SQLiteStatement
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.json.JsonObject
import kotlin.reflect.KProperty1

/**
 * A named set of [T] documents, stored as JSON in its own table. Create one directly, or extend it to declare
 * indexes, and list it in `NoSqlite { }`:
 * ```
 * val Notes = Collection("notes", Note.serializer())
 *
 * object Users : Collection<User>("users", User.serializer()) {
 *     val byEmail = unique("email")
 *     val byAge = index("age")
 *     val byName = index("name.last", "name.first")
 * }
 *
 * val db = NoSqlite("app.db") {
 *     +Notes
 *     +Users
 * }
 * ```
 */
public open class Collection<T>(public val name: String, internal val serializer: KSerializer<T>) {
    init {
        requireCollectionName(name)
    }

    internal val indexes: List<Index<T>>
        field = mutableListOf()

    /** A unique index on [fields]: documents can't share their values; with [where] only matching ones count. */
    protected fun unique(vararg fields: String, where: (WhereScope.() -> Where)? = null): UniqueIndex<T> =
        register(UniqueIndex(this, fields.map(::Path), where?.let(::where)))

    /** An index on [fields], in order; with [where] only matching documents are indexed. */
    protected fun index(vararg fields: String, where: (WhereScope.() -> Where)? = null): Index<T> =
        register(Index(this, fields.map(::Path), isUnique = false, where?.let(::where)))

    /** A unique index on property [fields]; each must be a serial field of [T] under its property name. */
    protected fun unique(vararg fields: Field<T, *>, where: (WhereScope.() -> Where)? = null): UniqueIndex<T> =
        unique(*fields.map { it.checkedPath() }.toTypedArray(), where = where)

    protected fun unique(vararg properties: KProperty1<T, *>, where: (WhereScope.() -> Where)? = null): UniqueIndex<T> =
        unique(*properties.map { it.field }.toTypedArray(), where = where)

    /** An index on property [fields]; each must be a serial field of [T] under its property name. */
    protected fun index(vararg fields: Field<T, *>, where: (WhereScope.() -> Where)? = null): Index<T> =
        index(*fields.map { it.checkedPath() }.toTypedArray(), where = where)

    protected fun index(vararg properties: KProperty1<T, *>, where: (WhereScope.() -> Where)? = null): Index<T> =
        index(*properties.map { it.field }.toTypedArray(), where = where)

    // A property renamed with @SerialName is stored under its serial name, which a property reference can't name.
    private fun Field<T, *>.checkedPath(): String {
        keys.fold(serializer.descriptor) { descriptor, key ->
            val index = descriptor.getElementIndex(key)
            require(index != CompositeDecoder.UNKNOWN_NAME) {
                "'$this' is not a serial field of '$name': name a field renamed with @SerialName by its serial name"
            }
            descriptor.getElementDescriptor(index)
        }
        return path
    }

    private fun <I : Index<T>> register(index: I): I {
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
public suspend fun <T> Collection<T>.insert(value: T) {
    val json = encode(value)
    withConnection { Queries.insert(this@insert).execute(json) }
    changed()
}

/** The documents [query] selects; without one, every document in the order they were inserted. */
context(_: ReadScope)
public suspend fun <T> Collection<T>.list(query: QueryScope.() -> Unit = {}): List<T> = list(query(query))

context(_: ReadScope)
internal suspend fun <T> Collection<T>.list(query: Query): List<T> =
    withConnection { Queries.select(this@list, query).execute() }

/** How many documents match [condition], or all of them without one. */
context(_: ReadScope)
public suspend fun Collection<*>.count(condition: (WhereScope.() -> Where)? = null): Long =
    withConnection { Queries.count(this@count, condition?.let(::where)).execute() }

/**
 * Replaces every document matching [condition] with [value], so match on a unique field to replace one.
 * The result is how many documents were replaced.
 */
context(_: WriteScope)
public suspend fun <T> Collection<T>.update(value: T, condition: WhereScope.() -> Where): Int {
    val json = encode(value)
    return withConnection { Queries.update(this@update, json, where(condition)).execute() }.changed(this)
}

/** Deletes every document matching [condition]; the result is how many were deleted. */
context(_: WriteScope)
public suspend fun Collection<*>.delete(condition: WhereScope.() -> Where): Int =
    withConnection { Queries.delete(this@delete, where(condition)).execute() }.changed(this)

/**
 * Applies the JSON merge [patch] to every document matching [condition]: its fields replace theirs, and a `null`
 * removes one. The result is how many documents were patched.
 */
context(_: WriteScope)
public suspend fun Collection<JsonObject>.patch(patch: JsonObject, condition: WhereScope.() -> Where): Int =
    withConnection { Queries.patch(this@patch, patch.toString(), where(condition)).execute() }.changed(this)

/** The number of documents a write touched, recording a change when there were any. */
context(_: WriteScope)
private fun Int.changed(collection: Collection<*>): Int {
    if (this > 0) collection.changed()
    return this
}

// Collection names are SQL identifiers, so they are part of the SQL text, quoted; everything else is bound.
internal val Collection<*>.table: String get() = "\"$name\""

internal fun <T> Collection<T>.encode(value: T): String {
    val json = NoSqlite.Json.encodeToJsonElement(serializer, value)
    require(json is JsonObject) { "A document of '$name' must be a JSON object, not $json" }
    return json.toString()
}

/** The document in the current row of a [Queries.selectSql] statement. */
internal fun <T> Collection<T>.decode(row: SQLiteStatement): T = try {
    NoSqlite.Json.decodeFromString(serializer, row.getText(1))
} catch (e: SerializationException) {
    throw NoSqliteException.DecodingFailed(name, row.getLong(0), e)
}

/** Runs [block] on the transaction's connection, with SQLite's errors about this collection made typed. */
context(_: ReadScope)
internal suspend fun <R> Collection<*>.withConnection(block: suspend context(Connection) () -> R): R =
    mapErrors { context(connection()) { block() } }

private val IndexName = Regex("index '([^']+)'")

/**
 * Only declared collections have a table, so SQLite not knowing this collection's table means it wasn't declared.
 * A unique constraint error names the index only when it is an expression index, which all of ours are.
 */
internal inline fun <R> Collection<*>.mapErrors(block: () -> R): R = try {
    block()
} catch (e: SQLiteException) {
    val message = e.message.orEmpty()
    when {
        "no such table: $name" in message -> throw NoSqliteException.CollectionNotDeclared(name, e)

        "UNIQUE constraint failed" in message -> {
            val index = IndexName.find(message)?.groupValues?.get(1)
            throw NoSqliteException.UniqueViolation(name, indexes.firstOrNull { it.name == index }, e)
        }

        else -> throw e
    }
}
