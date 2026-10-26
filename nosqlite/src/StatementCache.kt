package io.github.nailkhaf.nosqlite

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Prepared statements by SQL, least recently used first: [take] removes a statement while it is in use, [put]
 * resets it and adds it back at the end, closing the oldest one beyond [capacity].
 */
internal class StatementCache(private val capacity: Int = 64) {
    private val statements = LinkedHashMap<String, Statement>()
    private val lock = Mutex()

    init {
        require(capacity > 0) { "Capacity must be positive: $capacity" }
    }

    suspend fun take(sql: String): Statement? = lock.withLock { statements.remove(sql) }

    /** Caches [statement] for [sql]; when one is already cached, [statement] is closed instead. */
    suspend fun put(sql: String, statement: Statement) {
        statement.reset()
        statement.clearBindings()
        val dropped = lock.withLock {
            if (sql in statements) return@withLock statement
            statements[sql] = statement
            if (statements.size > capacity) statements.remove(statements.keys.first()) else null
        }
        dropped?.close()
    }

    suspend fun close() {
        val all = lock.withLock { statements.values.toList().also { statements.clear() } }
        all.forEach { it.close() }
    }
}
