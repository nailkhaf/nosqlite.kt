package io.github.nailkhaf.nosqlite

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Covers declaring indexes, syncing them with the file, unique violations and lookups by unique key. */
internal class IndexTest {

    object Users : Collection("users") {
        val byId = unique(User::id)
        val byEmail = unique(User::email)
        val byAge = index(User::age)
        val byName = index(User::name / Name::last, User::name / Name::first)
        val activeNickname = unique(User::nickname, where = { User::active eq true })
    }

    object People : Collection("people") {
        val byFullName = unique(path<String>("full_name"))
    }

    private val ada = user("ada", 36)
    private val alan = user("alan", 41)

    private suspend fun Database.insert(user: User): DocumentId = write { Users.insert(user) }

    private suspend fun Database.indexNames(): Set<String?> =
        querySql("SELECT name FROM sqlite_schema WHERE type = 'index'").map { it.single() }.toSet()

    @Test
    fun `declared indexes are created on first use`() = runTest {
        val db = Database { +Users }

        db.read { }

        val sql = db.querySql("SELECT sql FROM sqlite_schema WHERE type = 'index'").map { it.single() }.toSet()
        assertEquals(
            setOf(
                """CREATE UNIQUE INDEX "${Users.byId.name}" ON "users" (data ->> '$.id')""",
                """CREATE UNIQUE INDEX "${Users.byEmail.name}" ON "users" (data ->> '$.email')""",
                """CREATE INDEX "${Users.byAge.name}" ON "users" (data ->> '$.age')""",
                """CREATE INDEX "${Users.byName.name}" ON "users" (data ->> '$.name.last', data ->> '$.name.first')""",
                """CREATE UNIQUE INDEX "${Users.activeNickname.name}" ON "users" (data ->> '$.nickname') """ +
                    "WHERE data ->> '$.active' = 1",
            ),
            sql,
        )
    }

    @Test
    fun `index names hash their definition`() {
        assertTrue(Users.byEmail.name.matches(Regex("nsq_users_[0-9a-f]{16}")))
        // Read through the base type: Kotlin/Native fails to link an internal member read through a test object.
        val collection: Collection = Users
        assertEquals(5, collection.indexes.map { it.name }.toSet().size)
    }

    @Test
    fun `fnv1a64 matches the reference values`() {
        assertEquals("cbf29ce484222325", fnv1a64(""))
        assertEquals("af63dc4c8601ec8c", fnv1a64("a"))
    }

    @Test
    fun `reopening drops removed indexes and creates new ones`() = runTest {
        withTempFile { location ->
            val before = object : Collection("users") {
                val byAge = index(User::age)
                val byEmail = unique(User::email)
            }
            val after = object : Collection("users") {
                val byEmail = unique(User::email)
                val byLastName = index(User::name / Name::last)
            }
            Database(location) { +before }.apply { read { } }.close()

            val db = Database(location) { +after }

            assertEquals(setOf(after.byEmail.name, after.byLastName.name), db.indexNames())
            assertEquals(before.byEmail.name, after.byEmail.name)
            db.close()
        }
    }

    @Test
    fun `reopening keeps indexes nosqlite does not manage`() = runTest {
        withTempFile { location ->
            val db = Database(location) { +Users }
            db.pool().writeSql { execSQL("CREATE INDEX mine ON users (data ->> '$.score')") }
            db.close()

            val reopened = Database(location) { +Users }

            assertTrue("mine" in reopened.indexNames())
            reopened.close()
        }
    }

    @Test
    fun `unique index rejects a duplicate key and names itself`() = runTest {
        val db = Database { +Users }
        db.insert(ada)

        val error = assertFailsWith<NosqliteException.UniqueViolation> { db.insert(alan.copy(email = ada.email)) }

        assertSame(Users.byEmail, error.index)
        assertEquals("users", error.collection)
        assertEquals("Unique constraint violated in 'users': unique index users(email)", error.message)
    }

    @Test
    fun `caught violation leaves the write usable`() = runTest {
        val db = Database { +Users }

        db.write {
            Users.insert(buildJsonObject { put("id", "a") })
            assertFailsWith<NosqliteException.UniqueViolation> { Users.insert(buildJsonObject { put("id", "a") }) }
            Users.insert(buildJsonObject { put("id", "b") })
        }

        assertEquals(listOf(listOf("2")), db.querySql("SELECT count(*) FROM users"))
    }

    @Test
    fun `partial unique index only covers matching documents`() = runTest {
        val db = Database { +Users }
        db.insert(user("ann", 30, nickname = "twin", active = false))
        db.insert(user("bob", 30, nickname = "twin", active = false))
        db.insert(user("cid", 30, nickname = "twin"))

        val error = assertFailsWith<NosqliteException.UniqueViolation> { db.insert(user("dan", 30, nickname = "twin")) }

        assertSame(Users.activeNickname, error.index)
        assertEquals("cid", db.read { Users.activeNickname.get<User>("twin") }?.id)
    }

    @Test
    fun `unique index finds a document by key`() = runTest {
        val db = Database { +Users }
        db.insert(ada)
        db.insert(alan)

        db.read {
            assertEquals(ada, Users.byEmail.get<User>(ada.email))
            assertEquals(alan, Users.byId.get<User>("alan"))
            assertNull(Users.byId.get<User>("nobody"))
        }
    }

    @Test
    fun `lookup by unique key uses its index`() = runTest {
        val db = Database { +Users }
        val condition = Users.byEmail.lookup(ada.email).render(inline = true).sql

        val plan = db.querySql("EXPLAIN QUERY PLAN SELECT rowid FROM users WHERE $condition").joinToString {
            it.last()!!
        }

        assertTrue(Users.byEmail.name in plan, plan)
    }

    @Test
    fun `renamed property is rejected when the index is declared`() {
        val error = assertFailsWith<IllegalArgumentException> {
            object : Collection("people") {
                val byFullName = unique(Renamed::fullName)
            }
        }

        assertTrue(
            error.message!!.startsWith("Path 'fullName' doesn't match the serial names of Renamed"),
            error.message,
        )
    }

    @Test
    fun `serial path indexes a renamed property`() = runTest {
        val db = Database { +People }
        val doc = Renamed("Ada Lovelace", Renamed.Inner("1815"))
        db.write { People.insert(doc) }

        val found = db.read { People.byFullName.get<Renamed>("Ada Lovelace") }

        assertEquals(doc, found)
    }

    @Test
    fun `same index declared twice is rejected`() {
        val error = assertFailsWith<IllegalArgumentException> {
            object : Collection("twice") {
                val first = index(User::age)
                val second = index(User::age)
            }
        }

        assertEquals("Collection 'twice' declares index twice(age) more than once", error.message)
    }

    @Test
    fun `unique index over existing duplicates fails on open`() = runTest {
        withTempFile { location ->
            val plain = object : Collection("users") {}
            val withDuplicates = Database(location) { +plain }
            withDuplicates.write { repeat(2) { plain.insert(buildJsonObject { put("id", "a") }) } }
            withDuplicates.close()
            val unique = object : Collection("users") {
                val byId = unique(User::id)
            }

            val error = assertFailsWith<NosqliteException.UniqueViolation> { Database(location) { +unique }.read { } }

            assertEquals("users", error.collection)
        }
    }
}
