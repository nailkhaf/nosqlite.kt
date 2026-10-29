package io.github.nailkhaf.nosqlite

import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * One write connection and, for a file, [Location.File.maxReaders] `query_only` read connections in WAL mode.
 * Each is opened when first needed and handed to one coroutine at a time; an in-memory database has no readers,
 * its one connection serves both.
 */
internal class ConnectionPool(
    location: Location = Location.InMemory,
    driver: SQLiteDriver = BundledSQLiteDriver(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    init {
        require(!driver.hasConnectionPool) { "SQLiteDriver with its own connection pool is not supported" }
    }

    private val writer = Slots(1) { Connection.open(driver, location) }

    private val readers = (location as? Location.File)?.takeIf { it.maxReaders > 0 }?.let {
        Slots(it.maxReaders) { Connection.open(driver, location, queryOnly = true) }
    }

    /** Runs [block] with the write connection, waiting while another coroutine holds it. */
    suspend fun <R> withWriter(block: suspend (Connection) -> R): R = withContext(dispatcher) { writer.use(block) }

    /** Runs [block] with a read connection, or the write connection when there are no readers. */
    suspend fun <R> withReader(block: suspend (Connection) -> R): R =
        withContext(dispatcher) { (readers ?: writer).use(block) }

    suspend fun close() {
        writer.close()
        readers?.close()
    }
}

/**
 * Up to [size] connections handed to one coroutine at a time. An idle one is reused before another is opened, so
 * connections are opened only as far as coroutines need them at the same time. One returned while a transaction is
 * still open is rolled back, and closed if that fails, so it is never handed out mid-transaction.
 */
private class Slots(private val size: Int, private val open: () -> Connection) {
    private val permits = Semaphore(size)
    private val lock = Mutex()
    private val idle = ArrayDeque<Connection>()
    private var closed = false

    suspend fun <R> use(block: suspend (Connection) -> R): R = permits.withPermit {
        val connection = lock.withLock {
            check(!closed) { "Database is closed" }
            idle.removeLastOrNull()
        } ?: open()
        try {
            block(connection)
        } finally {
            // NonCancellable so a cancelled caller still gets a real ROLLBACK: statement() refuses one.
            // A transaction that survives it is closed with the connection instead of returned to the pool.
            withContext(NonCancellable) {
                val reusable = connection.rollbackIfOpen()
                lock.withLock {
                    when {
                        !reusable -> connection.closeQuietly()
                        closed -> connection.close()
                        else -> idle.addLast(connection)
                    }
                }
            }
        }
    }

    /** Closes the idle connections; one in use closes when it comes back, and [use] fails from now on. */
    suspend fun close(): Unit = lock.withLock {
        closed = true
        idle.forEach { it.close() }
        idle.clear()
    }
}

/** Closes a connection that can't be reused. A close error must not replace the caller's exception. */
private suspend fun Connection.closeQuietly() {
    try {
        close()
    } catch (_: Exception) {
        // Already unusable, and not going back to the pool.
    }
}
