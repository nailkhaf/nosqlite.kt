package io.github.nailkhaf.nosqlite

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
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
        val byAge = index("age")
    }

    object Notes : Collection("notes")

    object People : Collection("people", tracked = true)

    private val ada = contact("ada", 36)
    private val alan = contact("alan", 41)
    private val grace = contact("grace", 36)
    private val note = buildJsonObject { put("id", uuid("note")); put("text", "hi") }
    private val anotherNote = buildJsonObject { put("id", uuid("note-2")); put("text", "hi") }

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
        documentId: String,
        action: PendingChange.Action,
        changeSet: JsonObject,
        collection: String = Contacts.name,
    ) = PendingChange(id, collection, documentId, action, changeSet, Instant.fromEpochMilliseconds(0))

    private fun inserted(id: Long, contact: Contact) = recorded(id, contact.id, PendingChange.Action.Insert, contact.json())

    private fun updated(id: Long, documentId: String, changeSet: JsonObject) =
        recorded(id, documentId, PendingChange.Action.Update, changeSet)

    private fun deleted(id: Long, documentId: String) =
        recorded(id, documentId, PendingChange.Action.Delete, buildJsonObject { })

    private suspend fun changes(db: NoSqlite): List<PendingChange> = db.read { pendingChanges() }.withoutTime()

    private fun List<PendingChange>.withoutTime() = map { it.copy(timestamp = Instant.fromEpochMilliseconds(0)) }

    /** Each change, with queue ids 1..n in order and no duplicate triple. */
    private fun List<PendingChange>.bodies(): Set<Triple<String, PendingChange.Action, JsonObject>> {
        assertEquals((1L..size).toList(), map { it.id })
        assertTrue(all { it.collection == Contacts.name })
        return map { Triple(it.documentId, it.action, it.changeSet) }.toSet().also { assertEquals(size, it.size) }
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
                updated(2, ada.id, buildJsonObject { put("age", 37) }),
                updated(3, ada.id, buildJsonObject { put("name", "Ada Lovelace") }),
            ),
            changes(db),
        )
    }

    @Test
    fun `an id update replace and delete record the change set`() = runTest {
        val db = database()
        val updated = ada.copy(name = "Ada Lovelace")
        val replaced = updated.copy(age = 37)

        db.write { Contacts.insert(ada) }
        db.write { Contacts.id(ada.id).update(updated) }
        db.write { Contacts.id(ada.id).replace(replaced) }
        assertEquals(true, db.write { Contacts.id(ada.id).delete() })

        assertEquals(
            listOf(
                inserted(1, ada),
                updated(2, ada.id, buildJsonObject { put("name", "Ada Lovelace") }),
                updated(3, ada.id, buildJsonObject { put("age", 37) }),
                deleted(4, ada.id),
            ),
            changes(db),
        )
        assertEquals(emptyList(), db.contacts())
    }

    @Test
    fun `a where update replace and delete record every match`() = runTest {
        val db = database()
        val replaced = ada.copy(age = 99, name = "Augusta")

        db.write {
            Contacts.insert(ada)
            Contacts.insert(grace)
        }
        assertEquals(2, db.write { Contacts.update(buildJsonObject { put("age", 99) }) { "age" eq 36 } })
        assertEquals(1, db.write { Contacts.replace(replaced) { "id" eq ada.id } })
        assertEquals(2, db.write { Contacts.delete { "age" gte 36 } })

        assertEquals(
            setOf(
                Triple(ada.id, PendingChange.Action.Insert, ada.json()),
                Triple(grace.id, PendingChange.Action.Insert, grace.json()),
                Triple(ada.id, PendingChange.Action.Update, buildJsonObject { put("age", 99) }),
                Triple(grace.id, PendingChange.Action.Update, buildJsonObject { put("age", 99) }),
                Triple(ada.id, PendingChange.Action.Update, buildJsonObject { put("name", "Augusta") }),
                Triple(ada.id, PendingChange.Action.Delete, buildJsonObject { }),
                Triple(grace.id, PendingChange.Action.Delete, buildJsonObject { }),
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
                Triple(ada.id, PendingChange.Action.Insert, ada.json()),
                Triple(alan.id, PendingChange.Action.Insert, alan.json()),
                Triple(grace.id, PendingChange.Action.Insert, grace.json()),
                Triple(ada.id, PendingChange.Action.Update, buildJsonObject { put("age", 99) }),
                Triple(grace.id, PendingChange.Action.Update, buildJsonObject { put("age", 99) }),
                Triple(alan.id, PendingChange.Action.Update, buildJsonObject { put("name", "Alan Turing") }),
                Triple(ada.id, PendingChange.Action.Delete, buildJsonObject { }),
                Triple(grace.id, PendingChange.Action.Delete, buildJsonObject { }),
            ),
            changes(db).bodies(),
        )
    }

    @Test
    fun `a write that matches nothing records nothing`() = runTest {
        val db = database()
        db.write { Contacts.insert(ada) }

        db.write {
            assertEquals(0, Contacts.update(ada.copy(age = 1)) { "id" eq uuid("nobody") })
            assertEquals(0, Contacts.replace(ada) { "id" eq uuid("nobody") })
            assertEquals(0, Contacts.delete { "id" eq uuid("nobody") })
            assertEquals(false, Contacts.id(uuid("nobody")).delete())
            assertEquals(0, Contacts.byAge(1).delete())
        }

        assertEquals(listOf(inserted(1, ada)), changes(db))
    }

    @Test
    fun `untracked writes record nothing`() = runTest {
        val db = database()
        val older = ada.copy(age = 37)

        db.write { Contacts.insert(ada) }
        db.write(untracked = true) { Contacts.insert(alan) }
        db.write { Contacts.id(ada.id).update(older) }

        assertEquals(
            listOf(inserted(1, ada), updated(2, ada.id, buildJsonObject { put("age", 37) })),
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

        assertEquals(listOf(listOf("0")), db.querySql("SELECT untracked FROM __tracking_state"))
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
            listOf(inserted(1, ada), inserted(2, alan), deleted(3, ada.id)),
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
    fun `a plain collection has no triggers and no pending changes`() = runTest {
        val db = NoSqlite { +Notes }

        db.write { Notes.insert(note) }

        assertEquals(emptyList(), db.read { pendingChanges() })
        assertEquals(emptyList(), db.querySql("SELECT name FROM sqlite_schema WHERE type = 'trigger'"))
        assertEquals(
            listOf(listOf("nsq_notes_id")),
            db.querySql("SELECT name FROM sqlite_temp_schema WHERE type = 'trigger'"),
        )
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
                "nsq_contacts_id",
                "nsq_contacts_insert",
                "nsq_contacts_update",
                "nsq_notes_id",
            ),
            db.tempTriggers(),
        )
        assertEquals(emptyList(), db.querySql("SELECT name FROM sqlite_schema WHERE type = 'trigger'"))
        val sql = db.tempTriggerSql().joinToString("\n")
        assertTrue("OLD.data" in sql && "NEW.data" in sql)
        val table = db.querySql("SELECT sql FROM sqlite_schema WHERE name = '__pending_changes'").single().single()
        assertTrue("AUTOINCREMENT" in table!!)
        assertEquals(listOf(inserted(1, ada)), changes(db))
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
        db.write(untracked = true) { Notes.insert(anotherNote) }
        assertEquals(
            listOf(
                "UPDATE __tracking_state SET untracked = ?;",
                "SELECT 1 FROM sqlite_schema WHERE type = 'table' AND name = ?;",
            ),
            log,
        )
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
                    updated(2, ada.id, buildJsonObject { put("age", 37) }),
                ),
                changes(second),
            )
            assertEquals(
                listOf("nsq_contacts_delete", "nsq_contacts_id", "nsq_contacts_insert", "nsq_contacts_update"),
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
                Contacts.name,
                ada.id,
                PendingChange.Action.Delete,
                buildJsonObject { },
                Instant.fromEpochMilliseconds(0),
            )
        }

        db.write { removePendingChanges(listOf(first) + extra) }

        assertEquals(listOf(inserted(2, alan)), changes(db))
        assertEquals(listOf(ada, alan), db.contacts())
    }

    @Test
    fun `an update of a nested field records only that field`() = runTest {
        val db = NoSqlite { +People }
        val id = uuid("ada")
        val original = nested(id, "Lovelace", listOf("a", "b"))
        db.write { People.insert(original) }

        db.write { People.replace(nested(id, "Byron", listOf("a", "b"))) { "id" eq id } }

        assertEquals(
            listOf(
                recorded(1, id, PendingChange.Action.Insert, original, People.name),
                recorded(
                    2,
                    id,
                    PendingChange.Action.Update,
                    buildJsonObject { put("name", buildJsonObject { put("last", "Byron") }) },
                    People.name,
                ),
            ),
            db.read { pendingChanges() }.withoutTime(),
        )
    }

    @Test
    fun `a removed nested key is null at that path`() = runTest {
        val db = NoSqlite { +People }
        val id = uuid("ada")
        db.write { People.insert(nested(id, "Lovelace", listOf("a", "b"))) }
        val removed = buildJsonObject {
            put("id", id)
            put("name", buildJsonObject { put("first", "Ada") })
            put("age", 36)
            put("tags", buildJsonArray { add(JsonPrimitive("a")); add(JsonPrimitive("b")) })
        }

        db.write { People.replace(removed) { "id" eq id } }

        assertEquals(
            buildJsonObject { put("name", buildJsonObject { put("last", JsonNull) }) },
            db.read { pendingChanges() }.last().changeSet,
        )
    }

    @Test
    fun `an array change replaces the array`() = runTest {
        val db = NoSqlite { +People }
        val id = uuid("ada")
        db.write { People.insert(nested(id, "Lovelace", listOf("a", "b"))) }

        db.write { People.replace(nested(id, "Lovelace", listOf("a", "c"))) { "id" eq id } }

        assertEquals(
            buildJsonObject { put("tags", buildJsonArray { add(JsonPrimitive("a")); add(JsonPrimitive("c")) }) },
            db.read { pendingChanges() }.last().changeSet,
        )
    }

    @Test
    fun `insert records the full document and delete records an empty object`() = runTest {
        val db = database()
        db.write { Contacts.insert(ada) }
        db.write { Contacts.id(ada.id).delete() }

        val rows = changes(db)
        assertEquals(listOf(inserted(1, ada), deleted(2, ada.id)), rows)
        assertEquals(ada.json(), rows[0].changeSet)
        assertEquals(buildJsonObject { }, rows[1].changeSet)
    }

    @Test
    fun `a no-op update queues nothing`() = runTest {
        val db = database()
        db.write { Contacts.insert(ada) }

        db.write { Contacts.update(buildJsonObject { put("age", ada.age) }) { "id" eq ada.id } }
        db.write { Contacts.upsert(ada) }

        assertEquals(listOf(inserted(1, ada)), changes(db))
        assertEquals(listOf(ada), db.contacts())
    }

    @Test
    fun `a boolean change stays a boolean`() = runTest {
        val db = NoSqlite { +People }
        val id = uuid("ada")
        db.write { People.insert(buildJsonObject { put("id", id); put("done", false) }) }

        db.write { People.replace(buildJsonObject { put("id", id); put("done", true) }) { "id" eq id } }

        assertEquals(buildJsonObject { put("done", true) }, db.read { pendingChanges() }.last().changeSet)
    }

    @Test
    fun `a number changed to a boolean is recorded`() = runTest {
        val db = NoSqlite { +People }
        val id = uuid("ada")
        db.write { People.insert(buildJsonObject { put("id", id); put("done", 1) }) }

        db.write { People.replace(buildJsonObject { put("id", id); put("done", true) }) { "id" eq id } }

        assertEquals(buildJsonObject { put("done", true) }, db.read { pendingChanges() }.last().changeSet)
    }

    @Test
    fun `keys with brackets dots and quotes are diffed`() = runTest {
        val db = NoSqlite { +People }
        val id = uuid("ada")
        val quote = "say \"hi\""
        db.write {
            People.insert(
                buildJsonObject {
                    put("id", id)
                    put("a[0]", "old")
                    put("a[b", "gone")
                    put("a.b", 1)
                    put(quote, "x")
                    put("n", buildJsonObject { put("a[0]", "old") })
                },
            )
        }

        db.write {
            People.replace(
                buildJsonObject {
                    put("id", id)
                    put("a[0]", "new")
                    put(quote, "y")
                    put("x]", true)
                    put("n", buildJsonObject { put("a[0]", "new"); put("mid", JsonNull) })
                },
            ) { "id" eq id }
        }

        assertEquals(
            buildJsonObject {
                put("a[0]", "new")
                put("a[b", JsonNull)
                put("a.b", JsonNull)
                put(quote, "y")
                put("x]", true)
                put("n", buildJsonObject { put("a[0]", "new"); put("mid", JsonNull) })
            },
            db.read { pendingChanges() }.last().changeSet,
        )
    }

    @Test
    fun `a key order change queues nothing`() = runTest {
        val db = NoSqlite { +People }
        val id = uuid("ada")
        db.write { People.insert(buildJsonObject { put("id", id); put("a", 1); put("z", 2) }) }

        db.write { People.replace(buildJsonObject { put("id", id); put("z", 2); put("a", 1) }) { "id" eq id } }

        assertEquals(1, db.read { pendingChanges() }.size)
    }

    private fun nested(id: String, last: String, tags: List<String>): JsonObject = buildJsonObject {
        put("id", id)
        put("name", buildJsonObject { put("first", "Ada"); put("last", last) })
        put("age", 36)
        put("tags", buildJsonArray { tags.forEach { add(JsonPrimitive(it)) } })
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
