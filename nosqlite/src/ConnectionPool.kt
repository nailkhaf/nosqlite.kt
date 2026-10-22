package io.github.nailkhaf.nosqlite

import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
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
internal class ConnectionPool(location: Location = Location.InMemory, driver: SQLiteDriver = BundledSQLiteDriver()) {
    init {
        require(!driver.hasConnectionPool) { "SQLiteDriver with its own connection pool is not supported" }
    }

    private val writer = Slots(1) { Connection.open(driver, location) }

    private val readers = (location as? Location.File)?.takeIf { it.maxReaders > 0 }?.let {
        Slots(it.maxReaders) { Connection.open(driver, location, queryOnly = true) }
    }

    /** Runs [block] with the write connection, waiting while another coroutine holds it. */
    suspend fun <R> withWriter(block: suspend (Connection) -> R): R = withContext(Dispatchers.IO) { writer.use(block) }

    /** Runs [block] with a read connection, or the write connection when there are no readers. */
    suspend fun <R> withReader(block: suspend (Connection) -> R): R =
        withContext(Dispatchers.IO) { (readers ?: writer).use(block) }

    /** Waits for every connection to be handed back and closes the ones that were opened. */
    suspend fun close(): Unit = coroutineScope {
        launch { writer.close() }
        readers?.let { launch { it.close() } }
    }
}

/**
 * Up to [size] connections handed to one coroutine at a time. An idle one is reused before another is opened, so
 * connections are opened only as far as coroutines need them at the same time.
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
            withContext(NonCancellable) { lock.withLock { idle.addLast(connection) } }
        }
    }

    /**
     * Waits for every connection to come back, then closes them all. Afterwards [use] fails and closing again does
     * nothing; the permits are handed back so that waiting callers fail instead of waiting forever.
     */
    suspend fun close() {
        var acquired = 0
        try {
            repeat(size) {
                permits.acquire()
                acquired++
            }
            lock.withLock {
                if (!closed) {
                    closed = true
                    idle.forEach { it.close() }
                    idle.clear()
                }
            }
        } finally {
            repeat(acquired) { permits.release() }
        }
    }
}
