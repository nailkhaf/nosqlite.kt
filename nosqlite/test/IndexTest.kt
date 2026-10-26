package io.github.nailkhaf.nosqlite

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Covers declaring indexes, syncing them with the file, unique violations and lookups by unique field. */
internal class IndexTest {

    object Users : Collection("users") {
        val byId = unique("id")
        val byEmail = unique("email")
        val byAge = index("age")
        val byName = index("name.last", "name.first")
        val activeNickname = unique("nickname", where = { "active".isTrue() })
    }

    object Teams : Collection("teams") {
        val byLead = unique("lead.last")
        val byLeadFirst = index("lead.first")
    }

    @Serializable
    data class Team(val name: String, val lead: Name)

    private val ada = user("ada", 36)
    private val alan = user("alan", 41)

    private suspend fun NoSqlite.insert(user: User) = write { Users.insert(user) }

    private suspend fun NoSqlite.indexNames(): Set<String?> =
        querySql("SELECT name FROM sqlite_schema WHERE type = 'index'").map { it.single() }.toSet()

    @Test
    fun `declared indexes are created on first use`() = runTest {
        val db = NoSqlite { +Users }

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
                val byAge = index("age")
                val byEmail = unique("email")
            }
            val after = object : Collection("users") {
                val byEmail = unique("email")
                val byLastName = index("name.last")
            }
            NoSqlite(location) { +before }.apply { read { } }.close()

            val db = NoSqlite(location) { +after }

            assertEquals(setOf(after.byEmail.name, after.byLastName.name), db.indexNames())
            assertEquals(before.byEmail.name, after.byEmail.name)
            db.close()
        }
    }

    @Test
    fun `making an index unique recreates it`() = runTest {
        withTempFile { location ->
            val plain = object : Collection("users") {
                val byEmail = index("email")
            }
            val unique = object : Collection("users") {
                val byEmail = unique("email")
            }
            NoSqlite(location) { +plain }.apply { read { } }.close()

            val db = NoSqlite(location) { +unique }

            assertEquals(setOf(unique.byEmail.name), db.indexNames())
            assertTrue(plain.byEmail.name != unique.byEmail.name)
            db.close()
        }
    }

    @Test
    fun `reopening keeps indexes nosqlite does not manage`() = runTest {
        withTempFile { location ->
            val db = NoSqlite(location) { +Users }
            db.pool().writeSql { execute("CREATE INDEX mine ON users (data ->> '$.score')") }
            db.close()

            val reopened = NoSqlite(location) { +Users }

            assertTrue("mine" in reopened.indexNames())
            reopened.close()
        }
    }

    @Test
    fun `reopening drops an undeclared index whose name has a quote`() = runTest {
        withTempFile { location ->
            val db = NoSqlite(location) { +Users }
            db.pool().writeSql { execute("CREATE INDEX \"nsq_users_\"\"x\" ON users (data ->> '$.score')") }
            db.close()

            val reopened = NoSqlite(location) { +Users }

            assertTrue("nsq_users_\"x" !in reopened.indexNames())
            reopened.close()
        }
    }

    @Test
    fun `unique index rejects a duplicate key and names itself`() = runTest {
        val db = NoSqlite { +Users }
        db.insert(ada)

        val error = assertFailsWith<NoSqliteException.UniqueViolation> { db.insert(alan.copy(email = ada.email)) }

        assertSame(Users.byEmail, error.index)
        assertEquals("users", error.collection)
        assertEquals("Unique constraint violated in 'users': unique index users(email)", error.message)
    }

    @Test
    fun `composite unique index rejects only a repeated combination`() = runTest {
        val people = object : Collection("people") {
            val byFullName = unique("name.first", "name.last")
        }
        val db = NoSqlite { +people }
        db.write {
            people.insert(user("ada", 36, first = "Ada", last = "Lovelace"))
            people.insert(user("ada2", 20, first = "Ada", last = "Byron"))
        }

        val error = assertFailsWith<NoSqliteException.UniqueViolation> {
            db.write { people.insert(user("ada3", 50, first = "Ada", last = "Lovelace")) }
        }

        assertSame(people.byFullName, error.index)
    }

    @Test
    fun `plain index lets documents share a value`() = runTest {
        val db = NoSqlite { +Users }

        db.insert(ada)
        db.insert(alan.copy(age = ada.age))

        assertEquals(listOf(listOf("2")), db.querySql("SELECT count(*) FROM users"))
    }

    @Test
    fun `caught violation leaves the write usable`() = runTest {
        val db = NoSqlite { +Users }

        db.write {
            Users.insert(ada)
            assertFailsWith<NoSqliteException.UniqueViolation> { Users.insert(ada) }
            Users.insert(alan)
        }

        assertEquals(listOf(listOf("2")), db.querySql("SELECT count(*) FROM users"))
    }

    @Test
    fun `partial unique index only covers matching documents`() = runTest {
        val db = NoSqlite { +Users }
        db.insert(user("ann", 30, nickname = "twin", active = false))
        db.insert(user("bob", 30, nickname = "twin", active = false))
        db.insert(user("cid", 30, nickname = "twin"))

        val error = assertFailsWith<NoSqliteException.UniqueViolation> { db.insert(user("dan", 30, nickname = "twin")) }

        assertSame(Users.activeNickname, error.index)
        val active = db.read { Users.list<User> { where { ("nickname" eq "twin") and "active".isTrue() } } }
        assertEquals(listOf("cid"), active.map { it.id })
    }

    @Test
    fun `a condition on a unique field finds its document`() = runTest {
        val db = NoSqlite { +Users }
        db.insert(ada)
        db.insert(alan)

        db.read {
            assertEquals(listOf(ada), Users.list<User> { where { "email" eq ada.email.value } })
            assertEquals(listOf(alan), Users.list<User> { where { "id" eq "alan" } })
            assertEquals(emptyList(), Users.list<User> { where { "id" eq "nobody" } })
        }
    }

    @Test
    fun `a condition on a unique field uses its index`() = runTest {
        val db = NoSqlite { +Users }
        val condition = where { "email" eq ada.email.value }.render(inline = true).sql

        val plan = db.querySql("EXPLAIN QUERY PLAN SELECT rowid FROM users WHERE $condition").joinToString {
            it.last()!!
        }

        assertTrue(Users.byEmail.name in plan, plan)
    }

    @Test
    fun `a condition implying a partial index's own uses it`() = runTest {
        val db = NoSqlite { +Users }
        val condition = where { ("nickname" eq "twin") and "active".isTrue() }.render(inline = true).sql

        val plan = db.querySql("EXPLAIN QUERY PLAN SELECT rowid FROM users WHERE $condition").joinToString {
            it.last()!!
        }

        assertTrue(Users.activeNickname.name in plan, plan)
    }

    @Test
    fun `path indexes cover nested fields`() = runTest {
        val db = NoSqlite { +Teams }
        val core = Team("core", Name("Ada", "Lovelace"))
        db.write { Teams.insert(core) }

        assertEquals(setOf(Teams.byLead.name, Teams.byLeadFirst.name), db.indexNames())
        assertEquals(listOf(core), db.read { Teams.list<Team> { where { "lead.last" eq "Lovelace" } } })
        assertFailsWith<NoSqliteException.UniqueViolation> {
            db.write { Teams.insert(Team("other", Name("Byron", "Lovelace"))) }
        }
    }

    @Test
    fun `same index declared twice is rejected`() {
        val error = assertFailsWith<IllegalArgumentException> {
            object : Collection("twice") {
                val first = index("age")
                val second = index("age")
            }
        }

        assertEquals("Collection 'twice' declares index twice(age) more than once", error.message)
    }

    @Test
    fun `unique index over existing duplicates fails on open`() = runTest {
        withTempFile { location ->
            val plain = object : Collection("users") {}
            val withDuplicates = NoSqlite(location) { +plain }
            withDuplicates.write { repeat(2) { plain.insert(ada) } }
            withDuplicates.close()
            val unique = object : Collection("users") {
                val byId = unique("id")
            }

            val error = assertFailsWith<NoSqliteException.UniqueViolation> { NoSqlite(location) { +unique }.read { } }

            assertEquals("users", error.collection)
        }
    }
}
