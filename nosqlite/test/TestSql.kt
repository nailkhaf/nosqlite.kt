package io.github.nailkhaf.nosqlite

// Transactions for tests below the document layer, which declare no collections.
private val NoCollections = Catalog(emptyList())

internal suspend fun <R> ConnectionPool.read(block: suspend context(ReadScope) () -> R): R = read(NoCollections, block)

internal suspend fun <R> ConnectionPool.write(block: suspend context(WriteScope) () -> R): R = write(NoCollections, block)

/** Runs raw SQL in [block] inside a read transaction. */
internal suspend fun <R> ConnectionPool.readSql(block: suspend Connection.() -> R): R = read { connection().block() }

/** Runs raw SQL in [block] inside a write transaction. */
internal suspend fun <R> ConnectionPool.writeSql(block: suspend Connection.() -> R): R = write { connection().block() }

/** The rows [sql] returns, every column as text. */
internal suspend fun Database.querySql(sql: String): List<List<String?>> = pool().readSql {
    statement(sql) { statement ->
        buildList {
            while (statement.step()) {
                add(List(statement.getColumnCount()) { if (statement.isNull(it)) null else statement.getText(it) })
            }
        }
    }
}
