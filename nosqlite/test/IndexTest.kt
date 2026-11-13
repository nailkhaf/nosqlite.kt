package io.github.nailkhaf.nosqlite

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.uuid.Uuid
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Covers declaring indexes and syncing them with the file. The id index is covered in [IdTest]. */
internal class IndexTest {

    object Users : Collection("users") {
        val byEmail = index("email")
        val byAge = index("age")
        val byName = index("name.last", "name.first")
        val activeNickname = index("nickname", where = { "active".isTrue() })
    }

    object Teams : Collection("teams") {
        val byLead = index("lead.last")
        val byLeadFirst = index("lead.first")
    }

    @Serializable
    data class Team(val id: Uuid, val name: String, val lead: Name)

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
                """CREATE UNIQUE INDEX "${idIndexName(Users)}" ON "nsq_users" (data ->> '$.id')""",
                """CREATE INDEX "${Users.byEmail.name}" ON "nsq_users" (data ->> '$.email')""",
                """CREATE INDEX "${Users.byAge.name}" ON "nsq_users" (data ->> '$.age')""",
                """CREATE INDEX "${Users.byName.name}" ON "nsq_users" (data ->> '$.name.last', data ->> '$.name.first')""",
                """CREATE INDEX "${Users.activeNickname.name}" ON "nsq_users" (data ->> '$.nickname') """ +
                    "WHERE data ->> '$.active' = 1",
            ),
            sql,
        )
    }

    @Test
    fun `index names hash their definition`() {
        assertTrue(Users.byEmail.name.matches(Regex("nsq_idx_users_[0-9a-f]{16}")))
        assertEquals("nsq_idx_users_id", idIndexName(Users))
        // Read through the base type: Kotlin/Native fails to link an internal member read through a test object.
        val collection: Collection = Users
        assertEquals(4, collection.indexes.map { it.name }.toSet().size)
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
                val byEmail = index("email")
            }
            val after = object : Collection("users") {
                val byEmail = index("email")
                val byLastName = index("name.last")
            }
            NoSqlite(location) { +before }.apply { read { } }.close()

            val db = NoSqlite(location) { +after }

            assertEquals(setOf(after.byEmail.name, after.byLastName.name, idIndexName(after)), db.indexNames())
            assertEquals(before.byEmail.name, after.byEmail.name)
            assertEquals(idIndexName(before), idIndexName(after))
            db.close()
        }
    }

    @Test
    fun `changing an index definition recreates it`() = runTest {
        withTempFile { location ->
            val plain = object : Collection("users") {
                val byEmail = index("email")
            }
            val wider = object : Collection("users") {
                val byEmail = index("email", "name")
            }
            NoSqlite(location) { +plain }.apply { read { } }.close()

            val db = NoSqlite(location) { +wider }

            assertEquals(setOf(wider.byEmail.name, idIndexName(wider)), db.indexNames())
            assertTrue(plain.byEmail.name != wider.byEmail.name)
            db.close()
        }
    }

    @Test
    fun `reopening keeps indexes nosqlite does not manage`() = runTest {
        withTempFile { location ->
            val db = NoSqlite(location) { +Users }
            db.pool().writeSql { execute("CREATE INDEX mine ON nsq_users (data ->> '$.score')") }
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
            db.pool().writeSql { execute("CREATE INDEX \"nsq_idx_users_\"\"x\" ON nsq_users (data ->> '$.score')") }
            db.close()

            val reopened = NoSqlite(location) { +Users }

            assertTrue("nsq_idx_users_\"x" !in reopened.indexNames())
            reopened.close()
        }
    }

    @Test
    fun `plain index lets documents share a value`() = runTest {
        val db = NoSqlite { +Users }

        db.insert(ada)
        db.insert(alan.copy(age = ada.age))

        assertEquals(listOf(listOf("2")), db.querySql("SELECT count(*) FROM nsq_users"))
    }

    @Test
    fun `a condition finds its document`() = runTest {
        val db = NoSqlite { +Users }
        db.insert(ada)
        db.insert(alan)

        db.read {
            assertEquals(listOf(ada), Users.list<User> { where { "email" eq ada.email.value } })
            assertEquals(listOf(alan), Users.list<User> { where { "id" eq alan.id.toString() } })
            assertEquals(emptyList(), Users.list<User> { where { "id" eq uuid("nobody").toString() } })
        }
    }

    @Test
    fun `getting a document by id uses the id index`() = runTest {
        val db = NoSqlite { +Users }
        val sql = Queries.selectSql(
            Users,
            Query(Users.id(ada.id).condition, emptyList(), limit = 1, offset = 0),
        ).sql

        val plan = db.querySql("EXPLAIN QUERY PLAN $sql".replace("?", "'${ada.id}'")).joinToString { it.last()!! }

        assertTrue(idIndexName(Users) in plan, plan)
    }

    @Test
    fun `a condition on id uses the id index`() = runTest {
        val db = NoSqlite { +Users }
        val condition = where { "id" eq ada.id.toString() }.render(inline = true).sql

        val plan = db.querySql("EXPLAIN QUERY PLAN SELECT rowid FROM nsq_users WHERE $condition").joinToString {
            it.last()!!
        }

        assertTrue(idIndexName(Users) in plan, plan)
    }

    @Test
    fun `a condition implying a partial index's own uses it`() = runTest {
        val db = NoSqlite { +Users }
        val condition = where { ("nickname" eq "twin") and "active".isTrue() }.render(inline = true).sql

        val plan = db.querySql("EXPLAIN QUERY PLAN SELECT rowid FROM nsq_users WHERE $condition").joinToString {
            it.last()!!
        }

        assertTrue(Users.activeNickname.name in plan, plan)
    }

    @Test
    fun `path indexes cover nested fields`() = runTest {
        val db = NoSqlite { +Teams }
        val core = Team(uuid("core"), "core", Name("Ada", "Lovelace"))
        db.write { Teams.insert(core) }

        assertEquals(setOf(Teams.byLead.name, Teams.byLeadFirst.name, idIndexName(Teams)), db.indexNames())
        assertEquals(listOf(core), db.read { Teams.list<Team> { where { "lead.last" eq "Lovelace" } } })
    }

    @Test
    fun `users and users_id both open`() = runTest {
        val users = object : Collection("users") {
            val byEmail = index("email")
        }
        val usersId = object : Collection("users_id") {
            val byEmail = index("email")
        }
        val db = NoSqlite {
            +users
            +usersId
        }

        db.write {
            users.insert(ada)
            usersId.insert(ada)
        }

        assertEquals(listOf(ada), db.read { users.list<User>() })
        assertEquals(listOf(ada), db.read { usersId.list<User>() })
        assertEquals(
            setOf(users.byEmail.name, usersId.byEmail.name, idIndexName(users), idIndexName(usersId)),
            db.indexNames(),
        )
    }

    @Test
    fun `stored index names use the idx prefix and no colon`() = runTest {
        val db = NoSqlite { +Users }
        db.read { }

        val names = db.querySql(
            "SELECT name FROM sqlite_master WHERE type = 'index' AND name NOT LIKE 'sqlite%'",
        ).map { it.single()!! }
        val collection: Collection = Users

        assertEquals(setOf(idIndexName(Users)) + collection.indexes.map { it.name }, names.toSet())
        assertTrue(names.all { it.startsWith("nsq_idx_users_") && ':' !in it })
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
}

/** Kotlin/Native cannot link an internal member read through a test object. */
private fun idIndexName(collection: Collection): String = Queries.idIndexName(collection.name)
