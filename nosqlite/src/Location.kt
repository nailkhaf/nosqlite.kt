package io.github.nailkhaf.nosqlite

/** Where a database lives. */
public sealed class Location {
    /** A private database that lives as long as its one connection. */
    public data object InMemory : Location()

    /**
     * A database file at [path], opened with one writer and [maxReaders] read-only connections in WAL mode. On the web,
     * which has no WAL, it gets the writer only.
     */
    public class File(public val path: String, public val maxReaders: Int = 3) : Location() {
        init {
            require(maxReaders >= 0) { "Negative maxReaders: $maxReaders" }
        }

        override fun toString(): String = "File($path)"
    }
}

/** Deletes the database files, WAL sidecars included; [NoSqlite.delete] closes the connections first. */
internal expect suspend fun Location.delete()
