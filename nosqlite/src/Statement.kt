package io.github.nailkhaf.nosqlite

import androidx.sqlite.SQLiteStatement

// Building a statement never touches the database; execute is the only place it runs.
internal class Statement<I, O>(
    val sql: String,
    val encode: SQLiteStatement.(I) -> Unit,
    val decode: SQLiteStatement.() -> O,
    val cached: Boolean = true,
)

internal typealias Command<I> = Statement<I, Unit>

// DDL runs once, so it is not cached.
internal fun command(sql: String): Command<Unit> = Statement(sql, encode = {}, decode = { step() }, cached = false)

internal fun <R> rows(row: SQLiteStatement.() -> R): SQLiteStatement.() -> List<R> = {
    buildList { while (step()) add(row()) }
}

internal fun <R : Any> firstOrNull(row: SQLiteStatement.() -> R): SQLiteStatement.() -> R? = {
    if (step()) row() else null
}

context(conn: Connection)
internal suspend fun <I, O> Statement<I, O>.execute(input: I): O = if (cached) {
    conn.withStatement(sql) { stmt -> executeOn(stmt, input) }
} else {
    conn.prepare(sql).use { stmt -> executeOn(stmt, input) }
}

private fun <I, O> Statement<I, O>.executeOn(stmt: SQLiteStatement, input: I): O {
    stmt.encode(input)
    return stmt.decode()
}

context(conn: Connection)
internal suspend fun <O> Statement<Unit, O>.execute(): O = execute(Unit)
