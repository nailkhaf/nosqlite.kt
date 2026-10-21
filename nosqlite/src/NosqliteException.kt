package io.github.nailkhaf.nosqlite

import androidx.sqlite.SQLiteException

/** What nosqlite throws when an operation can't complete; the transaction it was in rolls back unless caught. */
public sealed class NosqliteException(message: String, cause: Throwable? = null) : RuntimeException(message, cause) {

    /** [collection] is used with a database that doesn't declare it in `Database { }`. */
    public class CollectionNotDeclared internal constructor(public val collection: String) :
        NosqliteException("Collection '$collection' is not declared, add +$collection to Database { }")

    /**
     * A write would give two documents of [collection] the same key in a unique [index];
     * [index] is `null` when SQLite doesn't say which one.
     */
    public class UniqueViolation internal constructor(
        public val collection: String,
        public val index: Index?,
        cause: Throwable,
    ) : NosqliteException("Unique constraint violated in '$collection': ${index ?: cause.message}", cause)

    /** The document [id] of [collection] can't be decoded into the requested type. */
    public class DecodingFailed internal constructor(
        public val collection: String,
        public val id: DocumentId?,
        cause: Throwable,
    ) : NosqliteException("Can't decode ${id ?: "a document"} of '$collection': ${cause.message}", cause)
}

private val IndexName = Regex("index '([^']+)'")

/** Runs [block], turning SQLite's unique constraint errors into [NosqliteException.UniqueViolation]. */
internal inline fun <R> Collection.mapConstraintErrors(block: () -> R): R = try {
    block()
} catch (e: SQLiteException) {
    val message = e.message.orEmpty()
    if ("UNIQUE constraint failed" !in message) throw e
    val name = IndexName.find(message)?.groupValues?.get(1)
    throw NosqliteException.UniqueViolation(this.name, indexes.firstOrNull { it.name == name }, e)
}
