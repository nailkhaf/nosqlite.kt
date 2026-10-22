package io.github.nailkhaf.nosqlite

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith

/** Covers observing queries and lookups, and the changes that drive them. */
internal class ObserveTest {

    @Serializable
    data class User(val id: String, val age: Int)

    object Users : Collection<User>("users", User.serializer()) {
        val byId = unique("id")
        val byAge = index("age")
    }

    object Notes : Collection<JsonObject>("notes", JsonObject.serializer())

    private val ada = User("ada", 36)
    private val alan = User("alan", 41)
    private val note = buildJsonObject { put("text", "hi") }

    private fun TestScope.database() = Database(dispatcher = StandardTestDispatcher(testScheduler)) {
        +Users
        +Notes
    }

    /** Collects [flow] in the background from now on. */
    private fun <T> TestScope.collect(flow: Flow<T>): List<T> {
        val values = mutableListOf<T>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.toList(values) }
        runCurrent()
        return values
    }

    @Test
    fun `observe emits the current result first`() = runTest {
        val db = database()
        db.write { Users.insert(ada) }

        assertEquals(listOf(ada), with(db) { Users.observe().first() })
    }

    @Test
    fun `observe emits again after each committed write`() = runTest {
        val db = database()
        val users = collect(with(db) { Users.observe { orderBy(asc("id")) } })

        db.write { Users.insert(ada) }
        runCurrent()
        db.write { Users.byId.upsert(alan) }
        runCurrent()
        db.write { Users.update(alan.copy(age = 42)) { "id" eq "alan" } }
        runCurrent()
        db.write { Users.byId("ada").delete() }
        runCurrent()

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
        val db = Database(dispatcher = UnconfinedTestDispatcher(testScheduler)) { +Users }
        val users = mutableListOf<List<User>>()
        backgroundScope.launch { with(db) { Users.observe() }.toList(users) }
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
        val adults = collect(with(db) { Users.observe { where { "age" gte 18 } } })

        db.write { Users.insert(User("kid", 10)) }
        runCurrent()
        db.write { Notes.insert(note) }
        runCurrent()
        db.write { Users.insert(ada) }
        runCurrent()

        assertEquals(listOf(emptyList(), listOf(ada)), adults)
    }

    @Test
    fun `observe a unique lookup`() = runTest {
        val db = database()
        val found = collect(with(db) { Users.byId("alan").observe() })

        db.write { Users.insert(alan) }
        runCurrent()
        db.write { Users.byId("alan").update(alan.copy(age = 42)) }
        runCurrent()
        db.write { Users.byId("alan").delete() }
        runCurrent()

        assertEquals(listOf(null, alan, alan.copy(age = 42), null), found)
    }

    @Test
    fun `observe a lookup with its query`() = runTest {
        val db = database()
        val found = collect(with(db) { Users.byAge(36).observe { orderBy(desc("id")) } })

        db.write {
            Users.insert(ada)
            Users.insert(User("cid", 36))
            Users.insert(alan)
        }
        runCurrent()

        assertEquals(listOf(emptyList(), listOf(User("cid", 36), ada)), found)
    }

    @Test
    fun `a committed write publishes each collection it changed once`() = runTest {
        val db = database()
        val changes = collect(db.changes)

        db.write {
            Users.insert(ada)
            db.write { Users.insert(alan) }
            Notes.insert(note)
        }
        runCurrent()

        assertEquals(setOf("users", "notes"), changes.toSet())
        assertEquals(2, changes.size)
    }

    @Test
    fun `every kind of write publishes a change`() = runTest {
        val db = database()
        val changes = collect(db.changes)

        db.write { Notes.insert(note) }
        db.write { Notes.patch(buildJsonObject { put("text", "bye") }) { "text" eq "hi" } }
        db.write { Notes.update(note) { "text" eq "bye" } }
        db.write { Users.byId.upsert(ada) }
        db.write { Notes.delete { "text" eq "hi" } }
        runCurrent()

        assertEquals(listOf("notes", "notes", "notes", "users", "notes"), changes)
    }

    @Test
    fun `writes that change nothing or roll back publish nothing`() = runTest {
        val db = database()
        db.write { Users.insert(ada) }
        val changes = collect(db.changes)

        assertFails {
            db.write {
                Users.insert(alan)
                error("rollback")
            }
        }
        db.write { Users.delete { "age" gt 100 } }
        db.write { Users.update(User("x", 1)) { "id" eq "nobody" } }
        db.write { Users.byId("nobody").delete() }
        runCurrent()

        assertEquals(emptyList(), changes)
    }

    @Test
    fun `a rolled back savepoint takes its changes with it`() = runTest {
        val db = database()
        val changes = collect(db.changes)

        db.write {
            Notes.insert(note)
            assertFails {
                db.write {
                    Users.insert(ada)
                    error("rollback")
                }
            }
        }
        runCurrent()

        assertEquals(listOf("notes"), changes)
    }

    @Test
    fun `observe of a closed database fails`() = runTest {
        val db = database()
        db.write { Users.insert(ada) }
        db.close()

        val error = assertFailsWith<IllegalStateException> { with(db) { Users.observe().first() } }

        assertEquals("Database is closed", error.message)
    }

    @Test
    fun `observe of an undeclared collection is rejected`() = runTest {
        val db = Database(dispatcher = StandardTestDispatcher(testScheduler)) { +Notes }

        val error = assertFailsWith<NosqliteException.CollectionNotDeclared> { with(db) { Users.observe().first() } }

        assertEquals("users", error.collection)
    }
}
