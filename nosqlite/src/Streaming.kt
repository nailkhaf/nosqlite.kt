package io.github.nailkhaf.nosqlite

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Runs [action] on every matching document, in order, reading one row at a time from an open cursor, so a large
 * result never sits in memory at once. The cursor lives inside this read transaction.
 */
context(_: ReadScope)
public suspend fun <T> Select<T>.forEach(action: suspend (T) -> Unit) {
    val fragment = Queries.selectFragment(this, limit = -1)
    val connection = collection.connection()
    // Its own statement, not a cached one: action may run other queries while the cursor is open.
    connection.prepare(fragment.sql).use { statement ->
        statement.bind(fragment.params)
        while (statement.step()) {
            currentCoroutineContext().ensureActive()
            action(decode(statement.row(keys = 0)))
        }
    }
}

/** One page of a [pages] flow. */
public class Page<T> internal constructor(
    /** The documents on this page, at most the page size and never none. */
    public val items: List<T>,
    /** The page's position, counting from 0. */
    public val number: Int,
    /** Whether this is the last page. */
    public val isLast: Boolean,
) {
    override fun toString(): String = "Page($number, ${items.size} items${if (isLast) ", last" else ""})"
}

/**
 * The matching documents of [db] in pages of [size], each read in its own short transaction. Pages continue after
 * the last document of the previous page by its sort keys and id (keyset pagination), so writes between pages
 * neither repeat nor skip documents that were already in order. No document, no page.
 */
public fun <T> Select<T>.pages(db: Database, size: Int): Flow<Page<T>> {
    require(size > 0) { "Page size must be positive: $size" }
    return flow {
        var cursor: Cursor? = null
        var number = 0
        while (true) {
            // One row more than a page tells whether another page follows.
            val rows = db.read { rowsAfter(cursor, limit = size + 1) }
            if (rows.isEmpty()) break
            val isLast = rows.size <= size
            emit(Page(rows.take(size).map(::decode), number++, isLast))
            if (isLast) break
            cursor = rows[size - 1].cursor
        }
    }
}

context(_: ReadScope)
private suspend fun Select<*>.rowsAfter(cursor: Cursor?, limit: Int): List<Row> =
    context(collection.connection()) { Queries.select(this, limit, cursor).execute() }
