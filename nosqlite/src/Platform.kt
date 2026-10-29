package io.github.nailkhaf.nosqlite

import androidx.sqlite.SQLiteDriver
import kotlinx.coroutines.CoroutineDispatcher

internal expect fun defaultDriver(): SQLiteDriver

internal expect val DefaultDispatcher: CoroutineDispatcher

// Read connections need WAL, which SQLite's web build doesn't have.
internal expect val SupportsReaders: Boolean
