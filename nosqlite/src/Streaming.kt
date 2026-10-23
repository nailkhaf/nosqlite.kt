package io.github.nailkhaf.nosqlite

import androidx.sqlite.SQLiteStatement
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Runs [action] on every document [query] selects, reading one row at a time from an open cursor, so a large result
 * never sits in memory at once. The read transaction stays open until the last document, and no write can be
 * checkpointed meanwhile, so do slow work after the loop, not in [action].
 */
context(_: ReadScope)
public suspend fun <T> Collection<T>.forEach(query: QueryScope.() -> Unit = {}, action: suspend (T) -> Unit) {
    val query = query(query)
    val select = Queries.selectSql(this, query)
    withConnection {
        select.forEachRow({ decode(this) }) { action(it) }
    }
}

// Connection.statement keeps the statement out of the cache while the cursor is open, so the block can run queries.
context(conn: Connection)
private suspend fun <R> Fragment.forEachRow(read: SQLiteStatement.() -> R, block: suspend (R) -> Unit) {
    conn.statement(sql) { statement ->
        conn.exclusive { statement.bind(params) }
        while (true) {
            currentCoroutineContext().ensureActive()
            val next = conn.exclusive { if (statement.step()) statement.read() else null } ?: break
            block(next)
        }
    }
}
