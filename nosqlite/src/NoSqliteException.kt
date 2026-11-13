package io.github.nailkhaf.nosqlite

/** What nosqlite throws when an operation can't complete; the transaction it was in rolls back unless caught. */
public sealed class NoSqliteException(message: String, cause: Throwable? = null) : RuntimeException(message, cause) {

    /** [collection] is used with a database that doesn't declare it in `NoSqlite { }`. */
    public class CollectionNotDeclared internal constructor(public val collection: String, cause: Throwable) :
        NoSqliteException("Collection '$collection' is not declared, add +$collection to NoSqlite { }", cause)

    /** Two documents of [collection] would share an id. */
    public class DuplicateId internal constructor(public val collection: String, cause: Throwable) :
        NoSqliteException("Duplicate id in '$collection'", cause)

    /** A write would change the id of a document in [collection]. An id is permanent. */
    public class ImmutableId internal constructor(public val collection: String, cause: Throwable) :
        NoSqliteException("Id of a document in '$collection' is immutable", cause)

    /** A document of [collection] has no id or one that isn't a lowercase canonical UUID string. */
    public class InvalidId internal constructor(public val collection: String, cause: Throwable) :
        NoSqliteException("A document of '$collection' must have a UUID string id", cause)

    /** A document of [collection] can't be decoded into the requested type. */
    public class DecodingFailed internal constructor(public val collection: String, cause: Throwable) :
        NoSqliteException("Can't decode a document of '$collection': ${cause.message}", cause)

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
