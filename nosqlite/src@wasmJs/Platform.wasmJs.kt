package io.github.nailkhaf.nosqlite

import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.web.WebWorkerSQLiteDriver
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.w3c.dom.Worker

// One worker serves every database, as OPFS lets only one of them hold a file open.
private val sharedDriver by lazy { WebWorkerSQLiteDriver(nosqliteWorker()) }

@OptIn(ExperimentalWasmJsInterop::class)
private fun nosqliteWorker(): Worker =
    js("new Worker(new URL('nosqlite-worker.js', import.meta.url), { type: 'module' })")

internal actual fun defaultDriver(): SQLiteDriver = sharedDriver

internal actual val DefaultDispatcher: CoroutineDispatcher get() = Dispatchers.Default

internal actual val SupportsReaders: Boolean = false

// The worker owns the files and its protocol has no delete.
internal actual suspend fun Location.delete() {
    when (this) {
        is Location.InMemory -> Unit
        is Location.File -> throw UnsupportedOperationException("Deleting a database file is not supported on the web")
    }
}
