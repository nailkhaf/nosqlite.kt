package io.github.nailkhaf.nosqlite

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith

/** Covers read and write transactions: commit, rollback, savepoints and snapshots. */
internal class TransactionTest {

    private object Users : Collection<User>("users", User.serializer())

    private suspend fun poolWithTable(): ConnectionPool = ConnectionPool().apply { writeSql { createTable() } }

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

        val error = assertFailsWith<IllegalStateException> { first.readSql { second.readSql { } } }

        assertEquals("Active transaction belongs to another database", error.message)
    }

    @Test
    fun `coroutines started inside a write take turns on its connection`() = runTest {
        val driver = GateDriver()
        val db = NoSqlite(driver = driver) { +Users }
        db.write { }
        val inner = CompletableDeferred<Unit>()

        db.write {
            launch(Dispatchers.Default) {
                driver.entered.await()
                driver.release()
                Users.insert(user("bob", 30))
                inner.complete(Unit)
            }
            // The insert's step() is held until the inner coroutine is waiting for the connection.
            driver.hold()
            Users.insert(user("ada", 36))
            inner.await()
        }

        assertEquals(listOf(30, 36), db.read { Users.list { orderBy { "age".asc } } }.map { it.age })
        db.close()
    }

    @Test
    fun `connection left in a transaction is rejected`() = runTest {
        val pool = ConnectionPool()
        pool.withWriter { it.execute("BEGIN;") }

        val error = assertFailsWith<IllegalStateException> { pool.writeSql { } }

        assertEquals("Connection is already in a transaction, BEGIN would fail", error.message)
    }
}
