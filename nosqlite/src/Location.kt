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

    /** A database file at [path], opened with one writer and [maxReaders] read-only connections in WAL mode. */
    public class File(public val path: String, public val maxReaders: Int = 3) : Location() {
        init {
            require(maxReaders >= 0) { "Negative maxReaders: $maxReaders" }
        }

        override fun toString(): String = "File($path)"
    }
}

/** Deletes the database files, WAL sidecars included; [NoSqlite.delete] closes the connections first. */
internal suspend fun Location.delete() {
    when (this) {
        is Location.InMemory -> Unit

        is Location.File -> withContext(Dispatchers.IO) {
            for (suffix in listOf("", "-wal", "-shm")) {
                SystemFileSystem.delete(Path(path + suffix), mustExist = false)
            }
        }
    }
}
