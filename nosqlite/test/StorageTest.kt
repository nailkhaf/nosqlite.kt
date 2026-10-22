package io.github.nailkhaf.nosqlite

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Covers declaring collections, their tables, and storing JSON documents by [DocumentId]. */
internal class StorageTest {

    object Users : Collection("users")

    object Notes : Collection("notes")

    object Undeclared : Collection("undeclared")

    private val ada = buildJsonObject {
        put("name", "Ada")
        put("age", 36)
    }
    private val alan = buildJsonObject {
        put("name", "Alan")
        put("age", 41)
    }

    @Test
    fun `insert returns the new row's id`() = runTest {
        val db = Database { +Users }

        val ids = db.write { listOf(Users.insert(ada), Users.insert(alan)) }

        assertEquals(listOf(DocumentId(1), DocumentId(2)), ids)
    }

    @Test
    fun `get returns the inserted document`() = runTest {
        val db = Database { +Users }
        val id = db.write { Users.insert(ada) }

        val stored = db.read { Users.get<JsonObject>(id) }

        assertEquals(ada, stored)
    }

    @Test
    fun `get of an unknown id is null`() = runTest {
        val db = Database { +Users }

        val stored = db.read { Users.get<JsonObject>(DocumentId(42)) }

        assertNull(stored)
    }

    @Test
    fun `delete tells whether the document was there`() = runTest {
        val db = Database { +Users }
        val id = db.write { Users.insert(ada) }

        val first = db.write { Users.delete(id) }
        val second = db.write { Users.delete(id) }

        assertTrue(first)
        assertFalse(second)
        assertNull(db.read { Users.get<JsonObject>(id) })
    }

    @Test
    fun `the id of a deleted document is never given out again`() = runTest {
        val db = Database { +Users }
        val deleted = db.write { Users.insert(ada) }
        db.write { Users.delete(deleted) }

        val next = db.write { Users.insert(alan) }

        assertNotEquals(deleted, next)
    }

    @Test
    fun `each collection is a strict table of rowid and jsonb data`() = runTest {
        val db = Database { +Users }

        db.write { Users.insert(ada) }

        assertEquals(
            listOf(
                listOf(
                    """
                    CREATE TABLE "users" (
                        rowid INTEGER PRIMARY KEY AUTOINCREMENT,
                        data BLOB NOT NULL
                    ) STRICT
                    """.trimIndent(),
                ),
            ),
            db.querySql("SELECT sql FROM sqlite_schema WHERE name = 'users'"),
        )
        assertEquals(listOf(listOf("1", "blob")), db.querySql("SELECT rowid, typeof(data) FROM users"))
    }

    @Test
    fun `the id is not written into the document`() = runTest {
        val db = Database { +Users }

        db.write { Users.insert(ada) }

        assertEquals(listOf(listOf("""{"name":"Ada","age":36}""")), db.querySql("SELECT json(data) FROM users"))
    }

    @Test
    fun `every declared collection gets a table on first use`() = runTest {
        val db = Database {
            +Users
            +Notes
        }

        db.read { }

        assertEquals(
            listOf(listOf("notes"), listOf("users")),
            // sqlite_sequence is SQLite's own AUTOINCREMENT table.
            db.querySql("SELECT name FROM sqlite_schema WHERE type = 'table' AND name NOT LIKE 'sqlite%' ORDER BY 1"),
        )
    }

    @Test
    fun `failed write leaves every collection unchanged`() = runTest {
        val db = Database {
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
            db.querySql("SELECT (SELECT count(*) FROM users), (SELECT count(*) FROM notes)"),
        )
    }

    @Test
    fun `documents survive closing and reopening the file`() = runTest {
        withTempFile { location ->
            val first = Database(location) { +Users }
            val id = first.write { Users.insert(ada) }
            first.close()

            val second = Database(location.path) { +Users }
            val stored = second.read { Users.get<JsonObject>(id) }

            assertEquals(ada, stored)
            second.close()
        }
    }

    @Test
    fun `closing an unused database creates no file`() = runTest {
        withTempFile { location ->
            Database(location) { +Users }.close()

            assertFalse(location.exists())
        }
    }

    @Test
    fun `a closed database can't be used`() = runTest {
        val db = Database { +Users }
        db.write { Users.insert(ada) }
        db.close()

        val error = assertFailsWith<IllegalStateException> { db.read { Users.get<JsonObject>(DocumentId(1)) } }

        assertEquals("Database is closed", error.message)
    }

    @Test
    fun `undeclared collection is rejected`() = runTest {
        val db = Database { +Users }

        val error = assertFailsWith<NosqliteException.CollectionNotDeclared> { db.write { Undeclared.insert(ada) } }

        assertEquals("Collection 'undeclared' is not declared, add +undeclared to Database { }", error.message)
    }

    @Test
    fun `collection declared twice is rejected`() {
        val error = assertFailsWith<IllegalArgumentException> {
            Database {
                +Users
                +Users
            }
        }

        assertEquals("Collection 'users' is declared more than once", error.message)
    }

    @Test
    fun `invalid collection names are rejected`() {
        val invalid = mapOf(
            "my users" to "Invalid collection name: 'my users'",
            "1users" to "Invalid collection name: '1users'",
            "sqlite_users" to "Collection name is reserved by SQLite: 'sqlite_users'",
        )

        for ((name, message) in invalid) {
            val error = assertFailsWith<IllegalArgumentException> { object : Collection(name) {} }
            assertEquals(message, error.message)
        }
    }

    @Test
    fun `document that does not decode names its id`() = runTest {
        val db = Database { +Users }
        val id = db.write { Users.insert(ada) }

        val error = assertFailsWith<NosqliteException.DecodingFailed> { db.read { Users.get<Int>(id) } }

        assertEquals("users", error.collection)
        assertEquals(id, error.id)
    }
}
