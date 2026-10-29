package io.github.nailkhaf.nosqlite

import androidx.sqlite.async.step

/** Runs raw SQL in [block] inside a read transaction. */
internal suspend fun <R> ConnectionPool.readSql(block: suspend Connection.() -> R): R = read { connection().block() }

/** Runs raw SQL in [block] inside a write transaction. */
internal suspend fun <R> ConnectionPool.writeSql(block: suspend Connection.() -> R): R = write { connection().block() }

/** The rows [sql] returns, every column as text. */
internal suspend fun NoSqlite.querySql(sql: String): List<List<String?>> = pool().readSql {
    statement(sql) { statement ->
        buildList {
            while (statement.step()) {
                add(List(statement.getColumnCount()) { if (statement.isNull(it)) null else statement.getText(it) })
            }
        }
    }
}

internal suspend fun Connection.execute(sql: String) {
    statement(sql) { it.step() }
}

internal suspend fun Connection.createTable() = execute("CREATE TABLE t (id INTEGER PRIMARY KEY, value TEXT);")

internal suspend fun Connection.insert(value: String) = execute("INSERT INTO t (value) VALUES ('$value');")

internal suspend fun Connection.values(): List<String> = statement("SELECT value FROM t ORDER BY id;") { statement ->
    buildList { while (statement.step()) add(statement.getText(0)) }
}

internal suspend fun Connection.single(sql: String): String = statement(sql) { statement ->
    check(statement.step()) { "No row for $sql" }
    statement.getText(0)
}
