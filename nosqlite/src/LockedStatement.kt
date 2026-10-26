package io.github.nailkhaf.nosqlite

import androidx.sqlite.SQLiteStatement
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A prepared statement whose every call takes its connection's [lock]. SQLite crashes when one connection is inside
 * two calls at once, which coroutines started inside `read { }` or `write { }` could cause; with the lock they take
 * turns. It is held for one call, never across user code.
 */
internal class LockedStatement(private val statement: SQLiteStatement, private val lock: Mutex) {
    suspend fun bindText(index: Int, value: String): Unit = lock.withLock { statement.bindText(index, value) }
    suspend fun bindLong(index: Int, value: Long): Unit = lock.withLock { statement.bindLong(index, value) }
    suspend fun bindDouble(index: Int, value: Double): Unit = lock.withLock { statement.bindDouble(index, value) }
    suspend fun bindNull(index: Int): Unit = lock.withLock { statement.bindNull(index) }
    suspend fun getText(index: Int): String = lock.withLock { statement.getText(index) }
    suspend fun getLong(index: Int): Long = lock.withLock { statement.getLong(index) }
    suspend fun getDouble(index: Int): Double = lock.withLock { statement.getDouble(index) }
    suspend fun getColumnCount(): Int = lock.withLock { statement.getColumnCount() }
    suspend fun isNull(index: Int): Boolean = lock.withLock { statement.isNull(index) }
    suspend fun step(): Boolean = lock.withLock { statement.step() }
    suspend fun reset(): Unit = lock.withLock { statement.reset() }
    suspend fun clearBindings(): Unit = lock.withLock { statement.clearBindings() }
    suspend fun close(): Unit = lock.withLock { statement.close() }
}
