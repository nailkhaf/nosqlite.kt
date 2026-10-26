package io.github.nailkhaf.nosqlite

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import kotlinx.atomicfu.locks.ReentrantLock
import kotlinx.atomicfu.locks.withLock

/**
 * A connection whose native calls, its statements' included, run one at a time. SQLite crashes when one connection
 * is inside two calls at once, which coroutines started inside `read { }` or `write { }` could cause; with the lock
 * they take turns. The lock is held for one call, never across user code.
 */
internal class LockedConnection(private val connection: SQLiteConnection) : SQLiteConnection {
    private val lock = ReentrantLock()

    override fun prepare(sql: String): SQLiteStatement = lock.withLock { LockedStatement(connection.prepare(sql), lock) }
    override fun inTransaction(): Boolean = lock.withLock { connection.inTransaction() }
    override fun close(): Unit = lock.withLock { connection.close() }
}

private class LockedStatement(private val statement: SQLiteStatement, private val lock: ReentrantLock) : SQLiteStatement {
    override fun bindBlob(index: Int, value: ByteArray): Unit = lock.withLock { statement.bindBlob(index, value) }
    override fun bindDouble(index: Int, value: Double): Unit = lock.withLock { statement.bindDouble(index, value) }
    override fun bindLong(index: Int, value: Long): Unit = lock.withLock { statement.bindLong(index, value) }
    override fun bindText(index: Int, value: String): Unit = lock.withLock { statement.bindText(index, value) }
    override fun bindNull(index: Int): Unit = lock.withLock { statement.bindNull(index) }
    override fun getBlob(index: Int): ByteArray = lock.withLock { statement.getBlob(index) }
    override fun getDouble(index: Int): Double = lock.withLock { statement.getDouble(index) }
    override fun getLong(index: Int): Long = lock.withLock { statement.getLong(index) }
    override fun getText(index: Int): String = lock.withLock { statement.getText(index) }
    override fun isNull(index: Int): Boolean = lock.withLock { statement.isNull(index) }
    override fun getColumnCount(): Int = lock.withLock { statement.getColumnCount() }
    override fun getColumnName(index: Int): String = lock.withLock { statement.getColumnName(index) }
    override fun getColumnType(index: Int): Int = lock.withLock { statement.getColumnType(index) }
    override fun step(): Boolean = lock.withLock { statement.step() }
    override fun reset(): Unit = lock.withLock { statement.reset() }
    override fun clearBindings(): Unit = lock.withLock { statement.clearBindings() }
    override fun close(): Unit = lock.withLock { statement.close() }
}
