package io.github.nailkhaf.nosqlite

import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

internal actual fun defaultDriver(): SQLiteDriver = BundledSQLiteDriver()

internal actual val DefaultDispatcher: CoroutineDispatcher get() = Dispatchers.IO

internal actual val SupportsReaders: Boolean = true

internal actual suspend fun Location.delete() {
    when (this) {
        is Location.InMemory -> Unit

        is Location.File -> withContext(Dispatchers.IO) {
            for (suffix in listOf("", "-wal", "-shm")) {
                SystemFileSystem.delete(Path(path + suffix), mustExist = false)
            }
        }
    }
}
