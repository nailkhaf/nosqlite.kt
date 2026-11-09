package io.github.nailkhaf.nosqlite

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/** Covers change tracking: triggers, the pending queue, and untracked writes. */
internal class TrackingTest {

    @Serializable
    data class Contact(val id: String, val email: String, val name: String, val age: Int)

    object Contacts : Collection("contacts", tracked = true) {
        val byId = unique("id")
        val byEmail = unique("email")
        val byAge = index("age")
    }

    object Notes : Collection("notes")

    private val ada = contact("ada", 36)
    private val alan = contact("alan", 41)
    private val grace = contact("grace", 36)
    private val note = buildJsonObject { put("text", "hi") }

    private fun contact(id: String, age: Int, name: String = id.replaceFirstChar { it.uppercase() }) =
        Contact(id, "$id@x.com", name, age)

    private fun database() = NoSqlite {
        +Contacts
        +Notes
    }

    private fun Contact.json(): JsonObject =
        NoSqlite.Json.parseToJsonElement(NoSqlite.Json.encodeToString(this)) as JsonObject

    private fun recorded(id: Long, before: JsonObject?, after: JsonObject?, collection: String = Contacts.name) = PendingChange(id, collection, before, after, Instant.fromEpochMilliseconds(0))

    private suspend fun changes(db: NoSqlite): List<PendingChange> = db.read { pendingChanges() }.withoutTime()

    private fun List<PendingChange>.withoutTime() = map { it.copy(timestamp = Instant.fromEpochMilliseconds(0)) }

    /** Before and after of each change, with ids 1..n in order and no duplicate pair. */
    private fun List<PendingChange>.pairs(): Set<Pair<JsonObject?, JsonObject?>> {
        assertEquals((1L..size).toList(), map { it.id })
        assertTrue(all { it.collection == Contacts.name })
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

    @Test
    fun `insert records the new document`() = runTest {
        val db = database()

        db.write { Contacts.insert(ada) }

        assertEquals(listOf(recorded(1, null, ada.json())), changes(db))
    }

    @Test
    fun `upsert records an insert and a merge`() = runTest {
        val db = database()
        val older = ada.copy(age = 37)
        val renamed = older.copy(name = "Ada Lovelace")

        db.write { Contacts.byId.upsert(ada) }
        db.write { Contacts.byId.upsert(older) }
        db.write { Contacts.byEmail.upsert(renamed) }

        assertEquals(
            listOf(
                recorded(1, null, ada.json()),
                recorded(2, ada.json(), older.json()),
                recorded(3, older.json(), renamed.json()),
            ),
            changes(db),
        )
    }

    @Test
    fun `a unique lookup update replace and delete record before and after`() = runTest {
        val db = database()
        val updated = ada.copy(name = "Ada Lovelace")
        val replaced = updated.copy(age = 37)

        db.write { Contacts.insert(ada) }
        db.write { Contacts.byEmail(ada.email).update(updated) }
        db.write { Contacts.byEmail(ada.email).replace(replaced) }
        assertEquals(true, db.write { Contacts.byEmail(ada.email).delete() })

        assertEquals(
            listOf(
                recorded(1, null, ada.json()),
                recorded(2, ada.json(), updated.json()),
                recorded(3, updated.json(), replaced.json()),
                recorded(4, replaced.json(), null),
            ),
            changes(db),
        )
        assertEquals(emptyList(), db.contacts())
    }

    @Test
    fun `a where update replace and delete record every match`() = runTest {
        val db = database()
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
            changes(db).pairs(),
        )
    }

    @Test
    fun `a many lookup update replace and delete record every match`() = runTest {
        val db = database()
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
            changes(db).pairs(),
        )
    }

    @Test
    fun `a write that matches nothing records nothing`() = runTest {
        val db = database()
        db.write { Contacts.insert(ada) }

        db.write {
            assertEquals(0, Contacts.update(ada.copy(age = 1)) { "id" eq "nobody" })
            assertEquals(0, Contacts.replace(ada) { "id" eq "nobody" })
            assertEquals(0, Contacts.delete { "id" eq "nobody" })
            assertEquals(false, Contacts.byEmail("nobody@x.com").delete())
            assertEquals(0, Contacts.byAge(1).delete())
        }

        assertEquals(listOf(recorded(1, null, ada.json())), changes(db))
    }

    @Test
    fun `untracked writes record nothing`() = runTest {
        val db = database()
        val older = ada.copy(age = 37)

        db.write { Contacts.insert(ada) }
        db.write(untracked = true) { Contacts.insert(alan) }
        db.write { Contacts.byId("ada").update(older) }

        assertEquals(
            listOf(recorded(1, null, ada.json()), recorded(2, ada.json(), older.json())),
            changes(db),
        )
        assertEquals(listOf(older, alan), db.contacts())
    }

    @Test
    fun `an untracked write cannot nest`() = runTest {
        val db = database()

        val error = db.write {
            Contacts.insert(ada)
            assertFailsWith<IllegalStateException> { db.write(untracked = true) { Contacts.insert(alan) } }
        }

        assertEquals("Untracked write cannot nest inside another write", error.message)
        assertEquals(listOf(recorded(1, null, ada.json())), changes(db))
        assertEquals(listOf(ada), db.contacts())
    }

    @Test
    fun `a thrown untracked write leaves the flag unset`() = runTest {
        val db = database()
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
            listOf(recorded(1, null, ada.json()), recorded(2, null, grace.json())),
            changes(db),
        )
        assertEquals(listOf(ada, grace), db.contacts())
    }

    @Test
    fun `timestamps fall in the write window and rows stay ordered by id`() = runTest {
        val db = database()
        // Changes record whole milliseconds, so the window starts at the current one.
        val before = Instant.fromEpochMilliseconds(Clock.System.now().toEpochMilliseconds())

        db.write { Contacts.insert(ada) }
        db.write { Contacts.insert(alan) }
        db.write { Contacts.byId("ada").delete() }

        val after = Clock.System.now()
        val rows = db.read { pendingChanges() }
        assertEquals(listOf(1L, 2L, 3L), rows.map { it.id })
        assertEquals(
            listOf(recorded(1, null, ada.json()), recorded(2, null, alan.json()), recorded(3, ada.json(), null)),
            rows.withoutTime(),
        )
        assertTrue(rows.all { it.timestamp in before..after })
    }

    @Test
    fun `removing pending changes leaves one written later`() = runTest {
        val db = database()
        db.write { Contacts.insert(ada) }
        val early = changes(db)
        db.write { Contacts.insert(alan) }

        db.write {
            removePendingChanges(emptyList())
            removePendingChanges(early)
        }

        assertEquals(listOf(recorded(2, null, alan.json())), changes(db))
        assertEquals(listOf(ada, alan), db.contacts())
    }

    @Test
    fun `a plain collection has no triggers and no pending changes`() = runTest {
        val db = NoSqlite { +Notes }

        db.write { Notes.insert(note) }

        assertEquals(emptyList(), db.read { pendingChanges() })
        assertEquals(emptyList(), db.querySql("SELECT name FROM sqlite_schema WHERE type = 'trigger'"))
        assertEquals(emptyList(), db.querySql("SELECT name FROM sqlite_temp_schema WHERE type = 'trigger'"))
    }

    @Test
    fun `only tracked collections get triggers`() = runTest {
        val db = database()

        db.write {
            Notes.insert(note)
            Contacts.insert(ada)
        }

        assertEquals(
            listOf("nsq_contacts_delete", "nsq_contacts_insert", "nsq_contacts_update"),
            db.tempTriggers(),
        )
        assertEquals(emptyList(), db.querySql("SELECT name FROM sqlite_schema WHERE type = 'trigger'"))
        val sql = db.tempTriggerSql().joinToString("\n")
        assertTrue("OLD.data" in sql && "NEW.data" in sql)
        val table = db.querySql("SELECT sql FROM sqlite_schema WHERE name = '__pending_changes'").single().single()
        assertTrue("AUTOINCREMENT" in table!!)
        assertEquals(listOf(recorded(1, null, ada.json())), changes(db))
    }

    @Test
    fun `a plain write in a tracked database adds no statements`() = runTest {
        val log = mutableListOf<String>()
        val db = NoSqlite(driver = SqlLog(log)) {
            +Contacts
            +Notes
        }
        db.write { Contacts.insert(ada) }
        log.clear()

        db.write { Notes.insert(note) }

        assertEquals(1, log.size, log.joinToString("\n"))
        assertTrue("notes" in log.single())
        assertTrue(log.none { "__tracking_state" in it || "__pending_changes" in it })

        log.clear()
        db.write(untracked = true) { Notes.insert(note) }
        assertEquals(listOf("UPDATE __tracking_state SET untracked = ?;"), log)
    }

    @Test
    fun `a writer replaced after a failed rollback still records changes`() = runTest {
        withTempFile { location ->
            val driver = FailingDriver("ROLLBACK;", times = 2)
            val db = NoSqlite(location, driver) { +Contacts }
            db.write { Contacts.insert(ada) }

            assertFails {
                db.write {
                    Contacts.insert(alan)
                    error("rollback")
                }
            }
            db.write { Contacts.insert(grace) }

            assertTrue(driver.connections.first().closed)
            assertEquals(listOf(recorded(1, null, ada.json()), recorded(2, null, grace.json())), changes(db))
            db.close()
        }
    }

    @Test
    fun `reopening recreates the temp triggers and keeps recorded changes`() = runTest {
        withTempFile { location ->
            val first = NoSqlite(location) { +Contacts }
            first.write { Contacts.insert(ada) }
            first.close()

            val second = NoSqlite(location) { +Contacts }
            second.write { Contacts.byId("ada").update(ada.copy(age = 37)) }

            assertEquals(
                listOf(
                    recorded(1, null, ada.json()),
                    recorded(2, ada.json(), ada.copy(age = 37).json()),
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
    fun `removing tens of thousands of ids in one call deletes only those ids`() = runTest {
        val db = database()
        db.write { Contacts.insert(ada) }
        val first = db.read { pendingChanges() }.single()
        db.write { Contacts.insert(alan) }
        val extra = (3L..32_768L).map { id ->
            PendingChange(id, Contacts.name, null, null, Instant.fromEpochMilliseconds(0))
        }

        db.write { removePendingChanges(listOf(first) + extra) }

        assertEquals(listOf(recorded(2, null, alan.json())), changes(db))
        assertEquals(listOf(ada, alan), db.contacts())
    }
}

private class SqlLog(private val log: MutableList<String>) : SQLiteDriver {
    private val bundled = BundledSQLiteDriver()

    override fun open(fileName: String): SQLiteConnection {
        val sqlite = bundled.open(fileName)
        return object : SQLiteConnection by sqlite {
            override fun prepare(sql: String): SQLiteStatement {
                log += sql
                return sqlite.prepare(sql)
            }
        }
    }
}
