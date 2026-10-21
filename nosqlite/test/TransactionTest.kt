package io.github.nailkhaf.nosqlite

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith

/** Covers read and write transactions: commit, rollback, savepoints, snapshots and reporting changed tables. */
internal class TransactionTest {

    private suspend fun poolWithTable(onCommit: (Set<String>) -> Unit = {}): ConnectionPool =
        ConnectionPool(onCommit = onCommit).apply { writeSql { createTable() } }

    @Test
    fun `write commits everything it did`() = runTest {
        val pool = poolWithTable()

        pool.writeSql {
            insert("first")
            insert("second")
        }

        assertEquals(listOf("first", "second"), pool.readSql { values() })
    }

    @Test
    fun `write that throws rolls back`() = runTest {
        val pool = poolWithTable()

        assertFails {
            pool.writeSql {
                insert("doomed")
                error("rollback")
            }
        }

        assertEquals(emptyList(), pool.readSql { values() })
    }

    @Test
    fun `cancelled write rolls back and frees the writer`() = runTest {
        val pool = poolWithTable()
        val inserted = CompletableDeferred<Unit>()
        val job = launch {
            pool.writeSql {
                insert("doomed")
                inserted.complete(Unit)
                awaitCancellation()
            }
        }
        inserted.await()

        job.cancelAndJoin()
        pool.writeSql { insert("after") }

        assertEquals(listOf("after"), pool.readSql { values() })
    }

    @Test
    fun `nested write that throws rolls back alone`() = runTest {
        val pool = poolWithTable()

        pool.writeSql {
            insert("outer")
            assertFails {
                pool.writeSql {
                    insert("inner")
                    error("rollback savepoint")
                }
            }
            insert("after")
        }

        assertEquals(listOf("outer", "after"), pool.readSql { values() })
    }

    @Test
    fun `outer write that throws rolls back its committed savepoints`() = runTest {
        val pool = poolWithTable()

        assertFails {
            pool.writeSql {
                insert("outer")
                pool.writeSql { insert("inner") }
                error("rollback outer")
            }
        }

        assertEquals(emptyList(), pool.readSql { values() })
    }

    @Test
    fun `savepoints roll back level by level`() = runTest {
        val pool = poolWithTable()

        pool.writeSql {
            insert("outer")
            assertFails {
                pool.writeSql {
                    insert("middle")
                    assertFails {
                        pool.writeSql {
                            insert("inner")
                            error("rollback inner")
                        }
                    }
                    error("rollback middle")
                }
            }
        }

        assertEquals(listOf("outer"), pool.readSql { values() })
    }

    @Test
    fun `read inside a write sees its uncommitted changes`() = runTest {
        val pool = poolWithTable()

        val seen = pool.writeSql {
            insert("pending")
            pool.readSql { values() }
        }

        assertEquals(listOf("pending"), seen)
    }

    @Test
    fun `read sees one snapshot while a write commits`() = runTest {
        withTempFile { location ->
            val pool = ConnectionPool(location).apply {
                writeSql {
                    createTable()
                    insert("before")
                }
            }
            val firstRead = CompletableDeferred<Unit>()
            val committed = CompletableDeferred<Unit>()

            val reads = launch {
                pool.readSql {
                    assertEquals(listOf("before"), values())
                    firstRead.complete(Unit)
                    committed.await()
                    assertEquals(listOf("before"), values())
                }
            }
            firstRead.await()
            pool.writeSql { insert("after") }
            committed.complete(Unit)
            reads.join()

            assertEquals(listOf("before", "after"), pool.readSql { values() })
            pool.close()
        }
    }

    @Test
    fun `write inside a read is rejected`() = runTest {
        val pool = ConnectionPool()

        val error = assertFailsWith<IllegalStateException> { pool.readSql { pool.writeSql { } } }

        assertEquals("Write inside a read transaction, start write { } outside of read { }", error.message)
    }

    @Test
    fun `transaction of another database is rejected`() = runTest {
        val first = ConnectionPool()
        val second = ConnectionPool()

        val error = assertFailsWith<IllegalArgumentException> { first.readSql { second.readSql { } } }

        assertEquals("Active transaction belongs to another database", error.message)
    }

    @Test
    fun `connection left in a transaction is rejected`() = runTest {
        val pool = ConnectionPool()
        pool.withWriter { it.execSQL("BEGIN;") }

        val error = assertFailsWith<IllegalStateException> { pool.writeSql { } }

        assertEquals("Connection is already in a transaction, BEGIN would fail", error.message)
    }

    @Test
    fun `changed tables are reported once after the outermost commit`() = runTest {
        val reported = mutableListOf<Set<String>>()
        val pool = poolWithTable(onCommit = { reported += it })

        pool.write {
            pool.write { contextOf<WriteScope>().changed(setOf("a")) }
            assertEquals(emptyList(), reported)
            contextOf<WriteScope>().changed(setOf("b"))
        }

        assertEquals(listOf(setOf("a", "b")), reported)
    }

    @Test
    fun `rolled back changes are not reported`() = runTest {
        val reported = mutableListOf<Set<String>>()
        val pool = poolWithTable(onCommit = { reported += it })

        pool.write {
            assertFails {
                pool.write {
                    contextOf<WriteScope>().changed(setOf("savepoint"))
                    error("rollback savepoint")
                }
            }
            contextOf<WriteScope>().changed(setOf("kept"))
        }
        assertFails {
            pool.write {
                contextOf<WriteScope>().changed(setOf("outer"))
                error("rollback")
            }
        }
        pool.write { }

        assertEquals(listOf(setOf("kept")), reported)
    }
}
