package io.github.nailkhaf.nosqlite

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/** Where a database lives. */
public sealed class Location {
    /** A private database that lives as long as its one connection. */
    public data object InMemory : Location()

    /**
     * A database file at [path], opened with one writer and [maxReaders] read-only connections in WAL mode.
     * [path] is a producer so callers can defer resolution (for example Android `filesDir`) until a connection
     * opens on [Dispatchers.IO].
     */
    public data class File(
        public val maxReaders: Int = 3,
        public val path: () -> String,
    ) : Location() {
        init {
            require(maxReaders >= 0) { "Negative maxReaders: $maxReaders" }
        }
    }
}

/** Deletes the database files, WAL sidecars included; [NoSqlite.delete] closes the connections first. */
internal suspend fun Location.delete() {
    when (this) {
        is Location.InMemory -> Unit

        is Location.File -> withContext(Dispatchers.IO) {
            val file = path()
            for (suffix in listOf("", "-wal", "-shm")) {
                SystemFileSystem.delete(Path("$file$suffix"), mustExist = false)
            }
        }
    }
}
