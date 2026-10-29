package io.github.nailkhaf.nosqlite

import kotlinx.coroutines.flow.first
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
import kotlin.test.assertFails
import kotlin.test.assertFailsWith

/** Covers observing queries and lookups, and the changes that drive them. */
internal class ObserveTest {

    @Serializable
    data class User(val id: String, val age: Int)

    object Users : Collection("users") {
        val byId = unique("id")
    }

    object Notes : Collection("notes")

    private val ada = User("ada", 36)
    private val alan = User("alan", 41)
    private val note = buildJsonObject { put("text", "hi") }

    private fun TestScope.database() = NoSqlite(dispatcher = UnconfinedTestDispatcher(testScheduler)) {
        +Users
        +Notes
    }

    @Test
    fun `observe emits the current result first`() = runTest {
        val db = database()
        db.write { Users.insert(ada) }

        assertEquals(listOf(ada), with(db) { Users.observe<User>().first() })
    }

    @Test
    fun `a committed write bumps each collection it changed once`() = runTest {
        val db = database()

        db.write {
            Users.insert(ada)
            db.write { Users.insert(alan) }
            Notes.insert(note)
        }

        assertEquals(mapOf("users" to 1L, "notes" to 1L), db.versions.value)
    }

    @Test
    fun `every kind of write bumps a version`() = runTest {
        val db = database()

        db.write { Notes.insert(note) }
        db.write { Notes.update(buildJsonObject { put("text", "bye") }) { "text" eq "hi" } }
        db.write { Notes.replace(note) { "text" eq "bye" } }
        db.write { Users.byId.upsert(ada) }
        db.write { Notes.delete { "text" eq "hi" } }

        assertEquals(mapOf("notes" to 4L, "users" to 1L), db.versions.value)
    }

    @Test
    fun `a slow observer still sees a write followed by many to other collections`() = runTest {
        val db = database()
        val users = mutableListOf<List<User>>()
        backgroundScope.launch { with(db) { Users.observe<User>() }.toList(users) }
        runCurrent()

        db.write { Users.insert(ada) }
        repeat(100) { db.write { Notes.insert(note) } }
        runCurrent()

        assertEquals(listOf(emptyList(), listOf(ada)), users)
    }

    @Test
    fun `writes that change nothing or roll back bump nothing`() = runTest {
        val db = database()
        db.write { Users.insert(ada) }

        assertFails {
            db.write {
                Users.insert(alan)
                error("rollback")
            }
        }
        db.write { Users.delete { "age" gt 100 } }
        db.write { Users.update(User("x", 1)) { "id" eq "nobody" } }
        db.write { Users.byId("nobody").delete() }

        assertEquals(mapOf("users" to 1L), db.versions.value)
    }

    @Test
    fun `a rolled back savepoint takes its changes with it`() = runTest {
        val db = database()

        db.write {
            Notes.insert(note)
            assertFails {
                db.write {
                    Users.insert(ada)
                    error("rollback")
                }
            }
        }

        assertEquals(mapOf("notes" to 1L), db.versions.value)
    }

    @Test
    fun `observe of a closed database fails`() = runTest {
        val db = database()
        db.write { Users.insert(ada) }
        db.close()

        val error = assertFailsWith<IllegalStateException> { with(db) { Users.observe<User>().first() } }

        assertEquals("Database is closed", error.message)
    }

    @Test
    fun `observe of an undeclared collection is rejected`() = runTest {
        val db = NoSqlite(dispatcher = UnconfinedTestDispatcher(testScheduler)) { +Notes }

        val error =
            assertFailsWith<NoSqliteException.CollectionNotDeclared> { with(db) { Users.observe<User>().first() } }

        assertEquals("users", error.collection)
    }
}
