package io.github.nailkhaf.nosqlite

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.execSQL
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * One SQLite connection and the prepared statements it keeps for reuse. SQLite is called through [exclusive], so two
 * coroutines reaching the connection at the same time fail instead of crashing the process.
 */
internal class Connection(private val sqlite: SQLiteConnection) {
    private val statements = StatementCache()
    private val busy = atomic(false)

    /**
     * Runs [block] with a prepared statement for [sql], reused across calls. While [block] runs the statement is out
     * of the cache, so a nested call with the same SQL gets its own; one whose block throws is closed, not reused.
     */
    suspend fun <R> statement(sql: String, block: suspend (SQLiteStatement) -> R): R {
        currentCoroutineContext().ensureActive()
        val statement = statements.take(sql) ?: exclusive { sqlite.prepare(sql) }
        val result = try {
            block(statement)
        } catch (e: Throwable) {
            exclusive { statement.close() }
            throw e
        }
        exclusive {
            statement.reset()
            statement.clearBindings()
        }
        statements.put(sql, statement)
        return result
    }

    /** Runs [block], which calls SQLite, while no other coroutine does; a second one arriving meanwhile fails. */
    fun <R> exclusive(block: () -> R): R {
        check(busy.compareAndSet(expect = false, update = true)) {
            "Connection is used by two coroutines at once, don't start coroutines inside read { } or write { }"
        }
        try {
            return block()
        } finally {
            busy.value = false
        }
    }

    fun inTransaction(): Boolean = exclusive { sqlite.inTransaction() }

    suspend fun close() {
        statements.close()
        exclusive { sqlite.close() }
    }

    companion object {
        /** Opens [location]; a [queryOnly] connection refuses to write. Opening a file blocks the thread. */
        fun open(driver: SQLiteDriver, location: Location, queryOnly: Boolean = false): Connection {
            val sqlite = driver.open(
                when (location) {
                    Location.InMemory -> ":memory:"
                    is Location.File -> location.path
                },
            )
            try {
                requireSupported(sqlite.version())
                for (pragma in Pragmas) sqlite.execSQL(pragma)
                if (queryOnly) sqlite.execSQL("PRAGMA query_only = ON;")
            } catch (e: Throwable) {
                sqlite.close()
                throw e
            }
            return Connection(sqlite)
        }

        private val Pragmas = listOf(
            "PRAGMA journal_mode = WAL;",
            "PRAGMA foreign_keys = ON;",
            "PRAGMA synchronous = NORMAL;",
            "PRAGMA temp_store = MEMORY;",
            "PRAGMA busy_timeout = 5000;",
            "PRAGMA cache_size = -4000;",
            "PRAGMA journal_size_limit = 4194304;",
        )
    }
}
