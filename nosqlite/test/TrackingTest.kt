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
import kotlin.uuid.Uuid

/** Covers change tracking: triggers, the pending queue, and untracked writes. */
internal class TrackingTest {

    @Serializable
    data class Contact(val id: Uuid, val email: String, val name: String, val age: Int)

    object Contacts : Collection("contacts", tracked = true) {
        val byAge = index("age")
    }

    object Notes : Collection("notes")

    private val ada = contact("ada", 36)
    private val alan = contact("alan", 41)
    private val grace = contact("grace", 36)
    private val note = buildJsonObject { put("id", uuid("note").toString()); put("text", "hi") }
    private val anotherNote = buildJsonObject { put("id", uuid("note-2").toString()); put("text", "hi") }

    private fun contact(id: String, age: Int, name: String = id.replaceFirstChar { it.uppercase() }) =
        Contact(uuid(id), "$id@x.com", name, age)

    private fun database() = NoSqlite {
        +Contacts
        +Notes
    }

    private fun Contact.json(): JsonObject =
        NoSqlite.Json.parseToJsonElement(NoSqlite.Json.encodeToString(this)) as JsonObject

    private fun recorded(
        id: Long,
        before: JsonObject?,
        after: JsonObject?,
        collection: String = Contacts.name,
    ) = PendingChange(id, Uuid.NIL, collection, before, after, Instant.fromEpochMilliseconds(0))

    private fun inserted(id: Long, contact: Contact) = recorded(id, null, contact.json())

    private fun updated(id: Long, before: Contact, after: Contact) = recorded(id, before.json(), after.json())

    private fun deleted(id: Long, contact: Contact) = recorded(id, contact.json(), null)

    private suspend fun changes(db: NoSqlite): List<PendingChange> = db.read { pendingChanges() }.withoutTime()

    /** Drops the clock and the random uid so an assertion can name the change itself. */
    private fun List<PendingChange>.withoutTime() =
        map { it.copy(timestamp = Instant.fromEpochMilliseconds(0), uid = Uuid.NIL) }

    private data class Recorded(
        val documentId: Uuid,
        val before: JsonObject?,
        val after: JsonObject?,
    )

    /** Each change, with queue ids 1..n in order and no duplicate record. */
    private fun List<PendingChange>.bodies(): Set<Recorded> {
        assertEquals((1L..size).toList(), map { it.id })
        assertTrue(all { it.collection == Contacts.name })
        return map { Recorded(it.documentId, it.before, it.after) }.toSet().also { assertEquals(size, it.size) }
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

        assertEquals(listOf(inserted(1, ada)), changes(db))
    }

    @Test
    fun `upsert records an insert and a merge`() = runTest {
        val db = database()
        val older = ada.copy(age = 37)
        val renamed = older.copy(name = "Ada Lovelace")

        db.write { Contacts.upsert(ada) }
        db.write { Contacts.upsert(older) }
        db.write { Contacts.upsert(renamed) }

        assertEquals(
            listOf(
                inserted(1, ada),
                updated(2, ada, older),
                updated(3, older, renamed),
            ),
            changes(db),
        )
    }

    @Test
    fun `an id update replace and delete record before and after`() = runTest {
        val db = database()
        val renamed = ada.copy(name = "Ada Lovelace")
        val replaced = renamed.copy(age = 37)

        db.write { Contacts.insert(ada) }
        db.write { Contacts.id(ada.id).update(renamed) }
        db.write { Contacts.id(ada.id).replace(replaced) }
        assertEquals(true, db.write { Contacts.id(ada.id).delete() })

        assertEquals(
            listOf(
                inserted(1, ada),
                updated(2, ada, renamed),
                updated(3, renamed, replaced),
                deleted(4, replaced),
            ),
            changes(db),
        )
        assertEquals(emptyList(), db.contacts())
    }

    @Test
    fun `a where update replace and delete record every match`() = runTest {
        val db = database()
        val agedAda = ada.copy(age = 99)
        val agedGrace = grace.copy(age = 99)
        val replaced = ada.copy(age = 99, name = "Augusta")

        db.write {
            Contacts.insert(ada)
            Contacts.insert(grace)
        }
        assertEquals(2, db.write { Contacts.update(buildJsonObject { put("age", 99) }) { "age" eq 36 } })
        assertEquals(1, db.write { Contacts.replace(replaced) { "id" eq ada.id.toString() } })
        assertEquals(2, db.write { Contacts.delete { "age" gte 36 } })

        assertEquals(
            setOf(
                Recorded(ada.id, null, ada.json()),
                Recorded(grace.id, null, grace.json()),
                Recorded(ada.id, ada.json(), agedAda.json()),
                Recorded(grace.id, grace.json(), agedGrace.json()),
                Recorded(ada.id, agedAda.json(), replaced.json()),
                Recorded(ada.id, replaced.json(), null),
                Recorded(grace.id, agedGrace.json(), null),
            ),
            changes(db).bodies(),
        )
    }

    @Test
    fun `a many lookup update replace and delete record every match`() = runTest {
        val db = database()
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
                Recorded(ada.id, null, ada.json()),
                Recorded(alan.id, null, alan.json()),
                Recorded(grace.id, null, grace.json()),
                Recorded(ada.id, ada.json(), ada.copy(age = 99).json()),
                Recorded(grace.id, grace.json(), grace.copy(age = 99).json()),
                Recorded(alan.id, alan.json(), renamed.json()),
                Recorded(ada.id, ada.copy(age = 99).json(), null),
                Recorded(grace.id, grace.copy(age = 99).json(), null),
            ),
            changes(db).bodies(),
        )
    }

    @Test
    fun `a write that matches nothing records nothing`() = runTest {
        val db = database()
        db.write { Contacts.insert(ada) }

        db.write {
            assertEquals(0, Contacts.update(ada.copy(age = 1)) { "id" eq uuid("nobody").toString() })
            assertEquals(0, Contacts.replace(ada) { "id" eq uuid("nobody").toString() })
            assertEquals(0, Contacts.delete { "id" eq uuid("nobody").toString() })
            assertEquals(false, Contacts.id(uuid("nobody")).delete())
            assertEquals(0, Contacts.byAge(1).delete())
        }

        assertEquals(listOf(inserted(1, ada)), changes(db))
    }

    @Test
    fun `a by-name change in a tracked write is recorded`() = runTest {
        val db = database()
        val older = ada.copy(age = 37)

        db.write { Contacts.insert(ada) }
        db.write {
            upsert("contacts", older.json())
            assertTrue(delete("contacts", ada.id))
        }

        assertEquals(listOf(inserted(1, ada), updated(2, ada, older), deleted(3, older)), changes(db))
    }

    @Test
    fun `a by-name change in an untracked write records nothing`() = runTest {
        val db = database()

        db.write { Contacts.insert(ada) }
        db.write(untracked = true) {
            upsert("contacts", alan.json())
            delete("contacts", ada.id)
            upsert("fresh", note)
        }

        assertEquals(listOf(inserted(1, ada)), changes(db))
        assertEquals(listOf(alan), db.contacts())
        assertEquals(listOf(note), db.read { Fresh.list<JsonObject>() })
    }

    @Test
    fun `untracked writes record nothing`() = runTest {
        val db = database()
        val older = ada.copy(age = 37)

        db.write { Contacts.insert(ada) }
        db.write(untracked = true) { Contacts.insert(alan) }
        db.write { Contacts.id(ada.id).update(older) }

        assertEquals(
            listOf(inserted(1, ada), updated(2, ada, older)),
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
        assertEquals(listOf(inserted(1, ada)), changes(db))
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

        assertEquals(listOf(listOf("0")), db.querySql("SELECT untracked FROM nsq__tracking_state"))
        db.write { Contacts.insert(grace) }
        assertEquals(
            listOf(inserted(1, ada), inserted(2, grace)),
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
        db.write { Contacts.id(ada.id).delete() }

        val after = Clock.System.now()
        val rows = db.read { pendingChanges() }
        assertEquals(listOf(1L, 2L, 3L), rows.map { it.id })
        assertEquals(
            listOf(inserted(1, ada), inserted(2, alan), deleted(3, ada)),
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

        assertEquals(listOf(inserted(2, alan)), changes(db))
        assertEquals(listOf(ada, alan), db.contacts())
    }

    @Test
    fun `a plain collection keeps its id trigger and records nothing`() = runTest {
        val db = NoSqlite { +Notes }

        db.write { Notes.insert(note) }

        assertEquals(emptyList(), db.read { pendingChanges() })
        assertEquals(
            listOf(listOf("nsq_notes_id_immutable")),
            db.querySql("SELECT name FROM sqlite_schema WHERE type = 'trigger'"),
        )
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
            listOf(
                "nsq_contacts_delete",
                "nsq_contacts_insert",
                "nsq_contacts_update",
            ),
            db.tempTriggers(),
        )
        assertEquals(
            listOf(listOf("nsq_contacts_id_immutable"), listOf("nsq_notes_id_immutable")),
            db.querySql("SELECT name FROM sqlite_schema WHERE type = 'trigger' ORDER BY name"),
        )
        val sql = db.tempTriggerSql().joinToString("\n")
        assertTrue("OLD.data" in sql && "NEW.data" in sql)
        val table = db.querySql("SELECT sql FROM sqlite_schema WHERE name = 'nsq__pending_changes'").single().single()
        assertTrue("AUTOINCREMENT" in table!!)
        assertEquals(listOf(inserted(1, ada)), changes(db))
    }

    @Test
    fun `stored trigger names use an underscore suffix and no colon`() = runTest {
        val db = database()
        db.read { }

        val main = db.querySql("SELECT name FROM sqlite_master WHERE type = 'trigger' ORDER BY name")
            .map { it.single()!! }
        val temp = db.tempTriggers()

        assertEquals(listOf("nsq_contacts_id_immutable", "nsq_notes_id_immutable"), main)
        assertEquals(listOf("nsq_contacts_delete", "nsq_contacts_insert", "nsq_contacts_update"), temp)
        assertTrue((main + temp).all { ':' !in it })
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
        assertTrue("nsq_notes" in log.single())
        assertTrue(log.none { "nsq__tracking_state" in it || "nsq__pending_changes" in it })

        log.clear()
        db.write(untracked = true) { Notes.insert(anotherNote) }
        assertEquals(listOf("""UPDATE "nsq__tracking_state" SET untracked = ?;"""), log)
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
            assertEquals(listOf(inserted(1, ada), inserted(2, grace)), changes(db))
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
            second.write { Contacts.id(ada.id).update(ada.copy(age = 37)) }

            assertEquals(
                listOf(
                    inserted(1, ada),
                    updated(2, ada, ada.copy(age = 37)),
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
            PendingChange(
                id,
                Uuid.NIL,
                Contacts.name,
                null,
                null,
                Instant.fromEpochMilliseconds(0),
            )
        }

        db.write { removePendingChanges(listOf(first) + extra) }

        assertEquals(listOf(inserted(2, alan)), changes(db))
        assertEquals(listOf(ada, alan), db.contacts())
    }

    @Test
    fun `insert and delete record the document before and after`() = runTest {
        val db = database()
        db.write { Contacts.insert(ada) }
        db.write { Contacts.id(ada.id).delete() }

        val rows = changes(db)
        assertEquals(listOf(inserted(1, ada), deleted(2, ada)), rows)
        assertEquals(ada.id, rows[0].documentId)
        assertEquals(null, rows[0].before)
        assertEquals(ada.json(), rows[0].after)
        assertEquals(ada.json(), rows[1].before)
        assertEquals(null, rows[1].after)
    }

    @Test
    fun `a no-op update queues nothing`() = runTest {
        val db = database()
        db.write { Contacts.insert(ada) }

        db.write { Contacts.update(buildJsonObject { put("age", ada.age) }) { "id" eq ada.id.toString() } }
        db.write { Contacts.upsert(ada) }

        assertEquals(listOf(inserted(1, ada)), changes(db))
        assertEquals(listOf(ada), db.contacts())
    }

    @Test
    fun `every queued change has a unique uuid uid`() = runTest {
        val db = database()
        val first = contact("p0", 1)
        db.write {
            repeat(32) { n -> Contacts.insert(contact("p$n", n)) }
            Contacts.id(first.id).update(first.copy(age = 2))
            Contacts.id(first.id).update(first.copy(age = 3))
            Contacts.id(first.id).delete()
        }

        val rows = db.read { pendingChanges() }
        val sameDocument = rows.filter { it.documentId == first.id }
        assertEquals(4, sameDocument.size)
        assertTrue(rows.size > sameDocument.size)
        assertEquals(rows.size, rows.map { it.uid }.toSet().size)
        for (row in rows) {
            val text = row.uid.toString()
            assertEquals('4', text[14], text)
            assertTrue(text[19] in "89ab", text)
        }
    }

    @Test
    fun `a pending change uid stays the same until the row is removed`() = runTest {
        withTempFile { location ->
            val db = NoSqlite(location) { +Contacts }
            db.write { Contacts.insert(ada) }
            val written = db.read { pendingChanges() }.single()
            assertEquals(written.uid, db.read { pendingChanges() }.single().uid)
            db.write { Contacts.id(ada.id).update(ada.copy(age = 37)) }
            val both = db.read { pendingChanges() }
            assertEquals(written.uid, both.first().uid)
            db.close()

            val reopened = NoSqlite(location) { +Contacts }
            val kept = reopened.read { pendingChanges() }
            assertEquals(written.uid, kept.first().uid)
            assertEquals(both.map { it.uid }, kept.map { it.uid })
            reopened.write { removePendingChanges(listOf(kept.first())) }
            assertEquals(listOf(kept.last().uid), reopened.read { pendingChanges() }.map { it.uid })
            reopened.write { removePendingChanges(reopened.read { pendingChanges() }) }
            assertEquals(emptyList(), reopened.read { pendingChanges() })
            reopened.close()
        }
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

private object Fresh : Collection("fresh")
