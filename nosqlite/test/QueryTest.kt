package io.github.nailkhaf.nosqlite

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Covers sorting and counting documents. */
internal class QueryTest {

    @Serializable
    data class Name(val first: String, val last: String)

    @Serializable
    data class User(val id: String, val name: Name, val age: Int? = null)

    object Users : Collection<User>("users", User.serializer()) {
        val byAge = index("age")
    }

    private val ada = User("ada", Name("Ada", "Lovelace"), 36)
    private val alan = User("alan", Name("Alan", "Turing"), 41)
    private val grace = User("grace", Name("Grace", "Hopper"), 36)
    private val linus = User("linus", Name("Linus", "Torvalds"))

    private suspend fun database(): Database = Database { +Users }.also { db ->
        db.write { listOf(ada, alan, grace, linus).forEach { Users.insert(it) } }
    }

    @Test
    fun `asc puts the smallest first and nulls first`() = runTest {
        val db = database()

        val users = db.read { Users.list(limit = 10, orderBy = listOf(Order.asc("age"))) }

        assertEquals(listOf(linus, ada, grace, alan), users)
    }

    @Test
    fun `desc puts the largest first and nulls last`() = runTest {
        val db = database()

        val users = db.read { Users.list(limit = 10, orderBy = listOf(Order.desc("age"))) }

        assertEquals(listOf(alan, ada, grace, linus), users)
    }

    @Test
    fun `equal keys keep insertion order`() = runTest {
        val db = database()

        val users = db.read { Users.list(limit = 10, orderBy = listOf(Order.asc("age"))) { "age" eq 36 } }

        assertEquals(listOf(ada, grace), users)
    }

    @Test
    fun `later keys break ties of earlier ones`() = runTest {
        val db = database()

        val users = db.read {
            Users.list(limit = 10, orderBy = listOf(Order.desc("age"), Order.asc("name.last")))
        }

        assertEquals(listOf(alan, grace, ada, linus), users)
    }

    @Test
    fun `nested fields sort`() = runTest {
        val db = database()

        val users = db.read { Users.list(limit = 10, orderBy = listOf(Order.asc("name.last"))) }

        assertEquals(listOf(grace, ada, linus, alan), users)
    }

    @Test
    fun `pages follow the order`() = runTest {
        val db = database()
        val order = listOf(Order.desc("age"))

        db.read {
            assertEquals(listOf(alan, ada), Users.list(limit = 2, orderBy = order))
            assertEquals(listOf(grace, linus), Users.list(limit = 2, offset = 2, orderBy = order))
            assertEquals(listOf(ada), Users.list(limit = 1, offset = 1, orderBy = order) { "age".isNotNull() })
        }
    }

    @Test
    fun `sorting by an indexed field reads the index in order`() = runTest {
        val db = database()
        val sql = Queries.select(Users, null, listOf(Order.asc("age")), limit = 10, offset = 0).sql

        val plan = db.querySql("EXPLAIN QUERY PLAN $sql".replace("?", "1")).joinToString { it.last()!! }

        assertTrue(Users.byAge.name in plan, plan)
        assertFalse("TEMP B-TREE" in plan, plan)
    }

    @Test
    fun `invalid order fields are rejected`() {
        val error = assertFailsWith<IllegalArgumentException> { Order.desc("first name") }

        assertEquals("Invalid field 'first name': keys must be identifiers", error.message)
    }

    @Test
    fun `orders read as their field and direction`() {
        assertEquals("name.last asc", Order.asc("name.last").toString())
        assertEquals("age desc", Order.desc("age").toString())
    }

    @Test
    fun `count counts matching documents`() = runTest {
        val db = database()

        db.read {
            assertEquals(4, Users.count())
            assertEquals(2, Users.count { "age" eq 36 })
            assertEquals(0, Users.count { "age" gt 50 })
        }
    }

    @Test
    fun `count of an empty collection is zero`() = runTest {
        val db = Database { +Users }

        assertEquals(0, db.read { Users.count() })
    }
}
