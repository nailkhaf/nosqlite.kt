package io.github.nailkhaf.nosqlite

/** Runs [block] with a fresh database file, deleted with its sidecars afterwards where the platform can. */
internal expect suspend fun <R> withTempFile(maxReaders: Int = 3, block: suspend (Location.File) -> R): R
