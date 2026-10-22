package io.github.nailkhaf.nosqlite

/** What nosqlite throws when an operation can't complete; the transaction it was in rolls back unless caught. */
public sealed class NosqliteException(message: String, cause: Throwable? = null) : RuntimeException(message, cause) {

    /** [collection] is used with a database that doesn't declare it in `Database { }`. */
    public class CollectionNotDeclared internal constructor(public val collection: String, cause: Throwable) :
        NosqliteException("Collection '$collection' is not declared, add +$collection to Database { }", cause)

    /** A write would give two documents of [collection] the same key in a unique [index], `null` when unknown. */
    public class UniqueViolation internal constructor(
        public val collection: String,
        public val index: Index<*>?,
        cause: Throwable,
    ) : NosqliteException("Unique constraint violated in '$collection': ${index ?: cause.message}", cause)

    /** A document of [collection] can't be decoded into the requested type. */
    public class DecodingFailed internal constructor(public val collection: String, id: DocumentId, cause: Throwable) :
        NosqliteException("Can't decode $id of '$collection': ${cause.message}", cause)
}
