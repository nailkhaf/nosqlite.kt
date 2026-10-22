package io.github.nailkhaf.nosqlite

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Covers how a [Connection] lends its prepared statements. */
internal class ConnectionTest {

    private suspend fun connectionWithRows(vararg values: String): Connection =
        Connection.open(CountingDriver(), Location.InMemory).apply {
            createTable()
            values.forEach { insert(it) }
        }

    @Test
    fun `a statement runs again on the next call`() = runTest {
        val connection = connectionWithRows("a")

        val first = connection.values()
        connection.insert("b")
        val second = connection.values()

        assertEquals(listOf("a"), first)
        assertEquals(listOf("a", "b"), second)
        connection.close()
    }

    @Test
    fun `a nested call with the same sql gets its own statement`() = runTest {
        val connection = connectionWithRows("a", "b")
        val sql = "SELECT value FROM t ORDER BY id;"

        // The outer statement is mid-way through its rows while the inner one reads them all.
        val seen = connection.statement(sql) { outer ->
            buildList {
                while (outer.step()) add(outer.getText(0) to connection.values())
            }
        }

        assertEquals(listOf("a" to listOf("a", "b"), "b" to listOf("a", "b")), seen)
        connection.close()
    }

    @Test
    fun `a statement whose block throws is not reused`() = runTest {
        val connection = connectionWithRows("a")
        val sql = "SELECT value FROM t ORDER BY id;"

        assertFailsWith<IllegalStateException> {
            connection.statement(sql) { statement ->
                statement.step()
                error("fails mid-way")
            }
        }

        // A reused statement would continue after the first row; a new one starts over.
        assertEquals(listOf("a"), connection.values())
        connection.close()
    }
}
