package io.github.nailkhaf.nosqlite

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.uuid.Uuid
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Covers declaring collections, their tables, and storing JSON documents. */
internal class StorageTest {

    object Users : Collection("users")

    object Notes : Collection("notes")

    object Undeclared : Collection("undeclared")

    object People : Collection("people")

    object Elements : Collection("elements")

    @Serializable
    data class Adult(val age: Int) {
        init {
            require(age >= 18) { "Too young: $age" }
        }
    }

    object Adults : Collection("adults")

    private val ada = buildJsonObject {
        put("id", uuid("ada").toString())
        put("name", "Ada")
        put("age", 36)
    }
    private val alan = buildJsonObject {
        put("id", uuid("alan").toString())
        put("name", "Alan")
        put("age", 41)
    }

    @Test
    fun `list returns the inserted documents`() = runTest {
        val db = NoSqlite { +Users }
        db.write {
            Users.insert(ada)
            Users.insert(alan)
        }

        assertEquals(setOf(ada, alan), db.read { Users.list<JsonObject>() }.toSet())
        assertEquals(listOf(ada), db.read { Users.list<JsonObject> { orderBy { "age".asc }; limit(1) } })
    }

    @Test
    fun `list without a limit returns every document`() = runTest {
        val db = NoSqlite { +Users }
        db.write {
            Users.insert(ada)
            Users.insert(alan)
        }

        db.read {
            assertEquals(setOf(ada, alan), Users.list<JsonObject>().toSet())
            assertEquals(listOf(alan), Users.list<JsonObject> { orderBy { "age".asc }; offset(1) })
            assertEquals(listOf(alan), Users.list<JsonObject> { where { "age" gt 40 } })
        }
    }

    @Test
    fun `list returns only matching documents`() = runTest {
        val db = NoSqlite { +Users }
        db.write {
            Users.insert(ada)
            Users.insert(alan)
        }

        assertEquals(listOf(alan), db.read { Users.list<JsonObject> { where { "age" gt 40 } } })
        assertEquals(emptyList(), db.read { Users.list<JsonObject> { where { "age" gt 50 } } })
    }

    @Test
    fun `list pages with limit and offset`() = runTest {
        val db = NoSqlite { +Users }
        val people = (1..5).map { buildJsonObject { put("id", uuid("p$it").toString()); put("age", it) } }
        db.write { people.forEach { Users.insert(it) } }

        db.read {
            assertEquals(people.subList(0, 2), Users.list<JsonObject> { orderBy { "age".asc }; limit(2) })
            assertEquals(people.subList(2, 4), Users.list<JsonObject> { orderBy { "age".asc }; limit(2); offset(2) })
            assertEquals(people.subList(4, 5), Users.list<JsonObject> { orderBy { "age".asc }; limit(2); offset(4) })
            assertEquals(emptyList(), Users.list<JsonObject> { limit(2); offset(5) })
            assertEquals(
                people.subList(3, 5),
                Users.list<JsonObject> { where { "age" gt 2 }; orderBy { "age".asc }; offset(1) },
            )
        }
    }

    @Test
    fun `list needs a positive limit and no negative offset`() = runTest {
        val db = NoSqlite { +Users }

        val limit = assertFailsWith<IllegalArgumentException> { db.read { Users.list<JsonObject> { limit(0) } } }
        val offset = assertFailsWith<IllegalArgumentException> { db.read { Users.list<JsonObject> { offset(-1) } } }

        assertEquals("Limit must be positive: 0", limit.message)
        assertEquals("Offset must not be negative: -1", offset.message)
    }

    @Serializable
    data class User(val id: Uuid, val name: String, val age: Int)

    @Test
    fun `a serializable value is stored as its JSON`() = runTest {
        val db = NoSqlite { +People }

        db.write { People.insert(User(uuid("ada"), "Ada", 36)) }

        assertEquals(listOf(User(uuid("ada"), "Ada", 36)), db.read { People.list<User> { limit(1) } })
        assertEquals(
            listOf(listOf("""{"id":"${uuid("ada")}","name":"Ada","age":36}""")),
            db.querySql("SELECT json(data) FROM nsq_people"),
        )
    }

    @Test
    fun `a value that is not a JSON object is rejected`() = runTest {
        val db = NoSqlite { +Elements }

        val error = assertFailsWith<IllegalArgumentException> { db.write { Elements.insert(JsonPrimitive(42)) } }

        assertEquals("A document of 'elements' must be a JSON object, not 42", error.message)
        assertEquals(0, db.read { Elements.count() })
    }

    @Test
    fun `each collection is a strict table of jsonb data`() = runTest {
        val db = NoSqlite { +Users }

        db.write { Users.insert(ada) }

        assertEquals(
            listOf(
                listOf(
                    """
                    CREATE TABLE "nsq_users" (
                        data BLOB NOT NULL,
                        CONSTRAINT document_id CHECK (json_type(data, '$.id') IS 'text' AND (data ->> '$.id') GLOB '$CANONICAL_UUID_GLOB')
                    ) STRICT
                    """.trimIndent(),
                ),
            ),
            db.querySql("SELECT sql FROM sqlite_schema WHERE name = 'nsq_users'"),
        )
        assertEquals(listOf(listOf("blob")), db.querySql("SELECT typeof(data) FROM nsq_users"))
    }

    @Test
    fun `a document is stored as its JSON alone`() = runTest {
        val db = NoSqlite { +Users }

        db.write { Users.insert(ada) }

        assertEquals(
            listOf(listOf("""{"id":"${uuid("ada")}","name":"Ada","age":36}""")),
            db.querySql("SELECT json(data) FROM nsq_users"),
        )
    }

    @Test
    fun `every declared collection gets a table on first use`() = runTest {
        val db = NoSqlite {
            +Users
            +Notes
        }

        db.read { }

        assertEquals(
            listOf(listOf("nsq__pending_changes"), listOf("nsq_notes"), listOf("nsq_users")),
            db.querySql("SELECT name FROM sqlite_schema WHERE type = 'table' AND name NOT LIKE 'sqlite%' ORDER BY 1"),
        )
    }

    @Test
    fun `failed write leaves every collection unchanged`() = runTest {
        val db = NoSqlite {
            +Users
            +Notes
        }

        assertFails {
            db.write {
                Users.insert(ada)
                Notes.insert(alan)
                error("rollback")
            }
        }

        assertEquals(
            listOf(listOf("0", "0")),
            db.querySql("SELECT (SELECT count(*) FROM nsq_users), (SELECT count(*) FROM nsq_notes)"),
        )
    }

    @Test
    fun `documents survive closing and reopening the file`() = runTest {
        withTempFile { location ->
            val first = NoSqlite(location) { +Users }
            first.write { Users.insert(ada) }
            first.close()

            val second = NoSqlite(location.path) { +Users }
            val stored = second.read { Users.list<JsonObject> { limit(1) } }

            assertEquals(listOf(ada), stored)
            second.close()
        }
    }

    @Test
    fun `closing an unused database creates no file`() = runTest {
        withTempFile { location ->
            NoSqlite(location) { +Users }.close()

            assertFalse(location.exists())
        }
    }

    @Test
    fun `delete closes the database and removes its file`() = runTest {
        withTempFile { location ->
            val db = NoSqlite(location) { +Users }
            db.write { Users.insert(ada) }

            db.delete()

            assertFalse(location.exists())
            assertFailsWith<IllegalStateException> { db.read { } }
            val reopened = NoSqlite(location) { +Users }
            assertEquals(0, reopened.read { Users.count() })
            reopened.close()
        }
    }

    @Test
    fun `a closed database can't be used`() = runTest {
        val db = NoSqlite { +Users }
        db.write { Users.insert(ada) }
        db.close()

        val error = assertFailsWith<IllegalStateException> { db.read { Users.list<JsonObject> { limit(1) } } }

        assertEquals("Database is closed", error.message)
    }

    @Test
    fun `undeclared collection is rejected`() = runTest {
        val db = NoSqlite { +Users }

        val error = assertFailsWith<NoSqliteException.CollectionNotDeclared> { db.write { Undeclared.insert(ada) } }

        assertEquals("Collection 'undeclared' is not declared, add +undeclared to NoSqlite { }", error.message)
    }

    @Test
    fun `a by-name upsert creates the table and without merge replaces the document`() = runTest {
        val db = NoSqlite { }
        val note = buildJsonObject { put("id", uuid("note").toString()); put("text", "hi") }
        val other = buildJsonObject { put("id", uuid("note-2").toString()); put("text", "yo") }
        val replacement = buildJsonObject {
            put("id", uuid("note").toString())
            put("done", true)
            put("tag", JsonNull)
        }

        db.write(untracked = true) {
            upsert("notes", note)
            upsert("notes", other)
        }
        db.write(untracked = true) { upsert("notes", replacement, merge = false) }

        assertEquals(
            listOf(
                listOf(
                    """
                    CREATE TABLE "nsq_notes" (
                        data BLOB NOT NULL,
                        CONSTRAINT document_id CHECK (json_type(data, '$.id') IS 'text' AND (data ->> '$.id') GLOB '$CANONICAL_UUID_GLOB')
                    ) STRICT
                    """.trimIndent(),
                ),
            ),
            db.querySql("SELECT sql FROM sqlite_schema WHERE name = 'nsq_notes'"),
        )
        val notes: Collection = Notes
        assertEquals(
            listOf(listOf("""CREATE UNIQUE INDEX "${idIndexName(notes)}" ON "nsq_notes" ($ID_VALUE)""")),
            db.querySql("SELECT sql FROM sqlite_schema WHERE type = 'index' AND tbl_name = 'nsq_notes'"),
        )
        assertEquals(
            listOf(listOf(triggerName(notes.name, "id_immutable"))),
            db.querySql("SELECT name FROM sqlite_schema WHERE type = 'trigger' AND tbl_name = 'nsq_notes'"),
        )
        assertEquals(
            emptyList(),
            db.querySql("SELECT name FROM sqlite_temp_schema WHERE type = 'trigger' AND tbl_name = 'nsq_notes'"),
        )
        assertEquals(2L, db.read { Notes.count() })
        assertEquals(
            listOf(listOf("""{"id":"${uuid("note")}","done":true,"tag":null}""")),
            db.querySql("""SELECT json(data) FROM nsq_notes WHERE data ->> '$.id' = '${uuid("note")}'"""),
        )
        assertTrue("notes" in db.pool().schema)
    }

    @Test
    fun `a batch of by-name upserts creates the table once`() = runTest {
        var creates = 0
        val driver = SteppingDriver(notesTableSql) { creates += 1 }
        val db = NoSqlite(driver = driver) { }
        val first = buildJsonObject { put("id", uuid("note").toString()); put("text", "hi") }
        val second = buildJsonObject { put("id", uuid("note-2").toString()); put("text", "yo") }
        val third = buildJsonObject { put("id", uuid("note-3").toString()); put("text", "hey") }
        val patch = buildJsonObject { put("id", uuid("note").toString()); put("done", true) }
        val rolledBack = buildJsonObject { put("id", uuid("rolled").toString()); put("text", "no") }

        db.write(untracked = true) {
            upsert("notes", first)
            upsert("notes", second)
            assertEquals(1, creates)
            assertFails {
                db.write {
                    Notes.insert(rolledBack)
                    error("rollback")
                }
            }
            upsert("notes", third)
            upsert("notes", patch, merge = false)
            assertTrue(delete("notes", uuid("note-2")))
            assertEquals(1, creates)
        }

        assertEquals(1, creates)
        assertEquals(2L, db.read { Notes.count() })
        assertEquals(
            listOf(listOf("""{"id":"${uuid("note")}","done":true}""")),
            db.querySql("""SELECT json(data) FROM nsq_notes WHERE data ->> '$.id' = '${uuid("note")}'"""),
        )
        assertEquals(
            listOf(listOf("""{"id":"${uuid("note-3")}","text":"hey"}""")),
            db.querySql("""SELECT json(data) FROM nsq_notes WHERE data ->> '$.id' = '${uuid("note-3")}'"""),
        )
        assertEquals(
            emptyList(),
            db.querySql(
                """SELECT json(data) FROM nsq_notes WHERE data ->> '$.id' IN ('${uuid("note-2")}', '${uuid("rolled")}')""",
            ),
        )
        assertTrue("notes" in db.pool().schema)
    }

    @Test
    fun `a rolled back upsert does not register the name`() = runTest {
        val db = NoSqlite { }
        val note = buildJsonObject { put("id", uuid("note").toString()); put("text", "hi") }

        assertFails {
            db.write(untracked = true) {
                upsert("notes", note)
                error("rollback")
            }
        }

        assertFalse("notes" in db.pool().schema)
        assertEquals(emptyList(), db.querySql("SELECT name FROM sqlite_schema WHERE name = 'nsq_notes'"))
        db.write(untracked = true) { upsert("notes", note) }
        assertEquals(listOf(listOf("nsq_notes")), db.querySql("SELECT name FROM sqlite_schema WHERE name = 'nsq_notes'"))
        assertEquals(1L, db.read { Notes.count() })
        assertTrue("notes" in db.pool().schema)
    }

    @Test
    fun `delete of an unknown collection does nothing`() = runTest {
        val db = NoSqlite { }

        val deleted = db.write(untracked = true) { delete("notes", uuid("note")) }

        assertFalse(deleted)
        assertFalse("notes" in db.pool().schema)
        assertEquals(emptyList(), db.querySql("SELECT name FROM sqlite_schema WHERE name = 'nsq_notes'"))
    }

    @Test
    fun `a by-name delete reaches a table created before the database reopened`() = runTest {
        withTempFile { location ->
            val note = buildJsonObject { put("id", uuid("note").toString()); put("text", "hi") }
            val first = NoSqlite(location) { }
            first.write(untracked = true) { upsert("notes", note) }
            first.close()

            val second = NoSqlite(location) { }
            val deleted = second.write(untracked = true) { delete("notes", uuid("note")) }

            assertTrue(deleted)
            assertEquals(0L, second.read { Notes.count() })
            second.close()
        }
    }

    @Test
    fun `an invalid collection name is rejected by a by-name change`() = runTest {
        val db = NoSqlite { }
        val note = buildJsonObject { put("id", uuid("note").toString()); put("text", "hi") }
        val invalid = mapOf(
            "my users" to "Invalid collection name: 'my users'",
            "idx_users" to "Invalid collection name: 'idx_users'",
            "sqlite_users" to "Collection name is reserved by SQLite: 'sqlite_users'",
            "_pending_changes" to "Collection name is reserved: '_pending_changes'",
        )

        for ((name, message) in invalid) {
            val upsertError = assertFailsWith<IllegalArgumentException> {
                db.write(untracked = true) { upsert(name, note) }
            }
            assertEquals(message, upsertError.message)
            val deleteError = assertFailsWith<IllegalArgumentException> {
                db.write(untracked = true) { delete(name, uuid("note")) }
            }
            assertEquals(message, deleteError.message)
        }
    }

    @Test
    fun `a by-name upsert in a nested write registers the name once the outer write commits`() = runTest {
        val db = NoSqlite { }
        val note = buildJsonObject { put("id", uuid("note").toString()); put("text", "hi") }

        db.write {
            db.write { upsert("notes", note) }
            assertFalse("notes" in db.pool().schema)
        }

        assertTrue("notes" in db.pool().schema)
        assertEquals(1L, db.read { Notes.count() })
        assertTrue(db.write { delete("notes", uuid("note")) })
        assertEquals(0L, db.read { Notes.count() })
    }

    @Test
    fun `a write that is not untracked still rejects an undeclared collection`() = runTest {
        val db = NoSqlite { }
        val note = buildJsonObject { put("id", uuid("note").toString()); put("text", "hi") }

        val error = assertFailsWith<NoSqliteException.CollectionNotDeclared> { db.write { Notes.insert(note) } }

        assertEquals("notes", error.collection)
        assertEquals(emptyList(), db.querySql("SELECT name FROM sqlite_schema WHERE name = 'nsq_notes'"))
    }

    @Test
    fun `a read of a missing table inside an untracked write is still not declared`() = runTest {
        val db = NoSqlite { }

        val error = assertFailsWith<NoSqliteException.CollectionNotDeclared> {
            db.write(untracked = true) { Notes.list<JsonObject>() }
        }

        assertEquals("notes", error.collection)
    }

    @Test
    fun `a failed create is tried again in the same untracked write`() = runTest {
        val driver = FailingDriver(notesTableSql, times = 1)
        val db = NoSqlite(driver = driver) { }
        val note = buildJsonObject { put("id", uuid("note").toString()); put("text", "hi") }

        db.write(untracked = true) {
            assertFails { upsert("notes", note) }
            upsert("notes", note)
        }

        assertEquals(1L, db.read { Notes.count() })
    }

    @Test
    fun `a failed id index is created when the untracked write tries again`() = runTest {
        val notes: Collection = Notes
        val indexSql = "CREATE UNIQUE INDEX IF NOT EXISTS ${identifier(idIndexName(notes))} " +
            "ON ${table(notes.name)} ($ID_VALUE);"
        val driver = FailingDriver(indexSql, times = 1)
        val db = NoSqlite(driver = driver) { }
        val note = buildJsonObject { put("id", uuid("note").toString()); put("text", "hi") }

        db.write(untracked = true) {
            assertFails { upsert("notes", note) }
            upsert("notes", note)
        }

        assertEquals(
            listOf(listOf(idIndexName(notes))),
            db.querySql("SELECT name FROM sqlite_schema WHERE type = 'index' AND tbl_name = 'nsq_notes'"),
        )
        assertEquals(1L, db.read { Notes.count() })
    }

    @Test
    fun `collection declared twice is rejected`() {
        val error = assertFailsWith<IllegalArgumentException> {
            NoSqlite {
                +Users
                +Users
            }
        }

        assertEquals("Collection 'users' is declared more than once", error.message)
    }

    @Test
    fun `reopening without a collection keeps its table`() = runTest {
        withTempFile { location ->
            NoSqlite(location) {
                +Users
                +Notes
            }.apply { write { Notes.insert(ada) } }.close()

            val db = NoSqlite(location) { +Users }

            assertEquals(
                listOf(listOf("nsq__pending_changes"), listOf("nsq_notes"), listOf("nsq_users")),
                db.querySql("SELECT name FROM sqlite_schema WHERE type = 'table' AND name NOT LIKE 'sqlite%' ORDER BY 1"),
            )
            assertEquals(
                listOf(listOf("""{"id":"${uuid("ada")}","name":"Ada","age":36}""")),
                db.querySql("SELECT json(data) FROM nsq_notes"),
            )
            db.close()
        }
    }

    @Test
    fun `invalid collection names are rejected`() {
        val invalid = mapOf(
            "my users" to "Invalid collection name: 'my users'",
            "1users" to "Invalid collection name: '1users'",
            "sqlite_users" to "Collection name is reserved by SQLite: 'sqlite_users'",
            "idx" to "Invalid collection name: 'idx'",
            "idx_users" to "Invalid collection name: 'idx_users'",
            "idxfoo" to "Invalid collection name: 'idxfoo'",
        )

        for ((name, message) in invalid) {
            val error = assertFailsWith<IllegalArgumentException> {
                object : Collection(name) {}
            }
            assertEquals(message, error.message)
        }
    }

    @Test
    fun `document that does not decode names its collection and row`() = runTest {
        val db = NoSqlite { +People }
        db.write { People.insert(User(uuid("ada"), "ada", 36)) }
        db.pool().writeSql {
            execute("""INSERT INTO nsq_people (data) VALUES (jsonb('{"id":"${uuid("x")}","name":1}'))""")
        }

        val error = assertFailsWith<NoSqliteException.DecodingFailed> { db.read { People.list<User>() } }

        assertEquals("people", error.collection)
        assertTrue(error.message!!.startsWith("Can't decode a document of 'people'"), error.message)
    }

    @Test
    fun `forEach stops at a document that does not decode`() = runTest {
        val db = NoSqlite { +People }
        db.write { People.insert(User(uuid("ada"), "ada", 36)) }
        db.pool().writeSql {
            execute("""INSERT INTO nsq_people (data) VALUES (jsonb('{"id":"${uuid("x")}","name":1}'))""")
        }
        db.write { People.insert(User(uuid("alan"), "alan", 41)) }
        val seen = mutableListOf<String>()

        assertFailsWith<NoSqliteException.DecodingFailed> { db.read { People.forEach<User> { seen += it.name } } }

        assertEquals(listOf("ada"), seen)
    }

    @Test
    fun `document its class rejects names its collection`() = runTest {
        val db = NoSqlite { +Adults }
        db.pool().writeSql {
            execute("""INSERT INTO nsq_adults (data) VALUES (jsonb('{"id":"${uuid("kid")}","age":17}'))""")
        }

        val error = assertFailsWith<NoSqliteException.DecodingFailed> { db.read { Adults.list<Adult>() } }

        assertEquals("adults", error.collection)
        assertEquals("Can't decode a document of 'adults': Too young: 17", error.message)
    }
}

private fun idIndexName(collection: Collection): String = Queries.idIndexName(collection.name)

private val notesTableSql = """
    CREATE TABLE IF NOT EXISTS "nsq_notes" (
        data BLOB NOT NULL,
        CONSTRAINT document_id CHECK (json_type(data, '$.id') IS 'text' AND ($ID_VALUE) GLOB '$CANONICAL_UUID_GLOB')
    ) STRICT;
""".trimIndent()
