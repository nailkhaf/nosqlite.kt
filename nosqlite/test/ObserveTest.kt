package io.github.nailkhaf.nosqlite

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

/** Covers observing queries and lookups, and the per-collection versions that drive them. */
internal class ObserveTest {

    @Serializable
    data class User(val id: String, val age: Int)

    object Users : Collection<User>("users", User.serializer()) {
        val byId = unique("id")
        val byAge = index("age")
    }

    object Notes : Collection<JsonObject>("notes", JsonObject.serializer())

    private fun database() = Database {
        +Users
        +Notes
    }

    private val note = buildJsonObject { put("text", "hi") }

    @Test
    fun `observe emits the current result first`() = runTest {
        val db = database()
        db.write { Users.insert(User("ada", 36)) }

        assertEquals(listOf(User("ada", 36)), Users.observe(db).first())
    }

    @Test
    fun `observe emits again after each committed write`() = runTest {
        val db = database()
        val ids = backgroundScope.record(Users.observe(db) { orderBy(asc("id")) }.map { users -> users.map { it.id } })
        assertEquals(emptyList(), ids.next())

        db.write { Users.insert(User("ada", 36)) }
        assertEquals(listOf("ada"), ids.next())

        db.write { Users.byId.upsert(User("alan", 41)) }
        assertEquals(listOf("ada", "alan"), ids.next())

        db.write { Users.update(User("bob", 41)) { "id" eq "alan" } }
        assertEquals(listOf("ada", "bob"), ids.next())

        db.write { Users.byId("ada").delete() }
        assertEquals(listOf("bob"), ids.next())
    }

    @Test
    fun `observe skips results equal to the last one`() = runTest {
        val db = database()
        val adults = backgroundScope.record(Users.observe(db) { where { "age" gte 18 } })
        assertEquals(emptyList(), adults.next())

        db.write { Users.insert(User("kid", 10)) }
        db.write { Notes.insert(note) }
        db.write { Users.insert(User("ada", 36)) }

        assertEquals(listOf(User("ada", 36)), adults.next())
    }

    @Test
    fun `observe after many quick writes ends on the latest result`() = runTest {
        val db = database()
        val all = Users.observe(db)

        repeat(100) { i -> db.write { Users.insert(User("u$i", i)) } }

        assertEquals(100, all.first().size)
    }

    @Test
    fun `observe a unique lookup`() = runTest {
        val db = database()
        val alan = backgroundScope.record(Users.byId("alan").observe(db))
        assertNull(alan.next())

        db.write { Users.insert(User("alan", 41)) }
        assertEquals(User("alan", 41), alan.next())

        db.write { Users.byId("alan").update(User("alan", 42)) }
        assertEquals(User("alan", 42), alan.next())

        db.write { Users.byId("alan").delete() }
        assertNull(alan.next())
    }

    @Test
    fun `observe a lookup with its query`() = runTest {
        val db = database()
        val ids = backgroundScope.record(
            Users.byAge(36).observe(db) { orderBy(desc("id")) }.map { users -> users.map { it.id } },
        )
        assertEquals(emptyList(), ids.next())

        db.write {
            Users.insert(User("ada", 36))
            Users.insert(User("cid", 36))
            Users.insert(User("bob", 40))
        }

        assertEquals(listOf("cid", "ada"), ids.next())
    }

    @Test
    fun `a committed write bumps each collection it changed once`() = runTest {
        val db = database()

        db.write {
            Users.insert(User("ada", 36))
            db.write { Users.insert(User("alan", 41)) }
            Notes.insert(note)
        }

        assertEquals(1, db.versions.value.getValue(Users.name))
        assertEquals(1, db.versions.value.getValue(Notes.name))
    }

    @Test
    fun `every kind of write counts as a change`() = runTest {
        val db = database()
        db.write { Notes.insert(note) }

        db.write { Notes.patch(buildJsonObject { put("text", "bye") }) { "text" eq "hi" } }
        db.write { Notes.update(note) { "text" eq "bye" } }
        db.write { Notes.delete { "text" eq "hi" } }

        assertEquals(4, db.versions.value.getValue(Notes.name))
    }

    @Test
    fun `writes that change nothing or roll back keep the version`() = runTest {
        val db = database()
        db.write { Users.insert(User("ada", 36)) }

        assertFails {
            db.write {
                Users.insert(User("alan", 41))
                error("rollback")
            }
        }
        db.write { Users.delete { "age" gt 100 } }
        db.write { Users.update(User("x", 1)) { "id" eq "nobody" } }
        db.write { Users.byId("nobody").delete() }

        assertEquals(1, db.versions.value.getValue(Users.name))
    }

    @Test
    fun `a rolled back savepoint takes its changes with it`() = runTest {
        val db = database()

        db.write {
            Notes.insert(note)
            assertFails {
                db.write {
                    Users.insert(User("ada", 36))
                    error("rollback")
                }
            }
        }

        assertEquals(0, db.versions.value.getValue(Users.name))
        assertEquals(1, db.versions.value.getValue(Notes.name))
    }

    @Test
    fun `observe of a closed database fails`() = runTest {
        val db = database()
        db.write { Users.insert(User("ada", 36)) }
        db.close()

        val error = assertFailsWith<IllegalStateException> { Users.observe(db).first() }

        assertEquals("Database is closed", error.message)
    }

    @Test
    fun `observe of an undeclared collection is rejected`() = runTest {
        val db = Database { +Notes }

        val error = assertFailsWith<NosqliteException.CollectionNotDeclared> { Users.observe(db).first() }

        assertEquals("users", error.collection)
    }

    /** The values of a flow collected on a real dispatcher, read one at a time with a real timeout. */
    private class Recorder<T>(scope: CoroutineScope, flow: Flow<T>) {
        private val values = Channel<T>(Channel.UNLIMITED)

        init {
            scope.launch(Dispatchers.Default) { flow.collect { values.send(it) } }
        }

        suspend fun next(): T = withContext(Dispatchers.Default) { withTimeout(5.seconds) { values.receive() } }
    }

    private fun <T> CoroutineScope.record(flow: Flow<T>) = Recorder(this, flow)
}
