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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

/** Covers observing selects and single documents, and the per-collection versions that drive them. */
internal class ObserveTest {

    object Users : Collection("users") {
        val byId = unique(User::id)
    }

    object Notes : Collection("notes")

    private fun database() = Database {
        +Users
        +Notes
    }

    private val adults = Users.select<User> { where { User::age gte 18 } }

    @Test
    fun `observe emits the current result first`() = runTest {
        val db = database()
        db.write { Users.insert(user("ada", 36)) }

        val first = adults.observe(db, limit = 10).first()

        assertEquals(listOf("ada"), first.map { it.id })
    }

    @Test
    fun `observe emits again after each committed write`() = runTest {
        val db = database()
        val ids = backgroundScope.record(adults.observe(db, limit = 10).map { users -> users.map { it.id } })
        assertEquals(emptyList(), ids.next())

        db.write { Users.insert(user("ada", 36)) }
        assertEquals(listOf("ada"), ids.next())

        db.write { Users.insert(user("alan", 41)) }
        assertEquals(listOf("ada", "alan"), ids.next())

        db.write { Users.byId.delete("ada") }
        assertEquals(listOf("alan"), ids.next())
    }

    @Test
    fun `observe skips results equal to the last one`() = runTest {
        val db = database()
        val ids = backgroundScope.record(adults.observe(db, limit = 10).map { users -> users.map { it.id } })
        assertEquals(emptyList(), ids.next())

        db.write { Users.insert(user("kid", 10)) } // not an adult: same result
        db.write { Notes.insert(buildJsonObject { put("text", "hi") }) } // another collection
        db.write { Users.insert(user("ada", 36)) }

        assertEquals(listOf("ada"), ids.next())
    }

    @Test
    fun `observe after many quick writes ends on the latest result`() = runTest {
        val db = database()
        val all = Users.select<User>().observe(db, limit = 1000)

        repeat(100) { i -> db.write { Users.insert(user("u$i", i)) } }

        assertEquals(100, all.first().size)
    }

    @Test
    fun `observe one document by id`() = runTest {
        val db = database()
        val id = db.write { Users.insert(user("ada", 36)) }
        val ada = backgroundScope.record(Users.observe<User>(db, id))
        assertEquals(36, ada.next()?.age)

        db.write { Users.patch(id, buildJsonObject { put("age", 37) }) }
        assertEquals(37, ada.next()?.age)

        db.write { Users.delete(id) }
        assertNull(ada.next())
    }

    @Test
    fun `observe one document by unique key`() = runTest {
        val db = database()
        val alan = backgroundScope.record(Users.byId.observe<User>(db, "alan"))
        assertNull(alan.next())

        db.write { Users.insert(user("ada", 36)) }
        db.write { Users.insert(user("alan", 41)) }

        assertEquals(41, alan.next()?.age)
    }

    @Test
    fun `a committed write bumps the version of each collection it changed once`() = runTest {
        val db = database()

        db.write {
            Users.insert(user("ada", 36))
            db.write { Users.insert(user("alan", 41)) }
            Notes.insert(buildJsonObject { put("text", "hi") })
        }

        assertEquals(1, db.version(Users).value)
        assertEquals(1, db.version(Notes).value)
    }

    @Test
    fun `writes that change nothing or roll back keep the version`() = runTest {
        val db = database()

        assertFails {
            db.write {
                Users.insert(user("ada", 36))
                error("rollback")
            }
        }
        db.write { Users.delete { User::age gt 100 } }

        assertEquals(0, db.version(Users).value)
    }

    @Test
    fun `observe of an undeclared collection is rejected`() {
        val db = Database { +Notes }

        assertFailsWith<NosqliteException.CollectionNotDeclared> { Users.select<User>().observe(db, limit = 10) }
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
