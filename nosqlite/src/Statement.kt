package io.github.nailkhaf.nosqlite

// Building a statement never touches the database; execute is the only place it runs.
internal class Statement<I, O>(
    val sql: String,
    val encode: suspend LockedStatement.(I) -> Unit,
    val decode: suspend LockedStatement.() -> O,
)

internal typealias Command<I> = Statement<I, Unit>

internal fun command(sql: String): Command<Unit> = Statement(sql, encode = {}, decode = { step() })

internal fun <R> rows(row: suspend LockedStatement.() -> R): suspend LockedStatement.() -> List<R> = {
    buildList { while (step()) add(row()) }
}

context(conn: Connection)
internal suspend fun <I, O> Statement<I, O>.execute(input: I): O = conn.statement(sql) { statement ->
    statement.encode(input)
    statement.decode()
}

context(conn: Connection)
internal suspend fun <O> Statement<Unit, O>.execute(): O = execute(Unit)
