package io.github.nailkhaf.nosqlite

/** Runs raw SQL in [block] inside a read transaction. */
internal suspend fun <R> ConnectionPool.readSql(block: suspend Connection.() -> R): R = read { connection().block() }

/** Runs raw SQL in [block] inside a write transaction. */
internal suspend fun <R> ConnectionPool.writeSql(block: suspend Connection.() -> R): R = write { connection().block() }
