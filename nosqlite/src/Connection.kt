package io.github.nailkhaf.nosqlite

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.execSQL
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** One SQLite connection and the prepared statements it keeps for reuse. */
internal class Connection(private val sqlite: SQLiteConnection) {
    internal val statements: StatementCache = StatementCache()

    /** A new statement that is not cached: the caller closes it. */
    suspend fun prepare(sql: String): SQLiteStatement {
        currentCoroutineContext().ensureActive()
        return sqlite.prepare(sql)
    }

    // A statement in use is out of the cache, so parallel children of one transaction never share it.
    // One whose block throws is closed, not cached: its state is unknown.
    suspend inline fun <R> withStatement(sql: String, block: (SQLiteStatement) -> R): R {
        val statement = statements.take(sql) ?: prepare(sql)
        val result = try {
            block(statement)
        } catch (e: Throwable) {
            statement.close()
            throw e
        }
        statements.put(sql, statement)
        return result
    }

    suspend fun execSQL(sql: String) {
        withStatement(sql) { it.step() }
    }

    fun inTransaction(): Boolean = sqlite.inTransaction()

    suspend fun close() {
        statements.close()
        sqlite.close()
    }

    companion object {
        /** Opens [location]; a [queryOnly] connection refuses to write. Opening a file blocks, so call it off the main thread. */
        fun open(driver: SQLiteDriver, location: Location, queryOnly: Boolean = false): Connection {
            val sqlite = driver.open(
                when (location) {
                    Location.InMemory -> ":memory:"
                    is Location.File -> location.path
                },
            )
            for (pragma in Pragmas) sqlite.execSQL(pragma)
            if (queryOnly) sqlite.execSQL("PRAGMA query_only = ON;")
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
