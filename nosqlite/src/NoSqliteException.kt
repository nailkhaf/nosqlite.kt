package io.github.nailkhaf.nosqlite

/** What nosqlite throws when an operation can't complete; the transaction it was in rolls back unless caught. */
public sealed class NoSqliteException(message: String, cause: Throwable? = null) : RuntimeException(message, cause) {

    /** [collection] is used with a database that doesn't declare it in `NoSqlite { }`. */
    public class CollectionNotDeclared internal constructor(public val collection: String, cause: Throwable) :
        NoSqliteException("Collection '$collection' is not declared, add +$collection to NoSqlite { }", cause)

    /** A write would give two documents of [collection] the same key in a unique [index], `null` when unknown. */
    public class UniqueViolation internal constructor(
        public val collection: String,
        public val index: Index?,
        cause: Throwable,
    ) : NoSqliteException("Unique constraint violated in '$collection': ${index ?: cause.message}", cause)

    /** A document of [collection] can't be decoded into the requested type. */
    public class DecodingFailed internal constructor(public val collection: String, cause: Throwable) :
        NoSqliteException("Can't decode a document of '$collection': ${cause.message}", cause)

    /** A table named [collection] exists with [columns] instead of a collection's, so something else created it. */
    public class IncompatibleTable internal constructor(public val collection: String, public val columns: List<String>) :
        NoSqliteException(
            "Table '$collection' has columns (${columns.joinToString()}), " +
                "not a collection's (${Queries.TableColumns.joinToString()})",
        )

    /** The driver's SQLite is [version], older than 3.45.0, the first with the JSONB functions storage needs. */
    public class UnsupportedSqlite internal constructor(public val version: String) :
        NoSqliteException("SQLite $version is older than $MinSqliteVersion; use BundledSQLiteDriver")

    /**
     * Another connection to the same file, from another [NoSqlite] or process, kept it locked longer than the 5 s
     * busy timeout. Nothing was changed, so the operation can be retried.
     */
    public class Busy internal constructor(cause: Throwable) :
        NoSqliteException("Database is locked by another connection: ${cause.message}", cause)
}
