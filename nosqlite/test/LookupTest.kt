package io.github.nailkhaf.nosqlite

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.uuid.Uuid
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
        val id: Uuid,
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
        db.write { for (user in listOf(ada, alan, grace, byron)) Users.insert(user) }
    }

    @Test
    fun `get finds the document with an id`() = runTest {
        val db = database()

        db.read {
            assertEquals(ada, Users.byId(ada.id).get<User>())
            assertNull(Users.byId(uuid("nobody")).get<User>())
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
            assertTrue(Users.byId(ada.id).update(ada.copy(age = 37)))
            assertFalse(Users.byId(uuid("nobody")).update(ada))
            assertTrue(Users.byId(alan.id).update(alan.copy(age = 42), merge = false))
            assertFalse(Users.byId(uuid("nobody")).update(ada, merge = false))
            assertTrue(Users.byId(grace.id).delete())
            assertFalse(Users.byId(grace.id).delete())
        }

        assertEquals(listOf(ada.copy(age = 37), alan.copy(age = 42), byron), db.read { Users.list<User>() })
    }

    @Test
    fun `list and count read every document found`() = runTest {
        val db = database()

        db.read {
            assertEquals(listOf(ada, byron, grace), Users.byAge(36).list<User>())
            assertEquals(3, Users.byAge(36).count())
            assertEquals(listOf(grace), Users.byActive(false).list<User>())
            assertEquals(emptyList(), Users.byAge(99).list<User>())
            assertEquals(0, Users.byAge(99).count())
        }
    }

    @Test
    fun `count of a lookup takes a condition`() = runTest {
        val db = database()

        db.read {
            assertEquals(2, Users.byAge(36).count { "active".isTrue() })
            assertEquals(1, Users.byAge(36).count { "name.last" eq "Hopper" })
            assertEquals(0, Users.byNickname("amazing").count { "age" eq 36 })
        }
    }

    @Test
    fun `forEach of a lookup streams the documents found`() = runTest {
        val db = database()
        val seen = mutableListOf<User>()

        db.read {
            Users.byAge(36).forEach<User>({ orderBy { "name.first".asc } }) { seen += it }
        }

        assertEquals(listOf(ada, byron, grace), seen)
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
    fun `a composite index finds documents by all its values`() = runTest {
        val db = database()

        db.read {
            assertEquals(listOf(ada), Users.byName("Lovelace", "Ada").list<User>())
            assertEquals(listOf(byron), Users.byName("Lovelace", "Byron").list<User>())
        }
    }

    @Test
    fun `lookups return their documents in id order`() = runTest {
        val db = NoSqlite { +Users }
        db.write { for (user in listOf(grace, byron, ada)) Users.insert(user) }

        db.read {
            assertEquals(listOf(ada, byron, grace), Users.byAge(36).list<User>())
            assertEquals(listOf(ada, byron, grace), Users.byAge(36).list<User> { limit(3) })
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
            assertEquals(1, Users.byActive(false).update(grace.copy(age = 51), merge = false))
            assertEquals(0, Users.byAge(99).delete())
            assertEquals(1, Users.byAge(41).delete())
        }

        assertEquals(listOf(ada, byron, grace.copy(age = 51)), db.read { Users.list<User>() })
    }

    @Test
    fun `a lookup reads through its index`() = runTest {
        val db = database()
        val lookup = Users.byName("Lovelace", "Ada")
        val sql = Queries.selectSql(Users.name, Query(lookup.condition, emptyList(), limit = 1, offset = 0)).sql

        val plan = db.querySql("EXPLAIN QUERY PLAN $sql".replace("?", "'x'")).joinToString { it.last()!! }

        assertTrue(Users.byName.name in plan, plan)
    }

    @Test
    fun `upsert inserts a new document and updates an existing one`() = runTest {
        val db = database()
        val carol = User(uuid("carol"), "carol@x.com", Name("Shaw", "Carol"), 28)

        db.write {
            Users.upsert(carol)
            Users.upsert(ada.copy(age = 37))
        }

        assertEquals(listOf(ada.copy(age = 37), alan, byron, grace, carol), db.read { Users.list<User>() })
    }

    @Test
    fun `calling an index in where finds what its lookup finds`() = runTest {
        val db = database()

        db.read {
            assertEquals(Users.byAge(36).list<User>(), Users.list<User> { where { Users.byAge(36) } })
            assertEquals(listOf(ada), Users.list<User> { where { Users.byName("Lovelace", "Ada") } })
            assertEquals(listOf(ada), Users.list<User> { where { Users.byNickname("countess") } })
            assertEquals(emptyList(), Users.list<User> { where { Users.byNickname("amazing") } })
            assertEquals(listOf(grace), Users.list<User> { where { Users.byId(grace.id) } })
            assertEquals(listOf(alan, byron), Users.list<User> { where { Users.byNick eq null } })
            assertEquals(
                listOf(byron),
                Users.list<User> { where { Users.byAge(36) and Users.byName("Lovelace", "Byron") } },
            )
        }
    }

    @Test
    fun `a one-field index compares its field and walks it in order`() = runTest {
        val db = database()

        db.read {
            assertEquals(listOf(alan), Users.list<User> { where { Users.byAge gt 36 } })
            assertEquals(listOf(ada, byron, grace, alan), Users.list<User> { where { Users.byAge gte 36 } })
            assertEquals(emptyList(), Users.list<User> { where { Users.byAge lt 36 } })
            assertEquals(listOf(ada, byron, grace), Users.list<User> { where { Users.byAge lte 36 } })
            assertEquals(listOf(ada, byron, grace), Users.list<User> { where { Users.byAge eq 36 } })
        }
    }

    @Test
    fun `a partial index compares only the documents it indexes`() = runTest {
        val db = database()

        db.read {
            assertEquals(listOf(ada), Users.list<User> { where { Users.byNickname gte "a" } })
        }
    }

    @Test
    fun `a multi-field index compares its fields together in either direction`() = runTest {
        val db = database()

        db.read {
            fun byName(condition: Condition, descending: Boolean): List<User> = Users.list<User> {
                where(condition)
                orderBy { if (descending) Users.byName.desc else Users.byName.asc }
            }

            assertEquals(listOf(byron, alan), byName({ Users.byName.gt("Lovelace", "Ada") }, descending = false))
            assertEquals(listOf(alan, byron), byName({ Users.byName.gt("Lovelace", "Ada") }, descending = true))
            assertEquals(listOf(ada, byron, alan), byName({ Users.byName.gte("Lovelace", "Ada") }, descending = false))
            assertEquals(listOf(alan, byron, ada), byName({ Users.byName.gte("Lovelace", "Ada") }, descending = true))
            assertEquals(listOf(grace), byName({ Users.byName.lt("Lovelace", "Ada") }, descending = false))
            assertEquals(listOf(grace), byName({ Users.byName.lt("Lovelace", "Ada") }, descending = true))
            assertEquals(listOf(grace, ada), byName({ Users.byName.lte("Lovelace", "Ada") }, descending = false))
            assertEquals(listOf(ada, grace), byName({ Users.byName.lte("Lovelace", "Ada") }, descending = true))
        }
    }

    @Test
    fun `comparing a multi-field index reads the index in either direction`() = runTest {
        val db = database()

        for (descending in listOf(false, true)) {
            val select = Queries.selectSql(
                Users.name,
                query {
                    where { Users.byName.gt("Lovelace", "Ada") }
                    orderBy { if (descending) Users.byName.desc else Users.byName.asc }
                    limit(2)
                },
            )
            val params = select.params.iterator()
            val sql = Regex("\\?").replace(select.sql) { params.next().literal }

            val plan = db.querySql("EXPLAIN QUERY PLAN $sql").joinToString { it.last()!! }

            assertTrue(Users.byName.name in plan, plan)
            assertFalse("TEMP B-TREE" in plan, plan)
        }
    }

    @Test
    fun `an index in where takes one value per field`() {
        val fewer = assertFailsWith<IllegalArgumentException> { where { Users.byName.gt("Lovelace") } }
        val many = assertFailsWith<IllegalArgumentException> { where { Users.byAge.gt(1, 2) } }
        val call = assertFailsWith<IllegalArgumentException> { where { Users.byName("Lovelace") } }

        assertEquals("index users(name.last, name.first) takes 2 values, not 1", fewer.message)
        assertEquals("index users(age) takes 1 values, not 2", many.message)
        assertEquals("index users(name.last, name.first) takes 2 values, not 1", call.message)
    }

    @Test
    fun `a multi-field comparison reads as its fields taken together`() {
        assertEquals(
            "(data ->> '$.name.last', data ->> '$.name.first') > ('Lovelace', 'Ada')",
            where { Users.byName.gt("Lovelace", "Ada") }.toString(),
        )
    }

    @Test
    fun `a lookup takes one value per field and plain values only`() {
        val none = assertFailsWith<IllegalArgumentException> { Users.byName() }
        val fewer = assertFailsWith<IllegalArgumentException> { Users.byName("Lovelace") }
        val many = assertFailsWith<IllegalArgumentException> { Users.byName("Lovelace", "Ada", "King") }
        val type = assertFailsWith<IllegalArgumentException> { Users.byAge('a') }
        val float = assertFailsWith<IllegalArgumentException> { Users.byAge(0.1f) }

        assertEquals("index users(name.last, name.first) takes 2 values, not 0", none.message)
        assertEquals("index users(name.last, name.first) takes 2 values, not 1", fewer.message)
        assertEquals("index users(name.last, name.first) takes 2 values, not 3", many.message)
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
        assertEquals("users id ${ada.id}", Users.byId(ada.id).toString())
    }
}
