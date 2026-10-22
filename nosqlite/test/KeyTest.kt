package io.github.nailkhaf.nosqlite

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Covers reading and writing documents by the keys of their indexes, and upserts. */
internal class KeyTest {

    @Serializable
    data class Name(val last: String, val first: String)

    @Serializable
    data class User(
        val id: String,
        val email: String,
        val name: Name,
        val age: Int,
        val active: Boolean = true,
        val nickname: String? = null,
    )

    object Users : Collection<User>("users", User.serializer()) {
        val byId = unique("id")
        val byEmail = unique("email")
        val byNickname = unique("nickname", where = { "active".isTrue() })
        val byName = unique("name.last", "name.first")
        val byAge = index("age")
        val byActive = index("active")
    }

    private val ada = User("ada", "ada@x.com", Name("Lovelace", "Ada"), 36, nickname = "countess")
    private val alan = User("alan", "alan@x.com", Name("Turing", "Alan"), 41)
    private val grace = User("grace", "grace@x.com", Name("Hopper", "Grace"), 36, active = false, nickname = "amazing")
    private val byron = User("byron", "byron@x.com", Name("Lovelace", "Byron"), 36)

    private suspend fun database(): Database = Database { +Users }.also { db ->
        db.write { listOf(ada, alan, grace, byron).forEach { Users.insert(it) } }
    }

    @Test
    fun `get finds the document with a unique key`() = runTest {
        val db = database()

        db.read {
            assertEquals(ada, Users.byId("ada").get())
            assertEquals(alan, Users.byEmail("alan@x.com").get())
            assertEquals(byron, Users.byName("Lovelace", "Byron").get())
            assertNull(Users.byId("nobody").get())
        }
    }

    @Test
    fun `get needs a full key of a unique index`() = runTest {
        val db = database()

        val plain = assertFailsWith<IllegalArgumentException> { db.read { Users.byAge(36).get() } }
        val prefix = assertFailsWith<IllegalArgumentException> { db.read { Users.byName("Lovelace").get() } }

        assertEquals("get needs every field of a unique index: index users(age) = [36]", plain.message)
        assertEquals(
            "get needs every field of a unique index: unique index users(name.last, name.first) = ['Lovelace']",
            prefix.message,
        )
    }

    @Test
    fun `a partial index's key matches only the documents it indexes`() = runTest {
        val db = database()

        db.read {
            assertEquals(ada, Users.byNickname("countess").get())
            assertNull(Users.byNickname("amazing").get())
        }
    }

    @Test
    fun `list and count read every document with a key`() = runTest {
        val db = database()

        db.read {
            assertEquals(listOf(ada, grace, byron), Users.byAge(36).list())
            assertEquals(3, Users.byAge(36).count())
            assertEquals(listOf(grace), Users.byActive(false).list())
            assertEquals(emptyList(), Users.byAge(99).list())
            assertEquals(0, Users.byAge(99).count())
        }
    }

    @Test
    fun `list by key takes the query builder`() = runTest {
        val db = database()

        db.read {
            assertEquals(listOf(grace, ada), Users.byAge(36).list { orderBy(asc("name.last")); limit(2) })
            assertEquals(listOf(byron), Users.byAge(36).list { where { "name.first" eq "Byron" } })
            assertEquals(listOf(grace, byron), Users.byAge(36).list { offset(1) })
        }
    }

    @Test
    fun `leading values of a composite index match its prefix`() = runTest {
        val db = database()

        db.read {
            assertEquals(listOf(ada, byron), Users.byName("Lovelace").list())
            assertEquals(listOf(ada), Users.byName("Lovelace", "Ada").list())
        }
    }

    @Test
    fun `a key reads through its index`() = runTest {
        val db = database()
        val key = Users.byName("Lovelace")
        val sql = Queries.select(Users, Query(key.condition, emptyList(), limit = 1, offset = 0)).sql

        val plan = db.querySql("EXPLAIN QUERY PLAN $sql".replace("?", "'x'")).joinToString { it.last()!! }

        assertTrue(Users.byName.name in plan, plan)
    }

    @Test
    fun `update and delete by key count what they touched`() = runTest {
        val db = database()

        db.write {
            assertEquals(1, Users.byId("ada").update(ada.copy(age = 37)))
            assertEquals(0, Users.byId("nobody").update(ada))
            assertEquals(2, Users.byAge(36).delete())
            assertEquals(0, Users.byEmail("grace@x.com").delete())
        }

        assertEquals(listOf(ada.copy(age = 37), alan), db.read { Users.list() })
    }

    @Test
    fun `upsert inserts a new key and replaces an existing one in place`() = runTest {
        val db = database()
        val carol = User("carol", "carol@x.com", Name("Shaw", "Carol"), 28)

        db.write {
            Users.byEmail.upsert(carol)
            Users.byEmail.upsert(ada.copy(id = "ada2", age = 37))
        }

        assertEquals(listOf(ada.copy(id = "ada2", age = 37), alan, grace, byron, carol), db.read { Users.list() })
        assertEquals(listOf(listOf("1")), db.querySql("SELECT rowid FROM users WHERE data ->> '$.id' = 'ada2'"))
    }

    @Test
    fun `upsert on a partial index conflicts only with the documents it indexes`() = runTest {
        val db = database()
        val neo = User("neo", "neo@x.com", Name("Anderson", "Thomas"), 30, nickname = "amazing")

        db.write {
            Users.byNickname.upsert(neo)
            Users.byNickname.upsert(neo.copy(age = 31))
        }

        db.read {
            assertEquals(neo.copy(age = 31), Users.byNickname("amazing").get())
            assertEquals(grace, Users.byId("grace").get())
        }
    }

    @Test
    fun `upsert on a composite index replaces the full key's document`() = runTest {
        val db = database()

        db.write { Users.byName.upsert(byron.copy(id = "lord", email = "lord@x.com")) }

        assertEquals(byron.copy(id = "lord", email = "lord@x.com"), db.read { Users.byName("Lovelace", "Byron").get() })
    }

    @Test
    fun `upsert that conflicts in another unique index fails`() = runTest {
        val db = database()

        val error = assertFailsWith<NosqliteException.UniqueViolation> {
            db.write { Users.byEmail.upsert(alan.copy(id = "ada", email = "new@x.com", name = Name("New", "Name"))) }
        }

        assertSame(Users.byId, error.index)
    }

    @Test
    fun `upsert needs a unique index`() = runTest {
        val db = database()

        val error = assertFailsWith<IllegalArgumentException> { db.write { Users.byAge.upsert(ada) } }

        assertEquals("upsert needs a unique index: index users(age)", error.message)
    }

    @Test
    fun `a null key matches null and missing fields`() = runTest {
        val db = database()

        db.read {
            assertEquals(listOf(alan, byron), Users.byNickname(null).list())
            assertEquals(2, Users.byNickname(null).count())
        }
        val error = assertFailsWith<IllegalArgumentException> { db.read { Users.byNickname(null).get() } }
        assertEquals(
            "get needs a key without nulls, many documents can share one: unique index users(nickname) " +
                "where data ->> '\$.active' = 1 = [NULL]",
            error.message,
        )
    }

    @Test
    fun `a key takes one value per field at most and plain values only`() {
        val none = assertFailsWith<IllegalArgumentException> { Users.byName() }
        val many = assertFailsWith<IllegalArgumentException> { Users.byName("Lovelace", "Ada", "King") }
        val type = assertFailsWith<IllegalArgumentException> { Users.byId('a') }

        assertEquals("unique index users(name.last, name.first) takes 1 to 2 values, not 0", none.message)
        assertEquals("unique index users(name.last, name.first) takes 1 to 2 values, not 3", many.message)
        assertEquals("Unsupported key value: Char", type.message)
    }
}
