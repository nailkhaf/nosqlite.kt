package io.github.nailkhaf.nosqlite

import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Covers the least-recently-used cache of prepared statements each connection keeps. */
internal class StatementCacheTest {

    private val sqlite = BundledSQLiteDriver().open(":memory:")

    @AfterTest
    fun close() = sqlite.close()

    private fun statement(sql: String) = TrackedStatement(sqlite.prepare(sql))

    @Test
    fun `nothing is cached at first`() = runTest {
        val cache = StatementCache()

        assertNull(cache.take("SELECT 1;"))
    }

    @Test
    fun `a cached statement is taken once`() = runTest {
        val cache = StatementCache()
        val statement = statement("SELECT 1;")

        cache.put("SELECT 1;", statement)

        assertSame(statement, cache.take("SELECT 1;"))
        assertNull(cache.take("SELECT 1;"))
        assertFalse(statement.closed)
    }

    @Test
    fun `a second statement for the same sql is closed`() = runTest {
        val cache = StatementCache()
        val first = statement("SELECT 1;")
        val second = statement("SELECT 1;")

        cache.put("SELECT 1;", first)
        cache.put("SELECT 1;", second)

        assertTrue(second.closed)
        assertSame(first, cache.take("SELECT 1;"))
    }

    @Test
    fun `beyond capacity the least recently used statement is closed`() = runTest {
        val cache = StatementCache(capacity = 2)
        val a = statement("SELECT 'a';")
        val b = statement("SELECT 'b';")
        val c = statement("SELECT 'c';")
        cache.put("a", a)
        cache.put("b", b)
        cache.put("a", cache.take("a")!!) // a is used again, so b is now the least recently used

        cache.put("c", c)

        assertTrue(b.closed)
        assertNull(cache.take("b"))
        assertSame(a, cache.take("a"))
        assertSame(c, cache.take("c"))
    }

    @Test
    fun `close closes every cached statement`() = runTest {
        val cache = StatementCache()
        val a = statement("SELECT 'a';")
        val b = statement("SELECT 'b';")
        cache.put("a", a)
        cache.put("b", b)

        cache.close()

        assertTrue(a.closed && b.closed)
        assertNull(cache.take("a"))
    }

    @Test
    fun `capacity must be positive`() {
        val error = assertFailsWith<IllegalArgumentException> { StatementCache(capacity = 0) }

        assertEquals("Capacity must be positive: 0", error.message)
    }
}

/** A statement that remembers whether it was closed. */
private class TrackedStatement(private val delegate: SQLiteStatement) : SQLiteStatement by delegate {
    var closed = false
        private set

    override fun close() {
        closed = true
        delegate.close()
    }
}
