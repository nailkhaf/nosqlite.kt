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
import kotlin.uuid.Uuid

/**
 * A collection whose writes are key-only and recorded. It is not a [Collection]: the real one is private, so bulk
 * where-writes and writes through an index are not available here. Declare exactly one primary key, then any other
 * indexes, which are read-only:
 * ```
 * object Contacts : TrackedCollection("contacts") {
 *     val byId = primaryKey(Contact::id)
 *     val byCity = index(Contact::city)
 * }
 *
 * val db = NoSqlite { +Contacts; +PendingChanges }
 * db.write { Contacts.upsert(Contact("ada", "Ada", "London")) }
 * db.write { Contacts.delete("ada") }
 * ```
 * [upsert] merges the value into the document with its primary key, keeping fields the value does not mention.
 * [delete] removes that one document. Each of them inserts one [PendingChange] in the same transaction.
 */
public abstract class TrackedCollection(public val name: String) {
    private val source = Inner(name)
    private var declared: DeclaredKey? = null

    /** A unique index on this one field, and the key of [upsert] and [delete]. Declare it once. */
    protected fun primaryKey(field: String): TrackedUnique =
        declare(field.split('.')) { source.addUnique(field) }

    /** A unique index on [property], named by its property name, and the key of [upsert] and [delete]. */
    protected fun primaryKey(property: KProperty1<*, *>): TrackedUnique =
        declare(listOf(property.name)) { source.addUnique(property) }

    /** A unique index on a nested [field], and the key of [upsert] and [delete]. */
    protected fun primaryKey(field: Field<*, *>): TrackedUnique =
        declare(field.toString().split('.')) { source.addUnique(field) }

    /** A read-only unique index on [fields]; with [where], only matching documents count. */
    protected fun unique(vararg fields: String, where: (WhereScope.() -> Where)? = null): TrackedUnique =
        TrackedUnique(source.addUnique(*fields, where = where))

    /** A read-only unique index on property [fields]. */
    protected fun unique(vararg fields: Field<*, *>, where: (WhereScope.() -> Where)? = null): TrackedUnique =
        TrackedUnique(source.addUnique(*fields, where = where))

    protected fun unique(vararg properties: KProperty1<*, *>, where: (WhereScope.() -> Where)? = null): TrackedUnique =
        TrackedUnique(source.addUnique(*properties, where = where))

    /** A read-only index on [fields], in order; with [where], only matching documents are indexed. */
    protected fun index(vararg fields: String, where: (WhereScope.() -> Where)? = null): TrackedIndex =
        TrackedIndex(source.addIndex(*fields, where = where))

    /** A read-only index on property [fields]. */
    protected fun index(vararg fields: Field<*, *>, where: (WhereScope.() -> Where)? = null): TrackedIndex =
        TrackedIndex(source.addIndex(*fields, where = where))

    protected fun index(vararg properties: KProperty1<*, *>, where: (WhereScope.() -> Where)? = null): TrackedIndex =
        TrackedIndex(source.addIndex(*properties, where = where))

    /** Declares this collection. Declare [PendingChanges] in the same schema, or the first write rolls back. */
    context(schema: SchemaBuilder)
    public operator fun unaryPlus(): Unit {
        declaredKey()
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
        val primary = declaredKey()
        val encoded = NoSqlite.Json.encodeToJsonElement(serializer, value)
        require(encoded is JsonObject) { "A document of '$name' must be a JSON object, not $encoded" }
        require(encoded.has(primary.path)) {
            "Collection '$name' upsert is missing primary key '${primary.path.joinToString(".")}'"
        }
        primary.index.indexUpsert(serializer, value)
        if (!isApplyingRemote()) record(PendingChange.Action.Upsert, encoded)
    }

    /** Deletes the document with this primary key. Records that key when a document was deleted. */
    context(_: WriteScope)
    public suspend fun delete(key: String): Boolean = deleteByKey(key)

    /** Deletes the document with this primary key. Records that key when a document was deleted. */
    context(_: WriteScope)
    public suspend fun delete(key: Number): Boolean = deleteByKey(key)

    /** Deletes the document with this primary key. Records that key when a document was deleted. */
    context(_: WriteScope)
    public suspend fun delete(key: Boolean): Boolean = deleteByKey(key)

    context(_: WriteScope)
    private suspend fun deleteByKey(key: Any): Boolean {
        val primary = declaredKey()
        val lookup: Lookup.Unique = primary.index.readInvoke(key)
        val deleted = lookup.lookupDelete()
        if (deleted && !isApplyingRemote()) record(PendingChange.Action.Delete, primary.changeSet(key))
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
                timestamp = TrackingClock.now(),
            ),
        )
    }

    private fun declare(path: List<String>, create: () -> UniqueIndex): TrackedUnique {
        check(declared == null) { "Collection '$name' already has a primary key" }
        val index = create()
        declared = DeclaredKey(index, path)
        return TrackedUnique(index)
    }

    private fun declaredKey(): DeclaredKey =
        checkNotNull(declared) { "Collection '$name' has no primary key. Declare one with primaryKey()." }

    override fun toString(): String = "TrackedCollection($name)"

    private class DeclaredKey(val index: UniqueIndex, val path: List<String>) {
        fun changeSet(key: Any): JsonObject {
            var element: JsonElement = when (key) {
                is String -> JsonPrimitive(key)
                is Boolean -> JsonPrimitive(key)
                is Number -> JsonPrimitive(key)
                else -> throw IllegalArgumentException(
                    "Primary key must be a string, number or boolean, not ${key::class.simpleName}",
                )
            }
            for (part in path.asReversed()) element = JsonObject(mapOf(part to element))
            return element as JsonObject
        }
    }

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

/** A read-only unique index. Call it with every field's value to read the one document. */
public class TrackedUnique internal constructor(private val index: UniqueIndex) {
    public operator fun invoke(vararg values: Any?): TrackedOne = TrackedOne(index.readInvoke(*values))
}

/** The document a unique index found. */
public class TrackedOne internal constructor(private val lookup: Lookup.Unique) {
    context(_: ReadScope)
    public suspend inline fun <reified T> get(): T? = get(serializer())

    context(_: ReadScope)
    public suspend fun <T> get(serializer: KSerializer<T>): T? = lookup.readGet(serializer)

    context(_: NoSqlite)
    public inline fun <reified T> observe(): Flow<T?> = observe(serializer())

    context(_: NoSqlite)
    public fun <T> observe(serializer: KSerializer<T>): Flow<T?> = lookup.readObserve(serializer)
}

/** A read-only index. Call it with leading field values to read the matching documents. */
public class TrackedIndex internal constructor(private val index: Index) {
    public operator fun invoke(vararg values: Any?): TrackedMany = TrackedMany(index.readInvoke(*values))
}

/** The documents an index found. */
public class TrackedMany internal constructor(private val lookup: Lookup.Many) {
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

private fun JsonObject.has(path: List<String>): Boolean {
    var current: JsonElement = this
    for (key in path) {
        val child = (current as? JsonObject)?.get(key) ?: return false
        if (child is JsonNull) return false
        current = child
    }
    return true
}
