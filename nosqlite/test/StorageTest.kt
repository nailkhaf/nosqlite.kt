package io.github.nailkhaf.nosqlite

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Covers declaring collections, their tables, and storing JSON documents. */
internal class StorageTest {

    object Users : Collection<JsonObject>("users", JsonObject.serializer())

    object Notes : Collection<JsonObject>("notes", JsonObject.serializer())

    object Undeclared : Collection<JsonObject>("undeclared", JsonObject.serializer())

    object People : Collection<User>("people", User.serializer())

    object Elements : Collection<JsonElement>("elements", JsonElement.serializer())

    @Serializable
    data class Adult(val age: Int) {
        init {
            require(age >= 18) { "Too young: $age" }
        }
    }

    object Adults : Collection<Adult>("adults", Adult.serializer())

    private val ada = buildJsonObject {
        put("name", "Ada")
        put("age", 36)
    }
    private val alan = buildJsonObject {
        put("name", "Alan")
        put("age", 41)
    }

    @Test
    fun `inserted documents get ids in order`() = runTest {
        val db = NoSqlite { +Users }

        db.write {
            Users.insert(ada)
            Users.insert(alan)
        }

        assertEquals(listOf(listOf("1"), listOf("2")), db.querySql("SELECT rowid FROM users"))
    }

    @Test
    fun `list returns the inserted documents in order`() = runTest {
        val db = NoSqlite { +Users }
        db.write {
            Users.insert(ada)
            Users.insert(alan)
        }

        assertEquals(listOf(ada, alan), db.read { Users.list() })
        assertEquals(listOf(ada), db.read { Users.list { limit(1) } })
    }

    @Test
    fun `list without a limit returns every document`() = runTest {
        val db = NoSqlite { +Users }
        db.write {
            Users.insert(ada)
            Users.insert(alan)
        }

        db.read {
            assertEquals(listOf(ada, alan), Users.list())
            assertEquals(listOf(alan), Users.list { offset(1) })
            assertEquals(listOf(alan), Users.list { where { "age" gt 40 } })
        }
    }

    @Test
    fun `list returns only matching documents`() = runTest {
        val db = NoSqlite { +Users }
        db.write {
            Users.insert(ada)
            Users.insert(alan)
        }

        assertEquals(listOf(alan), db.read { Users.list { where { "age" gt 40 } } })
        assertEquals(emptyList(), db.read { Users.list { where { "age" gt 50 } } })
    }

    @Test
    fun `list pages with limit and offset`() = runTest {
        val db = NoSqlite { +Users }
        val people = (1..5).map { buildJsonObject { put("age", it) } }
        db.write { people.forEach { Users.insert(it) } }

        db.read {
            assertEquals(people.subList(0, 2), Users.list { limit(2) })
            assertEquals(people.subList(2, 4), Users.list { limit(2); offset(2) })
            assertEquals(people.subList(4, 5), Users.list { limit(2); offset(4) })
            assertEquals(emptyList(), Users.list { limit(2); offset(5) })
            assertEquals(people.subList(3, 5), Users.list { where { "age" gt 2 }; offset(1) })
        }
    }

    @Test
    fun `list needs a positive limit and no negative offset`() = runTest {
        val db = NoSqlite { +Users }

        val limit = assertFailsWith<IllegalArgumentException> { db.read { Users.list { limit(0) } } }
        val offset = assertFailsWith<IllegalArgumentException> { db.read { Users.list { offset(-1) } } }

        assertEquals("Limit must be positive: 0", limit.message)
        assertEquals("Offset must not be negative: -1", offset.message)
    }

    @Serializable
    data class User(val name: String, val age: Int)

    @Test
    fun `a collection can be created without a subclass`() = runTest {
        val notes = Collection("notes", JsonObject.serializer())
        val db = NoSqlite { +notes }

        db.write { notes.insert(ada) }

        assertEquals(listOf(ada), db.read { notes.list { limit(1) } })
    }

    @Test
    fun `a serializable value is stored as its JSON`() = runTest {
        val db = NoSqlite { +People }

        db.write { People.insert(User("Ada", 36)) }

        assertEquals(listOf(User("Ada", 36)), db.read { People.list { limit(1) } })
        assertEquals(listOf(listOf("""{"name":"Ada","age":36}""")), db.querySql("SELECT json(data) FROM people"))
    }

    @Test
    fun `a collection of values that are not JSON objects is rejected`() {
        val serializers = listOf(
            Int.serializer(),
            String.serializer(),
            Role.serializer(),
            Email.serializer(),
            ListSerializer(User.serializer()),
            User.serializer().nullable,
        )

        val messages = serializers.map { assertFailsWith<IllegalArgumentException> { Collection("docs", it) }.message }

        val expected = serializers.map { "A document of 'docs' must be a JSON object, not ${it.descriptor.serialName}" }
        assertEquals(expected, messages)
    }

    @Test
    fun `objects maps and polymorphic values make collections`() {
        val collections = listOf(
            Collection("users", User.serializer()),
            Collection("emails", MapSerializer(String.serializer(), Email.serializer())),
            Collection("raw", JsonObject.serializer()),
            Collection("elements", JsonElement.serializer()),
        )

        assertEquals(listOf("users", "emails", "raw", "elements"), collections.map { it.name })
    }

    @Test
    fun `a value that is not a JSON object is rejected`() = runTest {
        val db = NoSqlite { +Elements }

        val error = assertFailsWith<IllegalArgumentException> { db.write { Elements.insert(JsonPrimitive(42)) } }

        assertEquals("A document of 'elements' must be a JSON object, not 42", error.message)
        assertEquals(0, db.read { Elements.count() })
    }

    @Test
    fun `the id of a deleted document is never given out again`() = runTest {
        val db = NoSqlite { +Users }
        db.write { Users.insert(ada) }
        db.pool().writeSql { execute("DELETE FROM users") }

        db.write { Users.insert(alan) }

        assertEquals(listOf(listOf("2")), db.querySql("SELECT rowid FROM users"))
    }

    @Test
    fun `each collection is a strict table of rowid and jsonb data`() = runTest {
        val db = NoSqlite { +Users }

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
        val db = NoSqlite { +Users }

        db.write { Users.insert(ada) }

        assertEquals(listOf(listOf("""{"name":"Ada","age":36}""")), db.querySql("SELECT json(data) FROM users"))
    }

    @Test
    fun `every declared collection gets a table on first use`() = runTest {
        val db = NoSqlite {
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
            db.querySql("SELECT (SELECT count(*) FROM users), (SELECT count(*) FROM notes)"),
        )
    }

    @Test
    fun `documents survive closing and reopening the file`() = runTest {
        withTempFile { location ->
            val first = NoSqlite(location) { +Users }
            first.write { Users.insert(ada) }
            first.close()

            val second = NoSqlite(location.path) { +Users }
            val stored = second.read { Users.list { limit(1) } }

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
    fun `table of another shape fails on open`() = runTest {
        withTempFile { location ->
            BundledSQLiteDriver().open(location.path).apply {
                execSQL("CREATE TABLE users (id TEXT, data BLOB);")
                close()
            }
            val db = NoSqlite(location) { +Users }

            val error = assertFailsWith<NoSqliteException.IncompatibleTable> { db.read { } }

            assertEquals(listOf("id TEXT", "data BLOB"), error.columns)
            assertEquals(
                "Table 'users' has columns (id TEXT, data BLOB), not a collection's (rowid INTEGER PRIMARY KEY, data BLOB)",
                error.message,
            )
            db.close()
        }
    }

    @Test
    fun `a closed database can't be used`() = runTest {
        val db = NoSqlite { +Users }
        db.write { Users.insert(ada) }
        db.close()

        val error = assertFailsWith<IllegalStateException> { db.read { Users.list { limit(1) } } }

        assertEquals("Database is closed", error.message)
    }

    @Test
    fun `undeclared collection is rejected`() = runTest {
        val db = NoSqlite { +Users }

        val error = assertFailsWith<NoSqliteException.CollectionNotDeclared> { db.write { Undeclared.insert(ada) } }

        assertEquals("Collection 'undeclared' is not declared, add +undeclared to NoSqlite { }", error.message)
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
    fun `removed collection is dropped on open`() = runTest {
        withTempFile { location ->
            NoSqlite(location) {
                +Users
                +Notes
            }.apply { write { Notes.insert(ada) } }.close()

            val db = NoSqlite(location) {
                +Users
                -"notes"
            }

            assertEquals(
                listOf(listOf("users")),
                db.querySql("SELECT name FROM sqlite_schema WHERE type = 'table' AND name NOT LIKE 'sqlite%'"),
            )
            db.close()
        }
    }

    @Test
    fun `collection declared again after removal starts empty`() = runTest {
        withTempFile { location ->
            NoSqlite(location) { +Notes }.apply { write { Notes.insert(ada) } }.close()
            NoSqlite(location) { -"notes" }.apply { read { } }.close()

            val db = NoSqlite(location) { +Notes }
            db.write { Notes.insert(alan) }

            assertEquals(listOf(alan), db.read { Notes.list() })
            assertEquals(listOf(listOf("1")), db.querySql("SELECT rowid FROM notes"))
            db.close()
        }
    }

    @Test
    fun `removing a missing collection does nothing`() = runTest {
        val db = NoSqlite {
            +Users
            -"notes"
        }

        db.write { Users.insert(ada) }

        assertEquals(1L, db.read { Users.count() })
    }

    @Test
    fun `collection both declared and removed is rejected`() {
        val declaredFirst = assertFailsWith<IllegalArgumentException> {
            NoSqlite {
                +Users
                -"users"
            }
        }
        val removedFirst = assertFailsWith<IllegalArgumentException> {
            NoSqlite {
                -"users"
                +Users
            }
        }

        assertEquals("Collection 'users' is both declared and removed", declaredFirst.message)
        assertEquals("Collection 'users' is both declared and removed", removedFirst.message)
    }

    @Test
    fun `collection removed twice is rejected`() {
        val error = assertFailsWith<IllegalArgumentException> {
            NoSqlite {
                -"notes"
                -"notes"
            }
        }

        assertEquals("Collection 'notes' is removed more than once", error.message)
    }

    @Test
    fun `removed collection name is validated`() {
        val error = assertFailsWith<IllegalArgumentException> { NoSqlite { -"sqlite_master" } }

        assertEquals("Collection name is reserved by SQLite: 'sqlite_master'", error.message)
    }

    @Test
    fun `invalid collection names are rejected`() {
        val invalid = mapOf(
            "my users" to "Invalid collection name: 'my users'",
            "1users" to "Invalid collection name: '1users'",
            "sqlite_users" to "Collection name is reserved by SQLite: 'sqlite_users'",
        )

        for ((name, message) in invalid) {
            val error = assertFailsWith<IllegalArgumentException> {
                object : Collection<JsonObject>(name, JsonObject.serializer()) {}
            }
            assertEquals(message, error.message)
        }
    }

    @Test
    fun `document that does not decode names its collection and row`() = runTest {
        val db = NoSqlite { +People }
        db.write { People.insert(User("ada", 36)) }
        db.pool().writeSql { execute("""INSERT INTO people (data) VALUES (jsonb('{"name":1}'))""") }

        val error = assertFailsWith<NoSqliteException.DecodingFailed> { db.read { People.list() } }

        assertEquals("people", error.collection)
        assertTrue(error.message!!.startsWith("Can't decode row 2 of 'people'"), error.message)
    }

    @Test
    fun `forEach stops at a document that does not decode`() = runTest {
        val db = NoSqlite { +People }
        db.write { People.insert(User("ada", 36)) }
        db.pool().writeSql { execute("""INSERT INTO people (data) VALUES (jsonb('{"name":1}'))""") }
        db.write { People.insert(User("alan", 41)) }
        val seen = mutableListOf<String>()

        assertFailsWith<NoSqliteException.DecodingFailed> { db.read { People.forEach { seen += it.name } } }

        assertEquals(listOf("ada"), seen)
    }

    @Test
    fun `document its class rejects names its collection`() = runTest {
        val db = NoSqlite { +Adults }
        db.pool().writeSql { execute("""INSERT INTO adults (data) VALUES (jsonb('{"age":17}'))""") }

        val error = assertFailsWith<NoSqliteException.DecodingFailed> { db.read { Adults.list() } }

        assertEquals("adults", error.collection)
        assertEquals("Can't decode row 1 of 'adults': Too young: 17", error.message)
    }
}
