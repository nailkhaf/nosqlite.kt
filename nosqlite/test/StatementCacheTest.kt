package io.github.nailkhaf.nosqlite

import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.sync.Mutex
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
    private val lock = Mutex()
    private val closed = mutableSetOf<Statement>()

    @AfterTest
    fun close() = sqlite.close()

    /** A statement that [closed] remembers once it is closed. */
    private fun statement(sql: String): Statement {
        lateinit var locked: Statement
        val tracked = object : SQLiteStatement by sqlite.prepare(sql) {
            override fun close() {
                closed += locked
            }
        }
        return Statement(tracked, lock).also { locked = it }
    }

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
        assertFalse(statement in closed)
    }

    @Test
    fun `a cached statement comes back reset with no bindings`() = runTest {
        val cache = StatementCache()
        val statement = statement("SELECT ?;")
        statement.bindLong(1, 42)
        statement.step()

        cache.put("SELECT ?;", statement)
        val reused = cache.take("SELECT ?;")!!

        assertTrue(reused.step())
        assertTrue(reused.isNull(0))
    }

    @Test
    fun `a second statement for the same sql is closed`() = runTest {
        val cache = StatementCache()
        val first = statement("SELECT 1;")
        val second = statement("SELECT 1;")

        cache.put("SELECT 1;", first)
        cache.put("SELECT 1;", second)

        assertTrue(second in closed)
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

        assertTrue(b in closed)
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

        assertTrue(a in closed && b in closed)
        assertNull(cache.take("a"))
    }

    @Test
    fun `capacity must be positive`() {
        val error = assertFailsWith<IllegalArgumentException> { StatementCache(capacity = 0) }

        assertEquals("Capacity must be positive: 0", error.message)
    }
}
