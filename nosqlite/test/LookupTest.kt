package io.github.nailkhaf.nosqlite

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Covers reading and writing documents through their indexes and their id. */
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

    object Users : Collection("users") {
        val byNickname = index("nickname", where = { "active".isTrue() })
        val byAge = index("age")
        val byActive = index("active")
        val byName = index("name.last", "name.first")
        val byNick = index("nickname")
    }

    private val ada = User(uuid("ada"), "ada@x.com", Name("Lovelace", "Ada"), 36, nickname = "countess")
    private val alan = User(uuid("alan"), "alan@x.com", Name("Turing", "Alan"), 41)
    private val grace = User(
        uuid("grace"),
        "grace@x.com",
        Name("Hopper", "Grace"),
        36,
        active = false,
        nickname = "amazing",
    )
    private val byron = User(uuid("byron"), "byron@x.com", Name("Lovelace", "Byron"), 36)

    private suspend fun database(): NoSqlite = NoSqlite { +Users }.also { db ->
        db.write { listOf(ada, alan, grace, byron).forEach { Users.insert(it) } }
    }

    @Test
    fun `get finds the document with an id`() = runTest {
        val db = database()

        db.read {
            assertEquals(ada, Users.id(ada.id).get<User>())
            assertNull(Users.id(uuid("nobody")).get<User>())
        }
    }

    @Test
    fun `a partial index finds only the documents it indexes`() = runTest {
        val db = database()

        db.read {
            assertEquals(listOf(ada), Users.byNickname("countess").list<User>())
            assertEquals(emptyList(), Users.byNickname("amazing").list<User>())
        }
    }

    @Test
    fun `writes by id tell whether there was a document`() = runTest {
        val db = database()

        db.write {
            assertTrue(Users.id(ada.id).update(ada.copy(age = 37)))
            assertFalse(Users.id(uuid("nobody")).update(ada))
            assertTrue(Users.id(alan.id).replace(alan.copy(age = 42)))
            assertFalse(Users.id(uuid("nobody")).replace(ada))
            assertTrue(Users.id(grace.id).delete())
            assertFalse(Users.id(grace.id).delete())
        }

        assertEquals(listOf(ada.copy(age = 37), alan.copy(age = 42), byron), db.read { Users.list<User>() })
    }

    @Test
    fun `list and count read every document found`() = runTest {
        val db = database()

        db.read {
            assertEquals(listOf(ada, grace, byron), Users.byAge(36).list<User>())
            assertEquals(3, Users.byAge(36).count())
            assertEquals(listOf(grace), Users.byActive(false).list<User>())
            assertEquals(emptyList(), Users.byAge(99).list<User>())
            assertEquals(0, Users.byAge(99).count())
        }
    }

    @Test
    fun `list of a lookup takes the query builder`() = runTest {
        val db = database()

        db.read {
            assertEquals(
                listOf(grace, ada),
                Users.byAge(36).list<User> { orderBy { "name.last".asc; "id".asc }; limit(2) },
            )
            assertEquals(listOf(byron), Users.byAge(36).list<User> { where { "name.first" eq "Byron" } })
            assertEquals(listOf(byron, grace), Users.byAge(36).list<User> { orderBy { "id".asc }; offset(1) })
        }
    }

    @Test
    fun `leading values of a composite index find its prefix`() = runTest {
        val db = database()

        db.read {
            assertEquals(listOf(ada, byron), Users.byName("Lovelace").list<User>())
            assertEquals(listOf(ada), Users.byName("Lovelace", "Ada").list<User>())
        }
    }

    @Test
    fun `a null value finds null and missing fields`() = runTest {
        val db = database()

        db.read {
            assertEquals(listOf(alan, byron), Users.byNick(null).list<User>())
            assertEquals(2, Users.byNick(null).count())
        }
    }

    @Test
    fun `writes of a lookup count what they touched`() = runTest {
        val db = database()

        db.write {
            assertEquals(1, Users.byActive(false).update(grace.copy(age = 50)))
            assertEquals(1, Users.byActive(false).replace(grace.copy(age = 51)))
            assertEquals(0, Users.byAge(99).delete())
            assertEquals(1, Users.byAge(41).delete())
        }

        assertEquals(listOf(ada, grace.copy(age = 51), byron), db.read { Users.list<User>() })
    }

    @Test
    fun `a lookup reads through its index`() = runTest {
        val db = database()
        val lookup = Users.byName("Lovelace")
        val sql = Queries.selectSql(Users, Query(lookup.condition, emptyList(), limit = 1, offset = 0)).sql

        val plan = db.querySql("EXPLAIN QUERY PLAN $sql".replace("?", "'x'")).joinToString { it.last()!! }

        assertTrue(Users.byName.name in plan, plan)
    }

    @Test
    fun `upsert inserts a new document and updates an existing one in place`() = runTest {
        val db = database()
        val carol = User(uuid("carol"), "carol@x.com", Name("Shaw", "Carol"), 28)

        db.write {
            Users.upsert(carol)
            Users.upsert(ada.copy(age = 37))
        }

        assertEquals(listOf(ada.copy(age = 37), alan, grace, byron, carol), db.read { Users.list<User>() })
        assertEquals(listOf(listOf("1")), db.querySql("SELECT rowid FROM users WHERE data ->> '$.id' = '${ada.id}'"))
    }

    @Test
    fun `a lookup takes one value per field at most and plain values only`() {
        val none = assertFailsWith<IllegalArgumentException> { Users.byName() }
        val many = assertFailsWith<IllegalArgumentException> { Users.byName("Lovelace", "Ada", "King") }
        val type = assertFailsWith<IllegalArgumentException> { Users.byAge('a') }
        val float = assertFailsWith<IllegalArgumentException> { Users.byAge(0.1f) }

        assertEquals("index users(name.last, name.first) takes 1 to 2 values, not 0", none.message)
        assertEquals("index users(name.last, name.first) takes 1 to 2 values, not 3", many.message)
        assertEquals("Unsupported value: Char", type.message)
        assertEquals(FLOAT_IS_NOT_AN_SQL_REAL, float.message)
    }

    @Test
    fun `a lookup rejects NaN and infinite numbers`() {
        assertFailsWith<IllegalArgumentException> { Users.byAge(Double.NaN) }
        assertFailsWith<IllegalArgumentException> { Users.byAge(Double.NEGATIVE_INFINITY) }
    }

    @Test
    fun `a lookup reads as its index and values`() {
        assertEquals(
            "index users(name.last, name.first) = ['Lovelace', NULL]",
            Users.byName("Lovelace", null).toString(),
        )
        assertEquals("users id ${ada.id}", Users.id(ada.id).toString())
    }
}
