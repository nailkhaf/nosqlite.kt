package io.github.nailkhaf.nosqlite

import androidx.sqlite.SQLiteStatement

// Building a statement never touches the database; execute is the only place it runs.
internal class Statement<I, O>(
    val sql: String,
    val encode: SQLiteStatement.(I) -> Unit,
    val decode: SQLiteStatement.() -> O,
)

internal typealias Command<I> = Statement<I, Unit>

internal fun command(sql: String): Command<Unit> = Statement(sql, encode = {}, decode = { step() })

internal fun <R> rows(row: SQLiteStatement.() -> R): SQLiteStatement.() -> List<R> = {
    buildList { while (step()) add(row()) }
}

context(conn: Connection)
internal suspend fun <I, O> Statement<I, O>.execute(input: I): O = conn.statement(sql) { statement ->
    conn.exclusive {
        statement.encode(input)
        statement.decode()
    }
}

context(conn: Connection)
internal suspend fun <O> Statement<Unit, O>.execute(): O = execute(Unit)
