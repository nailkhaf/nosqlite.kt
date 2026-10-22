package io.github.nailkhaf.nosqlite

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Covers reading and writing documents through their indexes, and upserts. */
internal class LookupTest {

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
        val byFullName = unique("name.last", "name.first")
        val byAge = index("age")
        val byActive = index("active")
        val byName = index("name.last", "name.first")
        val byNick = index("nickname")
    }

    private val ada = User("ada", "ada@x.com", Name("Lovelace", "Ada"), 36, nickname = "countess")
    private val alan = User("alan", "alan@x.com", Name("Turing", "Alan"), 41)
    private val grace = User("grace", "grace@x.com", Name("Hopper", "Grace"), 36, active = false, nickname = "amazing")
    private val byron = User("byron", "byron@x.com", Name("Lovelace", "Byron"), 36)

    private suspend fun database(): Database = Database { +Users }.also { db ->
        db.write { listOf(ada, alan, grace, byron).forEach { Users.insert(it) } }
    }

    @Test
    fun `get finds the one document of a unique lookup`() = runTest {
        val db = database()

        db.read {
            assertEquals(ada, Users.byId("ada").get())
            assertEquals(alan, Users.byEmail("alan@x.com").get())
            assertEquals(byron, Users.byFullName("Lovelace", "Byron").get())
            assertNull(Users.byId("nobody").get())
        }
    }

    @Test
    fun `a unique lookup takes every field and no nulls`() {
        val prefix = assertFailsWith<IllegalArgumentException> { Users.byFullName("Lovelace") }
        val nulls = assertFailsWith<IllegalArgumentException> { Users.byEmail(null) }

        assertEquals("unique index users(name.last, name.first) takes 2 values, not 1", prefix.message)
        assertEquals("unique index users(email) can't look up null, any number of documents can have it", nulls.message)
    }

    @Test
    fun `a partial index finds only the documents it indexes`() = runTest {
        val db = database()

        db.read {
            assertEquals(ada, Users.byNickname("countess").get())
            assertNull(Users.byNickname("amazing").get())
        }
    }

    @Test
    fun `update and delete of a unique lookup tell whether there was a document`() = runTest {
        val db = database()

        db.write {
            assertTrue(Users.byId("ada").update(ada.copy(age = 37)))
            assertFalse(Users.byId("nobody").update(ada))
            assertTrue(Users.byEmail("grace@x.com").delete())
            assertFalse(Users.byEmail("grace@x.com").delete())
        }

        assertEquals(listOf(ada.copy(age = 37), alan, byron), db.read { Users.list() })
    }

    @Test
    fun `list and count read every document found`() = runTest {
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
    fun `list of a lookup takes the query builder`() = runTest {
        val db = database()

        db.read {
            assertEquals(listOf(grace, ada), Users.byAge(36).list { orderBy(asc("name.last")); limit(2) })
            assertEquals(listOf(byron), Users.byAge(36).list { where { "name.first" eq "Byron" } })
            assertEquals(listOf(grace, byron), Users.byAge(36).list { offset(1) })
        }
    }

    @Test
    fun `leading values of a composite index find its prefix`() = runTest {
        val db = database()

        db.read {
            assertEquals(listOf(ada, byron), Users.byName("Lovelace").list())
            assertEquals(listOf(ada), Users.byName("Lovelace", "Ada").list())
        }
    }

    @Test
    fun `a null value finds null and missing fields`() = runTest {
        val db = database()

        db.read {
            assertEquals(listOf(alan, byron), Users.byNick(null).list())
            assertEquals(2, Users.byNick(null).count())
        }
    }

    @Test
    fun `update and delete of a lookup count what they touched`() = runTest {
        val db = database()

        db.write {
            assertEquals(1, Users.byActive(false).update(grace.copy(age = 50)))
            assertEquals(0, Users.byAge(99).delete())
            assertEquals(1, Users.byAge(41).delete())
        }

        assertEquals(listOf(ada, grace.copy(age = 50), byron), db.read { Users.list() })
    }

    @Test
    fun `a lookup reads through its index`() = runTest {
        val db = database()
        val lookup = Users.byName("Lovelace")
        val sql = Queries.select(Users, Query(lookup.condition, emptyList(), limit = 1, offset = 0)).sql

        val plan = db.querySql("EXPLAIN QUERY PLAN $sql".replace("?", "'x'")).joinToString { it.last()!! }

        assertTrue(Users.byName.name in plan, plan)
    }

    @Test
    fun `upsert inserts a new document and replaces an existing one in place`() = runTest {
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
    fun `upsert on a composite index replaces the document with all its values`() = runTest {
        val db = database()
        val lord = byron.copy(id = "lord", email = "lord@x.com")

        db.write { Users.byFullName.upsert(lord) }

        assertEquals(lord, db.read { Users.byFullName("Lovelace", "Byron").get() })
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
    fun `a lookup takes one value per field at most and plain values only`() {
        val none = assertFailsWith<IllegalArgumentException> { Users.byName() }
        val many = assertFailsWith<IllegalArgumentException> { Users.byName("Lovelace", "Ada", "King") }
        val type = assertFailsWith<IllegalArgumentException> { Users.byAge('a') }

        assertEquals("index users(name.last, name.first) takes 1 to 2 values, not 0", none.message)
        assertEquals("index users(name.last, name.first) takes 1 to 2 values, not 3", many.message)
        assertEquals("Unsupported value: Char", type.message)
    }

    @Test
    fun `a lookup reads as its index and values`() {
        assertEquals(
            "index users(name.last, name.first) = ['Lovelace', NULL]",
            Users.byName("Lovelace", null).toString(),
        )
        assertEquals(
            "unique index users(nickname) where data ->> '$.active' = 1 = ['countess']",
            Users.byNickname("countess").toString(),
        )
    }
}
