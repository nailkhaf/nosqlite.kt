package io.github.nailkhaf.nosqlite

import androidx.sqlite.SQLiteException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject

/**
 * A named set of [T] documents, stored as JSON in its own table. Declare one as an object with its indexes and list
 * it in `Database { }`:
 * ```
 * object Users : Collection<User>("users", User.serializer()) {
 *     val byEmail = unique("email")
 *     val byAge = index("age")
 *     val byName = index("name.last", "name.first")
 * }
 *
 * val db = Database("app.db") { +Users }
 * ```
 */
public abstract class Collection<T>(public val name: String, internal val serializer: KSerializer<T>) {
    init {
        require(name.matches(Name)) { "Invalid collection name: '$name'" }
        require(!name.startsWith("sqlite_", ignoreCase = true)) { "Collection name is reserved by SQLite: '$name'" }
    }

    private val declaredIndexes = mutableListOf<Index>()

    internal val indexes: List<Index> get() = declaredIndexes

    /** A unique index on [fields]: documents can't share their values; with [where] only matching ones count. */
    protected fun unique(vararg fields: String, where: (WhereScope.() -> Condition)? = null): Index =
        register(Index(this, fields.map(::Path), isUnique = true, where?.let(::where)))

    /** An index on [fields], in order; with [where] only matching documents are indexed. */
    protected fun index(vararg fields: String, where: (WhereScope.() -> Condition)? = null): Index =
        register(Index(this, fields.map(::Path), isUnique = false, where?.let(::where)))

    private fun register(index: Index): Index {
        require(declaredIndexes.none { it.name == index.name }) { "Collection '$name' declares $index more than once" }
        declaredIndexes += index
        return index
    }

    /** Inserts [value] as a new document; it must encode to a JSON object. */
    context(_: WriteScope)
    public suspend fun insert(value: T) {
        val json = encode(value)
        withConnection { Queries.insert(this@Collection).execute(json) }
    }

    /**
     * Up to [limit] documents matching [condition], or any documents without one, in the order they were inserted,
     * skipping the first [offset].
     */
    context(_: ReadScope)
    public suspend fun list(limit: Int, offset: Int = 0, condition: (WhereScope.() -> Condition)? = null): List<T> {
        require(limit > 0) { "Limit must be positive: $limit" }
        require(offset >= 0) { "Offset must not be negative: $offset" }
        return withConnection {
            Queries.select(this@Collection, condition?.let(::where), limit, offset).execute().map { decode(it) }
        }
    }

    internal fun encode(value: T): String {
        val json = Database.Json.encodeToJsonElement(serializer, value)
        require(json is JsonObject) { "A document of '$name' must be a JSON object, not $json" }
        return json.toString()
    }

    internal fun decode(row: Row): T = try {
        Database.Json.decodeFromString(serializer, row.json)
    } catch (e: SerializationException) {
        throw NosqliteException.DecodingFailed(name, row.id, e)
    }

    override fun toString(): String = "Collection($name)"

    // Collection names are SQL identifiers, so they are part of the SQL text, quoted; everything else is bound.
    internal val table: String get() = "\"$name\""

    private companion object {
        val Name = Regex("[A-Za-z_][A-Za-z0-9_]*")
    }
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
        "no such table: $name" in message -> throw NosqliteException.CollectionNotDeclared(name, e)

        "UNIQUE constraint failed" in message -> {
            val index = IndexName.find(message)?.groupValues?.get(1)
            throw NosqliteException.UniqueViolation(name, indexes.firstOrNull { it.name == index }, e)
        }

        else -> throw e
    }
}
