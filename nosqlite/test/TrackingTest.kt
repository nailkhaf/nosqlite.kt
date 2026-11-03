package io.github.nailkhaf.nosqlite

import androidx.sqlite.SQLiteException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/** Covers change tracking: triggers, the pending queue, and untracked writes. */
internal class TrackingTest {

    @Serializable
    data class Contact(val id: String, val email: String, val name: String, val age: Int)

    object Contacts : Collection("contacts", tracked = true) {
        val byId = primaryKey(Contact::id)
        val byEmail = unique("email")
        val byAge = index("age")
    }

    object Notes : Collection("notes")

    @Serializable
    data class Person(val id: String)

    @Serializable
    data class Named(val name: Name, val age: Int)

    private val ada = contact("ada", 36)
    private val alan = contact("alan", 41)
    private val grace = contact("grace", 36)
    private val note = buildJsonObject { put("text", "hi") }

    private fun contact(id: String, age: Int, name: String = id.replaceFirstChar { it.uppercase() }) =
        Contact(id, "$id@x.com", name, age)

    private fun database(clock: Clock = Clock.System) = NoSqlite(clock = clock) {
        +Contacts
        +Notes
    }

    private fun clock(millis: Long) = ManualClock(millis)

    private class ManualClock(var millis: Long) : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(millis)
    }

    private fun Contact.json(): JsonObject =
        NoSqlite.Json.parseToJsonElement(NoSqlite.Json.encodeToString(this)) as JsonObject

    private fun Named.json(): JsonObject =
        NoSqlite.Json.parseToJsonElement(NoSqlite.Json.encodeToString(this)) as JsonObject

    private fun recorded(
        id: Long,
        before: JsonObject?,
        after: JsonObject?,
        at: Long,
        collection: String = Contacts.name,
    ) = PendingChange(id, collection, before, after, Instant.fromEpochMilliseconds(at))

    private suspend fun changes(db: NoSqlite): List<PendingChange> = db.read { pendingChanges() }

    /** Each change's before and after, when every row has this timestamp and the ids are 1..n with no duplicates. */
    private fun List<PendingChange>.pairs(at: Long): Set<Pair<JsonObject?, JsonObject?>> {
        assertEquals((1L..size).toList(), map { it.id })
        assertTrue(all { it.timestamp == Instant.fromEpochMilliseconds(at) && it.collection == Contacts.name })
        return map { it.before to it.after }.toSet().also { assertEquals(size, it.size) }
    }

    private suspend fun NoSqlite.contacts(): List<Contact> = read { Contacts.list { orderBy { "id".asc } } }

    private suspend fun NoSqlite.tempTriggers(): List<String> = pool().writeSql {
        statement("SELECT name FROM sqlite_temp_schema WHERE type = 'trigger' ORDER BY name;") { statement ->
            buildList { while (statement.step()) add(statement.getText(0)) }
        }
    }

    private suspend fun NoSqlite.tempTriggerSql(): List<String> = pool().writeSql {
        statement("SELECT sql FROM sqlite_temp_schema WHERE type = 'trigger' ORDER BY name;") { statement ->
            buildList { while (statement.step()) add(statement.getText(0)) }
        }
    }

    private fun <T> TestScope.collect(flow: Flow<T>): List<T> {
        val values = mutableListOf<T>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.toList(values) }
        return values
    }

    @Test
    fun `insert records the new document`() = runTest {
        val db = database(clock(1))

        db.write { Contacts.insert(ada) }

        assertEquals(listOf(recorded(1, null, ada.json(), 1)), changes(db))
    }

    @Test
    fun `upsert records an insert and a merge`() = runTest {
        val db = database(clock(1))
        val older = ada.copy(age = 37)
        val renamed = older.copy(name = "Ada Lovelace")

        db.write { Contacts.byId.upsert(ada) }
        db.write { Contacts.byId.upsert(older) }
        db.write { Contacts.byEmail.upsert(renamed) }

        assertEquals(
            listOf(
                recorded(1, null, ada.json(), 1),
                recorded(2, ada.json(), older.json(), 1),
                recorded(3, older.json(), renamed.json(), 1),
            ),
            changes(db),
        )
    }

    @Test
    fun `a unique lookup update, replace and delete record before and after`() = runTest {
        val db = database(clock(1))
        val updated = ada.copy(name = "Ada Lovelace")
        val replaced = updated.copy(age = 37)

        db.write { Contacts.insert(ada) }
        db.write { Contacts.byEmail(ada.email).update(updated) }
        db.write { Contacts.byEmail(ada.email).replace(replaced) }
        assertEquals(true, db.write { Contacts.byEmail(ada.email).delete() })

        assertEquals(
            listOf(
                recorded(1, null, ada.json(), 1),
                recorded(2, ada.json(), updated.json(), 1),
                recorded(3, updated.json(), replaced.json(), 1),
                recorded(4, replaced.json(), null, 1),
            ),
            changes(db),
        )
        assertEquals(emptyList(), db.contacts())
    }

    @Test
    fun `a where update, replace and delete record every match`() = runTest {
        val db = database(clock(1))
        val ada99 = ada.copy(age = 99)
        val grace99 = grace.copy(age = 99)
        val replaced = ada99.copy(name = "Augusta")

        db.write {
            Contacts.insert(ada)
            Contacts.insert(grace)
        }
        assertEquals(2, db.write { Contacts.update(buildJsonObject { put("age", 99) }) { "age" eq 36 } })
        assertEquals(1, db.write { Contacts.replace(replaced) { "id" eq ada.id } })
        assertEquals(2, db.write { Contacts.delete { "age" gte 36 } })

        assertEquals(
            setOf(
                null to ada.json(),
                null to grace.json(),
                ada.json() to ada99.json(),
                grace.json() to grace99.json(),
                ada99.json() to replaced.json(),
                replaced.json() to null,
                grace99.json() to null,
            ),
            changes(db).pairs(at = 1),
        )
    }

    @Test
    fun `a many lookup update, replace and delete record every match`() = runTest {
        val db = database(clock(1))
        val ada99 = ada.copy(age = 99)
        val grace99 = grace.copy(age = 99)
        val renamed = alan.copy(name = "Alan Turing")

        db.write {
            Contacts.insert(ada)
            Contacts.insert(alan)
            Contacts.insert(grace)
        }
        assertEquals(2, db.write { Contacts.byAge(36).update(buildJsonObject { put("age", 99) }) })
        assertEquals(1, db.write { Contacts.byAge(41).replace(renamed) })
        assertEquals(2, db.write { Contacts.byAge(99).delete() })

        assertEquals(
            setOf(
                null to ada.json(),
                null to alan.json(),
                null to grace.json(),
                ada.json() to ada99.json(),
                grace.json() to grace99.json(),
                alan.json() to renamed.json(),
                ada99.json() to null,
                grace99.json() to null,
            ),
            changes(db).pairs(at = 1),
        )
    }

    @Test
    fun `a write that matches nothing records nothing`() = runTest {
        val db = database(clock(1))
        db.write { Contacts.insert(ada) }

        db.write {
            assertEquals(0, Contacts.update(ada.copy(age = 1)) { "id" eq "nobody" })
            assertEquals(0, Contacts.replace(ada) { "id" eq "nobody" })
            assertEquals(0, Contacts.delete { "id" eq "nobody" })
            assertEquals(false, Contacts.byEmail("nobody@x.com").delete())
            assertEquals(0, Contacts.byAge(1).delete())
        }

        assertEquals(listOf(recorded(1, null, ada.json(), 1)), changes(db))
    }

    @Test
    fun `changing the primary key is rejected and that statement rolls back`() = runTest {
        val db = database(clock(1))

        db.write {
            Contacts.insert(ada)
            val updated = assertFailsWith<SQLiteException> { Contacts.byId("ada").update(ada.copy(id = "ada2")) }
            val replaced = assertFailsWith<SQLiteException> { Contacts.byId("ada").replace(ada.copy(id = "ada3")) }
            val upserted = assertFailsWith<SQLiteException> { Contacts.byEmail.upsert(ada.copy(id = "ada4")) }
            assertTrue("primary key is immutable" in updated.message.orEmpty())
            assertTrue("primary key is immutable" in replaced.message.orEmpty())
            assertTrue("primary key is immutable" in upserted.message.orEmpty())
            Contacts.insert(alan)
        }

        assertEquals(listOf(ada, alan), db.contacts())
        assertEquals(
            listOf(recorded(1, null, ada.json(), 1), recorded(2, null, alan.json(), 1)),
            changes(db),
        )
    }

    @Test
    fun `a nested primary key cannot change`() = runTest {
        val people = object : Collection("people", tracked = true) {
            val byLast = primaryKey(Named::name / Name::last)
        }
        val db = NoSqlite(clock = clock(1)) { +people }
        val lovelace = Named(Name("Ada", "Lovelace"), 36)

        val error = db.write {
            people.insert(lovelace)
            assertFailsWith<SQLiteException> {
                people.update(buildJsonObject { put("name", buildJsonObject { put("last", "Byron") }) }) {
                    "name.last" eq "Lovelace"
                }
            }
        }

        assertTrue("primary key is immutable" in error.message.orEmpty())
        assertEquals(listOf(lovelace), db.read { people.list<Named>() })
        assertEquals(listOf(recorded(1, null, lovelace.json(), 1, people.name)), changes(db))
    }

    @Test
    fun `untracked writes record nothing`() = runTest {
        val db = database(clock(1))
        val older = ada.copy(age = 37)

        db.write {
            Contacts.insert(ada)
            db.write(untracked = true) {
                Contacts.insert(alan)
                db.write { Contacts.insert(grace) }
            }
            Contacts.byId("ada").update(older)
        }

        assertEquals(
            listOf(recorded(1, null, ada.json(), 1), recorded(2, ada.json(), older.json(), 1)),
            changes(db),
        )
        assertEquals(listOf(older, alan, grace), db.contacts())
    }

    @Test
    fun `a thrown untracked write leaves the flag unset`() = runTest {
        val db = database(clock(1))
        db.write { Contacts.insert(ada) }

        assertFails {
            db.write(untracked = true) {
                Contacts.insert(alan)
                error("rollback")
            }
        }

        assertEquals(listOf(listOf("0")), db.querySql("SELECT untracked FROM __tracking_state"))
        db.write { Contacts.insert(grace) }
        assertEquals(
            listOf(recorded(1, null, ada.json(), 1), recorded(2, null, grace.json(), 1)),
            changes(db),
        )
        assertEquals(listOf(ada, grace), db.contacts())
    }

    @Test
    fun `a thrown nested untracked write does not stick the flag`() = runTest {
        val db = database(clock(1))

        db.write {
            Contacts.insert(ada)
            assertFails {
                db.write(untracked = true) {
                    Contacts.insert(alan)
                    error("rollback")
                }
            }
            Contacts.insert(grace)
        }

        assertEquals(listOf(listOf("0")), db.querySql("SELECT untracked FROM __tracking_state"))
        assertEquals(
            listOf(recorded(1, null, ada.json(), 1), recorded(2, null, grace.json(), 1)),
            changes(db),
        )
        assertEquals(listOf(ada, grace), db.contacts())
    }

    @Test
    fun `the clock timestamps each write`() = runTest {
        val clock = clock(10)
        val db = database(clock)

        db.write {
            Contacts.insert(ada)
            clock.millis = 99
            Contacts.insert(alan)
        }
        clock.millis = 11
        db.write { Contacts.byId("ada").delete() }
        clock.millis = 12
        db.write {
            clock.millis = 13
            db.write { Contacts.insert(grace) }
        }

        assertEquals(
            listOf(
                recorded(1, null, ada.json(), 10),
                recorded(2, null, alan.json(), 10),
                recorded(3, ada.json(), null, 11),
                recorded(4, null, grace.json(), 13),
            ),
            changes(db),
        )
    }

    @Test
    fun `removing pending changes leaves one written later`() = runTest {
        val clock = clock(1)
        val db = database(clock)
        db.write { Contacts.insert(ada) }
        val early = changes(db)
        clock.millis = 2
        db.write { Contacts.insert(alan) }

        db.write {
            removePendingChanges(emptyList())
            removePendingChanges(early)
        }

        assertEquals(listOf(recorded(2, null, alan.json(), 2)), changes(db))
        assertEquals(listOf(ada, alan), db.contacts())
    }

    @Test
    fun `pending changes are read again only after the queue changes`() = runTest {
        val clock = clock(1)
        val db = NoSqlite(dispatcher = UnconfinedTestDispatcher(testScheduler), clock = clock) {
            +Contacts
            +Notes
        }
        val seen = collect(db.pendingChanges())

        db.write { Contacts.insert(ada) }
        assertEquals(1L, db.versions.value[PENDING_CHANGES])
        db.write { Notes.insert(note) }
        db.write(untracked = true) { Contacts.insert(alan) }
        assertEquals(1L, db.versions.value[PENDING_CHANGES])
        assertEquals(2L, db.versions.value[Contacts.name])
        clock.millis = 2
        db.write { Contacts.insert(grace) }
        db.write { removePendingChanges(changes(db)) }

        assertEquals(
            listOf(
                emptyList(),
                listOf(recorded(1, null, ada.json(), 1)),
                listOf(recorded(1, null, ada.json(), 1), recorded(2, null, grace.json(), 2)),
                emptyList(),
            ),
            seen,
        )
    }

    @Test
    fun `a plain collection has no triggers and no pending changes table`() = runTest {
        val db = NoSqlite { +Notes }

        db.write { Notes.insert(note) }

        assertEquals(emptyList(), db.querySql("SELECT name FROM sqlite_schema WHERE type = 'trigger'"))
        assertEquals(emptyList(), db.querySql("SELECT name FROM sqlite_temp_schema WHERE type = 'trigger'"))
        assertEquals(emptyList(), db.querySql("SELECT name FROM sqlite_schema WHERE name = '__pending_changes'"))
    }

    @Test
    fun `only tracked collections get triggers`() = runTest {
        val db = database(clock(1))

        db.write {
            Notes.insert(note)
            Contacts.insert(ada)
        }

        assertEquals(listOf("nsq_contacts_delete", "nsq_contacts_insert", "nsq_contacts_update"), db.tempTriggers())
        assertEquals(emptyList(), db.querySql("SELECT name FROM sqlite_schema WHERE type = 'trigger'"))
        val sql = db.tempTriggerSql().joinToString("\n")
        assertTrue("primary key is immutable" in sql)
        assertTrue("json_extract" in sql)
        assertTrue("OLD.data" in sql && "NEW.data" in sql)
        val table = db.querySql("SELECT sql FROM sqlite_schema WHERE name = '__pending_changes'").single().single()
        assertTrue("AUTOINCREMENT" in table!!)
        assertEquals(listOf(recorded(1, null, ada.json(), 1)), changes(db))
    }

    @Test
    fun `a collection may declare a primary key without tracking`() = runTest {
        val people = object : Collection("people") { val byId = primaryKey("id") }
        val db = NoSqlite { +people }

        db.write {
            people.insert(Person("ada"))
            people.byId("ada").update(Person("ada2"))
        }

        assertEquals(listOf(Person("ada2")), db.read { people.list<Person>() })
        val duplicate = assertFailsWith<NoSqliteException.UniqueViolation> { db.write { people.insert(Person("ada2")) } }
        assertSame(people.byId, duplicate.index)
        assertEquals(emptyList(), db.tempTriggers())
        assertEquals(emptyList(), db.querySql("SELECT name FROM sqlite_schema WHERE name = '__pending_changes'"))
    }

    @Test
    fun `reopening recreates the temp triggers and keeps recorded changes`() = runTest {
        withTempFile { location ->
            val clock = clock(5)
            val first = NoSqlite(location, clock = clock) { +Contacts }
            first.write { Contacts.insert(ada) }
            first.close()

            clock.millis = 6
            val second = NoSqlite(location, clock = clock) { +Contacts }
            second.write { Contacts.byId("ada").update(ada.copy(age = 37)) }

            assertEquals(
                listOf(
                    recorded(1, null, ada.json(), 5),
                    recorded(2, ada.json(), ada.copy(age = 37).json(), 6),
                ),
                changes(second),
            )
            assertEquals(
                listOf("nsq_contacts_delete", "nsq_contacts_insert", "nsq_contacts_update"),
                second.tempTriggers(),
            )
            second.close()
        }
    }

    @Test
    fun `a tracked collection without a primary key fails to open`() = runTest {
        val db = NoSqlite { +Collection("contacts", tracked = true) }

        val error = assertFailsWith<IllegalArgumentException> { db.read { } }

        assertEquals("Tracked collection 'contacts' must declare a primary key", error.message)
    }

    @Test
    fun `a primary key is one field and a collection declares it once`() {
        val fields = emptyArray<String>()
        val none = assertFailsWith<IllegalArgumentException> {
            object : Collection("contacts", tracked = true) { val key = primaryKey(*fields) }
        }
        val two = assertFailsWith<IllegalArgumentException> {
            object : Collection("contacts", tracked = true) { val key = primaryKey("id", "email") }
        }
        val again = assertFailsWith<IllegalArgumentException> {
            object : Collection("contacts", tracked = true) {
                val byId = primaryKey("id")
                val byEmail = primaryKey("email")
            }
        }

        assertEquals("Collection 'contacts' primary key must be one field, not 0", none.message)
        assertEquals("Collection 'contacts' primary key must be one field, not 2", two.message)
        assertEquals("Collection 'contacts' declares more than one primary key", again.message)
    }
}
