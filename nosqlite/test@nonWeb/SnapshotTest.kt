package io.github.nailkhaf.nosqlite

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Covers reads on their own connections, which need WAL. */
internal class SnapshotTest {

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
}
