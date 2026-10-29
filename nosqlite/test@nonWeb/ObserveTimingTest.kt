package io.github.nailkhaf.nosqlite

import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers each emission of observe as writes land. They are pinned by finishing every read inside the test dispatcher,
 * which the web driver can't, as it answers from a worker.
 */
internal class ObserveTimingTest {

    @Serializable
    data class User(val id: String, val age: Int)

    object Users : Collection("users") {
        val byId = unique("id")
        val byAge = index("age")
    }

    object Notes : Collection("notes")

    private val ada = User("ada", 36)
    private val alan = User("alan", 41)
    private val note = buildJsonObject { put("text", "hi") }

    private fun TestScope.database() = NoSqlite(dispatcher = UnconfinedTestDispatcher(testScheduler)) {
        +Users
        +Notes
    }

    /** Collects [flow] in the background from now on. */
    private fun <T> TestScope.collect(flow: Flow<T>): List<T> {
        val values = mutableListOf<T>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.toList(values) }
        return values
    }

    @Test
    fun `observe emits again after each committed write`() = runTest {
        val db = database()
        val users = collect(with(db) { Users.observe<User> { orderBy { "id".asc } } })

        db.write { Users.insert(ada) }
        db.write { Users.byId.upsert(alan) }
        db.write { Users.update(alan.copy(age = 42)) { "id" eq "alan" } }
        db.write { Users.byId("ada").delete() }

        assertEquals(
            listOf(
                emptyList(),
                listOf(ada),
                listOf(ada, alan),
                listOf(ada, alan.copy(age = 42)),
                listOf(alan.copy(age = 42)),
            ),
            users,
        )
    }

    @Test
    fun `writes landing during a read are folded into one more read`() = runTest {
        val db = NoSqlite(dispatcher = UnconfinedTestDispatcher(testScheduler)) { +Users }
        val users = mutableListOf<List<User>>()
        backgroundScope.launch { with(db) { Users.observe<User>() }.toList(users) }
        runCurrent()

        db.write { Users.insert(ada) }
        db.write { Users.insert(alan) }
        db.write { Users.insert(User("bob", 7)) }
        runCurrent()

        assertEquals(listOf(emptyList(), listOf(ada, alan, User("bob", 7))), users)
    }

    @Test
    fun `observe skips results equal to the last one`() = runTest {
        val db = database()
        val adults = collect(with(db) { Users.observe<User> { where { "age" gte 18 } } })

        db.write { Users.insert(User("kid", 10)) }
        db.write { Notes.insert(note) }
        db.write { Users.insert(ada) }

        assertEquals(listOf(emptyList(), listOf(ada)), adults)
    }

    @Test
    fun `observe a unique lookup`() = runTest {
        val db = database()
        val found = collect(with(db) { Users.byId("alan").observe<User>() })

        db.write { Users.insert(alan) }
        db.write { Users.byId("alan").update(alan.copy(age = 42)) }
        db.write { Users.byId("alan").delete() }

        assertEquals(listOf(null, alan, alan.copy(age = 42), null), found)
    }

    @Test
    fun `observe a lookup with its query`() = runTest {
        val db = database()
        val found = collect(with(db) { Users.byAge(36).observe<User> { orderBy { "id".desc } } })

        db.write {
            Users.insert(ada)
            Users.insert(User("cid", 36))
            Users.insert(alan)
        }

        assertEquals(listOf(emptyList(), listOf(User("cid", 36), ada)), found)
    }

    @Test
    fun `a write cancelled right after its commit still bumps its version`() = runTest {
        var writing: Job? = null
        val db = NoSqlite(
            driver = SteppingDriver("COMMIT;") { writing?.cancel() },
            dispatcher = UnconfinedTestDispatcher(testScheduler),
        ) { +Users }
        val users = collect(with(db) { Users.observe<User>() })

        writing = launch { db.write { Users.insert(ada) } }
        writing.join()

        assertTrue(writing.isCancelled)
        assertEquals(listOf(emptyList(), listOf(ada)), users)
    }
}
