package io.github.nailkhaf.nosqlite

import androidx.sqlite.SQLiteStatement
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer

/**
 * Runs [action] on every document [query] selects, reading one row at a time from an open cursor, so a large result
 * never sits in memory at once. [action] runs in the current transaction, one document at a time.
 */
context(_: ReadScope)
public suspend inline fun <reified T> BaseCollection.forEach(
    noinline query: QueryScope.() -> Unit = {},
    noinline action: suspend (T) -> Unit,
): Unit = forEach(serializer(), query, action)

context(_: ReadScope)
public suspend fun <T> BaseCollection.forEach(
    serializer: KSerializer<T>,
    query: QueryScope.() -> Unit = {},
    action: suspend (T) -> Unit,
) {
    val query = query(query)
    val select = Queries.selectSql(this, query)
    withConnection {
        select.forEachRow { action(decode(serializer, this)) }
    }
}

// Connection.statement keeps the statement out of the cache while the cursor is open, so the block can run queries.
context(conn: Connection)
private suspend fun Fragment.forEachRow(block: suspend SQLiteStatement.() -> Unit) {
    conn.statement(sql) { statement ->
        statement.bind(params)
        while (statement.step()) {
            currentCoroutineContext().ensureActive()
            statement.block()
        }
    }
}
