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

    object Users : Collection<User>("users", User.serializer()) {
        val byId = unique("id")
        val byEmail = unique("email")
        val byAge = index("age")
        val byName = index("name.last", "name.first")
        val activeNickname = unique("nickname", where = { "active".isTrue() })
    }

    object Teams : Collection<Team>("teams", Team.serializer()) {
        val byLead = unique("lead.last")
        val byLeadFirst = index("lead.first")
    }

    @Serializable
    data class Team(val name: String, val lead: Name)

    private val ada = user("ada", 36)
    private val alan = user("alan", 41)

    private suspend fun Database.insert(user: User) = write { Users.insert(user) }

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
        val collection: Collection<User> = Users
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
            val before = object : Collection<User>("users", User.serializer()) {
                val byAge = index("age")
                val byEmail = unique("email")
            }
            val after = object : Collection<User>("users", User.serializer()) {
                val byEmail = unique("email")
                val byLastName = index("name.last")
            }
            Database(location) { +before }.apply { read { } }.close()

            val db = Database(location) { +after }

            assertEquals(setOf(after.byEmail.name, after.byLastName.name), db.indexNames())
            assertEquals(before.byEmail.name, after.byEmail.name)
            db.close()
        }
    }

    @Test
    fun `making an index unique recreates it`() = runTest {
        withTempFile { location ->
            val plain = object : Collection<User>("users", User.serializer()) {
                val byEmail = index("email")
            }
            val unique = object : Collection<User>("users", User.serializer()) {
                val byEmail = unique("email")
            }
            Database(location) { +plain }.apply { read { } }.close()

            val db = Database(location) { +unique }

            assertEquals(setOf(unique.byEmail.name), db.indexNames())
            assertTrue(plain.byEmail.name != unique.byEmail.name)
            db.close()
        }
    }

    @Test
    fun `reopening keeps indexes nosqlite does not manage`() = runTest {
        withTempFile { location ->
            val db = Database(location) { +Users }
            db.pool().writeSql { execute("CREATE INDEX mine ON users (data ->> '$.score')") }
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
    fun `composite unique index rejects only a repeated combination`() = runTest {
        val people = object : Collection<User>("people", User.serializer()) {
            val byFullName = unique("name.first", "name.last")
        }
        val db = Database { +people }
        db.write {
            people.insert(user("ada", 36, first = "Ada", last = "Lovelace"))
            people.insert(user("ada2", 20, first = "Ada", last = "Byron"))
        }

        val error = assertFailsWith<NosqliteException.UniqueViolation> {
            db.write { people.insert(user("ada3", 50, first = "Ada", last = "Lovelace")) }
        }

        assertSame(people.byFullName, error.index)
    }

    @Test
    fun `plain index lets documents share a value`() = runTest {
        val db = Database { +Users }

        db.insert(ada)
        db.insert(alan.copy(age = ada.age))

        assertEquals(listOf(listOf("2")), db.querySql("SELECT count(*) FROM users"))
    }

    @Test
    fun `caught violation leaves the write usable`() = runTest {
        val db = Database { +Users }

        db.write {
            Users.insert(ada)
            assertFailsWith<NosqliteException.UniqueViolation> { Users.insert(ada) }
            Users.insert(alan)
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
        val active = db.read { Users.list(limit = 10) { ("nickname" eq "twin") and "active".isTrue() } }
        assertEquals(listOf("cid"), active.map { it.id })
    }

    @Test
    fun `a condition on a unique field finds its document`() = runTest {
        val db = Database { +Users }
        db.insert(ada)
        db.insert(alan)

        db.read {
            assertEquals(listOf(ada), Users.list(limit = 10) { "email" eq ada.email.value })
            assertEquals(listOf(alan), Users.list(limit = 10) { "id" eq "alan" })
            assertEquals(emptyList(), Users.list(limit = 10) { "id" eq "nobody" })
        }
    }

    @Test
    fun `a condition on a unique field uses its index`() = runTest {
        val db = Database { +Users }
        val condition = where { "email" eq ada.email.value }.render(inline = true).sql

        val plan = db.querySql("EXPLAIN QUERY PLAN SELECT rowid FROM users WHERE $condition").joinToString {
            it.last()!!
        }

        assertTrue(Users.byEmail.name in plan, plan)
    }

    @Test
    fun `a condition implying a partial index's own uses it`() = runTest {
        val db = Database { +Users }
        val condition = where { ("nickname" eq "twin") and "active".isTrue() }.render(inline = true).sql

        val plan = db.querySql("EXPLAIN QUERY PLAN SELECT rowid FROM users WHERE $condition").joinToString {
            it.last()!!
        }

        assertTrue(Users.activeNickname.name in plan, plan)
    }

    @Test
    fun `path indexes cover nested fields`() = runTest {
        val db = Database { +Teams }
        val core = Team("core", Name("Ada", "Lovelace"))
        db.write { Teams.insert(core) }

        assertEquals(setOf(Teams.byLead.name, Teams.byLeadFirst.name), db.indexNames())
        assertEquals(listOf(core), db.read { Teams.list(limit = 10) { "lead.last" eq "Lovelace" } })
        assertFailsWith<NosqliteException.UniqueViolation> {
            db.write { Teams.insert(Team("other", Name("Byron", "Lovelace"))) }
        }
    }

    @Test
    fun `same index declared twice is rejected`() {
        val error = assertFailsWith<IllegalArgumentException> {
            object : Collection<User>("twice", User.serializer()) {
                val first = index("age")
                val second = index("age")
            }
        }

        assertEquals("Collection 'twice' declares index twice(age) more than once", error.message)
    }

    @Test
    fun `unique index over existing duplicates fails on open`() = runTest {
        withTempFile { location ->
            val plain = object : Collection<User>("users", User.serializer()) {}
            val withDuplicates = Database(location) { +plain }
            withDuplicates.write { repeat(2) { plain.insert(ada) } }
            withDuplicates.close()
            val unique = object : Collection<User>("users", User.serializer()) {
                val byId = unique("id")
            }

            val error = assertFailsWith<NosqliteException.UniqueViolation> { Database(location) { +unique }.read { } }

            assertEquals("users", error.collection)
        }
    }
}
