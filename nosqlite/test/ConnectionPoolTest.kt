package io.github.nailkhaf.nosqlite

import androidx.sqlite.SQLiteException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Covers how [ConnectionPool] opens, hands out and closes its writer and readers. */
internal class ConnectionPoolTest {

    @Test
    fun `in-memory reader uses the writer's connection`() = runTest {
        val pool = ConnectionPool()
        pool.withWriter {
            it.createTable()
            it.insert("hello")
        }

        val values = pool.withReader { it.values() }

        assertEquals(listOf("hello"), values)
    }

    @Test
    fun `file readers see what the writer committed`() = runTest {
        withTempFile { location ->
            val pool = ConnectionPool(location)
            pool.withWriter {
                it.createTable()
                it.insert("hello")
            }

            val values = pool.withReader { it.values() }

            assertEquals(listOf("hello"), values)
            pool.close()
        }
    }

    @Test
    fun `file connections use WAL journal mode`() = runTest {
        withTempFile { location ->
            val pool = ConnectionPool(location)

            val mode = pool.withWriter { it.single("PRAGMA journal_mode;") }

            assertEquals("wal", mode)
            pool.close()
        }
    }

    @Test
    fun `readers refuse to write`() = runTest {
        withTempFile { location ->
            val pool = ConnectionPool(location)
            pool.withWriter { it.createTable() }

            assertFailsWith<SQLiteException> { pool.withReader { it.insert("nope") } }

            pool.close()
        }
    }

    @Test
    fun `readers serve coroutines at the same time`() = runTest {
        withTempFile(maxReaders = 2) { location ->
            val pool = ConnectionPool(location)
            pool.withWriter {
                it.createTable()
                it.insert("hello")
            }
            val arrived = List(2) { CompletableDeferred<Unit>() }

            // Each reader waits inside until the other one is in too, which only works with two connections.
            val values = List(2) { reader ->
                async {
                    pool.withReader { connection ->
                        arrived[reader].complete(Unit)
                        arrived.awaitAll()
                        connection.values()
                    }
                }
            }.awaitAll()

            assertEquals(listOf(listOf("hello"), listOf("hello")), values)
            pool.close()
        }
    }

    @Test
    fun `connections open on first use only`() = runTest {
        withTempFile(maxReaders = 3) { location ->
            val driver = CountingDriver()
            val pool = ConnectionPool(location, driver)
            assertEquals(0, driver.opened)

            pool.withWriter { it.createTable() }
            repeat(3) { pool.withReader { it.values() } }

            // One writer, and one reader reused by the three sequential reads.
            assertEquals(2, driver.opened)
            pool.close()
        }
    }

    @Test
    fun `closing an unused pool opens nothing`() = runTest {
        withTempFile { location ->
            val driver = CountingDriver()

            ConnectionPool(location, driver).close()

            assertEquals(0, driver.opened)
            assertFalse(location.exists())
        }
    }

    @Test
    fun `a closed pool lends no connection`() = runTest {
        val pool = ConnectionPool()
        pool.withWriter { it.createTable() }
        pool.close()

        val write = assertFailsWith<IllegalStateException> { pool.withWriter { } }
        val read = assertFailsWith<IllegalStateException> { pool.withReader { } }

        assertEquals("Database is closed", write.message)
        assertEquals("Database is closed", read.message)
    }

    @Test
    fun `closing twice is allowed`() = runTest {
        val pool = ConnectionPool()
        pool.withWriter { it.createTable() }

        pool.close()
        pool.close()
    }

    @Test
    fun `a connection in use closes when it comes back`() = runTest {
        val pool = ConnectionPool()

        pool.withWriter {
            it.createTable()
            pool.close()
            // Closing doesn't take the connection away from the coroutine using it.
            it.execute("INSERT INTO t (value) VALUES ('still open');")
        }

        assertFailsWith<IllegalStateException> { pool.withWriter { } }
    }

    @Test
    fun `delete removes the file and its sidecars`() = runTest {
        withTempFile { location ->
            val pool = ConnectionPool(location)
            pool.withWriter { it.createTable() }
            pool.close()
            assertTrue(location.exists())

            location.delete()

            assertFalse(location.exists())
        }
    }

    @Test
    fun `negative reader count is rejected`() {
        val error = assertFailsWith<IllegalArgumentException> { Location.File("test.db", maxReaders = -1) }

        assertEquals("Negative maxReaders: -1", error.message)
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
