package io.github.nailkhaf.nosqlite.tracking

import io.github.nailkhaf.nosqlite.Collection
import io.github.nailkhaf.nosqlite.Field
import io.github.nailkhaf.nosqlite.Index
import io.github.nailkhaf.nosqlite.Lookup
import io.github.nailkhaf.nosqlite.NoSqlite
import io.github.nailkhaf.nosqlite.QueryScope
import io.github.nailkhaf.nosqlite.ReadScope
import io.github.nailkhaf.nosqlite.SchemaBuilder
import io.github.nailkhaf.nosqlite.UniqueIndex
import io.github.nailkhaf.nosqlite.Where
import io.github.nailkhaf.nosqlite.WhereScope
import io.github.nailkhaf.nosqlite.WriteScope
import io.github.nailkhaf.nosqlite.count as readCount
import io.github.nailkhaf.nosqlite.delete as lookupDelete
import io.github.nailkhaf.nosqlite.forEach as readForEach
import io.github.nailkhaf.nosqlite.get as readGet
import io.github.nailkhaf.nosqlite.insert
import io.github.nailkhaf.nosqlite.invoke as readInvoke
import io.github.nailkhaf.nosqlite.list as readList
import io.github.nailkhaf.nosqlite.observe as readObserve
import io.github.nailkhaf.nosqlite.upsert as indexUpsert
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.serializer
import kotlin.reflect.KProperty1
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * A collection whose writes are key-only and recorded. It is not a [Collection]. The real one is private, so bulk
 * where-writes and writes through an index are not on this type. Subclasses set [id] with [primaryKey] once, then
 * any other indexes, which are read-only:
 * ```
 * object Contacts : TrackedCollection("contacts") {
 *     override val id = primaryKey(Contact::id)
 *     val byCity = index(Contact::city)
 * }
 *
 * val db = NoSqlite { +Contacts; +PendingChanges }
 * db.write { Contacts.upsert(Contact("ada", "Ada", "London")) }
 * db.write { Contacts.delete("ada") }
 * ```
 * [upsert] merges [value] into the document with its primary key, keeping fields the value does not mention.
 * [delete] removes that one document. Each inserts one [PendingChange] in the same transaction.
 * [clock] stamps those changes. The default is [Clock.System].
 */
public abstract class TrackedCollection(
    public val name: String,
    private val clock: Clock = Clock.System,
) {
    private val source = Inner(name)
    private var hasPrimaryKey: Boolean = false

    /** The primary key, declared once with [primaryKey]. [upsert] and [delete] use it. */
    public abstract val id: ReadOnlyUnique

    /**
     * A unique index on this one field, and the value of [id]. A string is the stored key, the same form as
     * `unique`: `"id"`, or `"meta.id"` for a nested object. That string is also how a `@SerialName` is written,
     * because a property reference names the property, not the serial name.
     */
    protected fun primaryKey(field: String): ReadOnlyUnique = primary(field.split('.')) { source.addUnique(field) }

    /**
     * A unique index on [property], named by its property name, the same name core gives `unique(property)`.
     * A `@SerialName` is not applied; pass that name as a string instead.
     */
    protected fun primaryKey(property: KProperty1<*, *>): ReadOnlyUnique =
        primary(listOf(property.name)) { source.addUnique(property) }

    /** A read-only unique index on [fields]; with [where], only matching documents count. */
    protected fun unique(vararg fields: String, where: (WhereScope.() -> Where)? = null): ReadOnlyUnique =
        readOnly(source.addUnique(*fields, where = where))

    /** A read-only unique index on property [fields]. */
    protected fun unique(vararg fields: Field<*, *>, where: (WhereScope.() -> Where)? = null): ReadOnlyUnique =
        readOnly(source.addUnique(*fields, where = where))

    protected fun unique(vararg properties: KProperty1<*, *>, where: (WhereScope.() -> Where)? = null): ReadOnlyUnique =
        readOnly(source.addUnique(*properties, where = where))

    /** A read-only index on [fields], in order; with [where], only matching documents are indexed. */
    protected fun index(vararg fields: String, where: (WhereScope.() -> Where)? = null): ReadOnlyIndex =
        ReadOnlyIndex(source.addIndex(*fields, where = where))

    /** A read-only index on property [fields]. */
    protected fun index(vararg fields: Field<*, *>, where: (WhereScope.() -> Where)? = null): ReadOnlyIndex =
        ReadOnlyIndex(source.addIndex(*fields, where = where))

    protected fun index(vararg properties: KProperty1<*, *>, where: (WhereScope.() -> Where)? = null): ReadOnlyIndex =
        ReadOnlyIndex(source.addIndex(*properties, where = where))

    /** Declares this collection. Declare [PendingChanges] in the same schema, or the first write rolls back. */
    context(schema: SchemaBuilder)
    public operator fun unaryPlus(): Unit {
        id.requireKeys()
        with(schema) { +source }
    }

    /** The documents [query] selects, as [T]; without one, every document in an unspecified order. */
    context(_: ReadScope)
    public suspend inline fun <reified T> list(noinline query: QueryScope.() -> Unit = {}): List<T> =
        list(serializer(), query)

    context(_: ReadScope)
    public suspend fun <T> list(serializer: KSerializer<T>, query: QueryScope.() -> Unit = {}): List<T> =
        source.readList(serializer, query)

    /** How many documents match [condition], or all of them without one. */
    context(_: ReadScope)
    public suspend fun count(condition: (WhereScope.() -> Where)? = null): Long = source.readCount(condition)

    /** Runs [action] on every document [query] selects, one row at a time. */
    context(_: ReadScope)
    public suspend inline fun <reified T> forEach(
        noinline query: QueryScope.() -> Unit = {},
        noinline action: suspend (T) -> Unit,
    ): Unit = forEach(serializer(), query, action)

    context(_: ReadScope)
    public suspend fun <T> forEach(
        serializer: KSerializer<T>,
        query: QueryScope.() -> Unit = {},
        action: suspend (T) -> Unit,
    ): Unit = source.readForEach(serializer, query, action)

    /** The documents [query] selects, now and after every committed write that changed this collection. */
    context(_: NoSqlite)
    public inline fun <reified T> observe(noinline query: QueryScope.() -> Unit = {}): Flow<List<T>> =
        observe(serializer(), query)

    context(_: NoSqlite)
    public fun <T> observe(serializer: KSerializer<T>, query: QueryScope.() -> Unit = {}): Flow<List<T>> =
        source.readObserve(serializer, query)

    /**
     * Inserts [value], or merges it into the document that has its primary key, keeping fields it does not mention.
     * Records the encoded value, which must contain the primary key.
     */
    context(_: WriteScope)
    public suspend inline fun <reified T> upsert(value: T): Unit = upsert(serializer(), value)

    context(_: WriteScope)
    public suspend fun <T> upsert(serializer: KSerializer<T>, value: T) {
        val keys = id.requireKeys()
        val encoded = NoSqlite.Json.encodeToJsonElement(serializer, value)
        require(encoded is JsonObject) { "A document of '$name' must be a JSON object, not $encoded" }
        require(encoded.has(keys)) {
            "Collection '$name' upsert is missing primary key '${keys.joinToString(".")}'"
        }
        id.index.indexUpsert(serializer, value)
        if (!isUntracked()) record(PendingChange.Action.Upsert, encoded)
    }

    /** Deletes the document with this primary key. Records that key when a document was deleted. */
    context(_: WriteScope)
    public suspend fun delete(key: String): Boolean = deleteByKey(key)

    /** Deletes the document with this primary key. Records that key when a document was deleted. */
    context(_: WriteScope)
    public suspend fun delete(key: Long): Boolean = deleteByKey(key)

    /** Deletes the document with this primary key. Records that key when a document was deleted. */
    context(_: WriteScope)
    public suspend fun delete(key: Double): Boolean = deleteByKey(key)

    /** Deletes the document with this primary key. Records that key when a document was deleted. */
    context(_: WriteScope)
    public suspend fun delete(key: Boolean): Boolean = deleteByKey(key)

    context(_: WriteScope)
    private suspend fun deleteByKey(key: Any): Boolean {
        val keys = id.requireKeys()
        val lookup: Lookup.Unique = id.index.readInvoke(key)
        val deleted = lookup.lookupDelete()
        if (deleted && !isUntracked()) record(PendingChange.Action.Delete, keys.changeSet(key))
        return deleted
    }

    context(_: WriteScope)
    private suspend fun record(action: PendingChange.Action, changeSet: JsonObject) {
        PendingChanges.insert(
            PendingChange(
                id = Uuid.generateV7(),
                collection = name,
                action = action,
                changeSet = changeSet,
                timestamp = clock.now(),
            ),
        )
    }

    private fun primary(keys: List<String>, create: () -> UniqueIndex): ReadOnlyUnique {
        check(!hasPrimaryKey) { "Collection '$name' already has a primary key" }
        hasPrimaryKey = true
        return ReadOnlyUnique(create(), keys)
    }

    private fun readOnly(index: UniqueIndex): ReadOnlyUnique = ReadOnlyUnique(index, keys = null)

    override fun toString(): String = "TrackedCollection($name)"

    private class Inner(name: String) : Collection(name) {
        fun addUnique(vararg fields: String, where: (WhereScope.() -> Where)? = null): UniqueIndex =
            unique(*fields, where = where)

        fun addUnique(vararg fields: Field<*, *>, where: (WhereScope.() -> Where)? = null): UniqueIndex =
            unique(*fields, where = where)

        fun addUnique(vararg properties: KProperty1<*, *>, where: (WhereScope.() -> Where)? = null): UniqueIndex =
            unique(*properties, where = where)

        fun addIndex(vararg fields: String, where: (WhereScope.() -> Where)? = null): Index =
            index(*fields, where = where)

        fun addIndex(vararg fields: Field<*, *>, where: (WhereScope.() -> Where)? = null): Index =
            index(*fields, where = where)

        fun addIndex(vararg properties: KProperty1<*, *>, where: (WhereScope.() -> Where)? = null): Index =
            index(*properties, where = where)
    }
}

/**
 * A unique index with no writes. Call it with every field's value to read the one document.
 * The primary key remembers the stored field names; other unique indexes do not.
 */
public class ReadOnlyUnique internal constructor(
    internal val index: UniqueIndex,
    private val keys: List<String>?,
) {
    public operator fun invoke(vararg values: Any?): ReadOnlyOne = ReadOnlyOne(index.readInvoke(*values))

    internal fun requireKeys(): List<String> =
        keys ?: throw IllegalStateException("override val id must be primaryKey(...), not unique(...).")
}

/** The document a unique index found. */
public class ReadOnlyOne internal constructor(private val lookup: Lookup.Unique) {
    context(_: ReadScope)
    public suspend inline fun <reified T> get(): T? = get(serializer())

    context(_: ReadScope)
    public suspend fun <T> get(serializer: KSerializer<T>): T? = lookup.readGet(serializer)

    context(_: NoSqlite)
    public inline fun <reified T> observe(): Flow<T?> = observe(serializer())

    context(_: NoSqlite)
    public fun <T> observe(serializer: KSerializer<T>): Flow<T?> = lookup.readObserve(serializer)
}

/** An index with no writes. Call it with leading field values to read the matching documents. */
public class ReadOnlyIndex internal constructor(private val index: Index) {
    public operator fun invoke(vararg values: Any?): ReadOnlyMany = ReadOnlyMany(index.readInvoke(*values))
}

/** The documents an index found. */
public class ReadOnlyMany internal constructor(private val lookup: Lookup.Many) {
    context(_: ReadScope)
    public suspend inline fun <reified T> list(noinline query: QueryScope.() -> Unit = {}): List<T> =
        list(serializer(), query)

    context(_: ReadScope)
    public suspend fun <T> list(serializer: KSerializer<T>, query: QueryScope.() -> Unit = {}): List<T> =
        lookup.readList(serializer, query)

    context(_: ReadScope)
    public suspend fun count(): Long = lookup.readCount()

    context(_: NoSqlite)
    public inline fun <reified T> observe(noinline query: QueryScope.() -> Unit = {}): Flow<List<T>> =
        observe(serializer(), query)

    context(_: NoSqlite)
    public fun <T> observe(serializer: KSerializer<T>, query: QueryScope.() -> Unit = {}): Flow<List<T>> =
        lookup.readObserve(serializer, query)
}

private fun JsonObject.has(keys: List<String>): Boolean {
    var current: JsonElement = this
    for (key in keys) {
        val child = (current as? JsonObject)?.get(key) ?: return false
        if (child is JsonNull) return false
        current = child
    }
    return true
}

private fun List<String>.changeSet(key: Any): JsonObject {
    var element: JsonElement = when (key) {
        is String -> JsonPrimitive(key)
        is Boolean -> JsonPrimitive(key)
        is Long -> JsonPrimitive(key)
        is Double -> JsonPrimitive(key)
        else -> throw IllegalArgumentException(
            "Primary key must be a string, long, double or boolean, not ${key::class.simpleName}",
        )
    }
    for (part in asReversed()) element = JsonObject(mapOf(part to element))
    return element as JsonObject
}
