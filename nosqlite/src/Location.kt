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
     * A database file, opened with one writer and [maxReaders] read-only connections in WAL mode.
     *
     * [path] is produced by [producePath] on first use — when a connection opens or [Location.delete] runs —
     * and cached so the writer, readers and delete share one path. Pass a lambda to defer resolution
     * (for example Android `filesDir`) until IO; a [String] is accepted too.
     */
    public class File(
        public val maxReaders: Int = 3,
        producePath: () -> String,
    ) : Location() {
        /** A database file at a known [path]. */
        public constructor(path: String, maxReaders: Int = 3) : this(maxReaders, { path })

        init {
            require(maxReaders >= 0) { "Negative maxReaders: $maxReaders" }
        }

        private val producePath = producePath
        private val resolvedPath = lazy { producePath() }

        /** File path, resolved once on first use and shared by open, readers and delete. */
        public val path: String get() = resolvedPath.value

        override fun toString(): String =
            if (resolvedPath.isInitialized()) "File(${resolvedPath.value})" else "File(<deferred>)"
    }
}

/** Deletes the database files, WAL sidecars included; [NoSqlite.delete] closes the connections first. */
internal suspend fun Location.delete() {
    when (this) {
        is Location.InMemory -> Unit

        is Location.File -> withContext(Dispatchers.IO) {
            for (suffix in listOf("", "-wal", "-shm")) {
                SystemFileSystem.delete(Path("$path$suffix"), mustExist = false)
            }
        }
    }
}
