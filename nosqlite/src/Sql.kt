package io.github.nailkhaf.nosqlite

// SQL with how to bind its input and read its output; building one never touches the database.
internal class Sql<I, O>(
    val sql: String,
    val encode: suspend Statement.(I) -> Unit,
    val decode: suspend Statement.() -> O,
)

internal typealias Command<I> = Sql<I, Unit>

internal fun command(sql: String): Command<Unit> = Sql(sql, encode = {}, decode = { step() })

internal fun <R> rows(row: suspend Statement.() -> R): suspend Statement.() -> List<R> = {
    buildList { while (step()) add(row()) }
}

context(conn: Connection)
internal suspend fun <I, O> Sql<I, O>.execute(input: I): O = conn.statement(sql) { statement ->
    statement.encode(input)
    statement.decode()
}

context(conn: Connection)
internal suspend fun <O> Sql<Unit, O>.execute(): O = execute(Unit)
