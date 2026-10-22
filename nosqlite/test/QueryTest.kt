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

        val users = db.read { Users.list { orderBy { "age".asc } } }

        assertEquals(listOf(linus, ada, grace, alan), users)
    }

    @Test
    fun `desc puts the largest first and nulls last`() = runTest {
        val db = database()

        val users = db.read { Users.list { orderBy { "age".desc } } }

        assertEquals(listOf(alan, ada, grace, linus), users)
    }

    @Test
    fun `equal keys keep insertion order`() = runTest {
        val db = database()

        val users = db.read { Users.list { where { "age" eq 36 }; orderBy { "age".asc } } }

        assertEquals(listOf(ada, grace), users)
    }

    @Test
    fun `later keys break ties of earlier ones`() = runTest {
        val db = database()

        val users = db.read {
            Users.list { orderBy { "age".desc; "name.last".asc } }
        }

        assertEquals(listOf(alan, grace, ada, linus), users)
    }

    @Test
    fun `nested fields sort`() = runTest {
        val db = database()

        val users = db.read { Users.list { orderBy { "name.last".asc } } }

        assertEquals(listOf(grace, ada, linus, alan), users)
    }

    @Test
    fun `pages follow the order`() = runTest {
        val db = database()
        db.read {
            assertEquals(listOf(alan, ada), Users.list { orderBy { "age".desc }; limit(2) })
            assertEquals(listOf(grace, linus), Users.list { orderBy { "age".desc }; limit(2); offset(2) })
            assertEquals(
                listOf(ada),
                Users.list {
                    where { "age".isNotNull() }
                    orderBy { "age".desc }
                    limit(1)
                    offset(1)
                },
            )
        }
    }

    @Test
    fun `sorting by an indexed field reads the index in order`() = runTest {
        val db = database()
        val sql = Queries.select(Users, query { orderBy { "age".asc } }).sql

        val plan = db.querySql("EXPLAIN QUERY PLAN $sql".replace("?", "1")).joinToString { it.last()!! }

        assertTrue(Users.byAge.name in plan, plan)
        assertFalse("TEMP B-TREE" in plan, plan)
    }

    @Test
    fun `invalid order fields are rejected`() {
        val error = assertFailsWith<IllegalArgumentException> { query { orderBy { "first name".desc } } }

        assertEquals("Invalid field 'first name': keys must be identifiers", error.message)
    }

    @Test
    fun `orders read as their field and direction`() {
        val query = query { orderBy { "name.last".asc; "age".desc } }

        assertEquals("[name.last asc, age desc]", query.orderBy.toString())
    }

    @Test
    fun `each part of a query is set once`() {
        val messages = listOf<QueryScope.() -> Unit>(
            { where { "age" gt 1 }; where { "age" lt 9 } },
            { orderBy { "age".asc }; orderBy { "age".desc } },
            { limit(1); limit(2) },
            { offset(1); offset(2) },
        ).map { assertFailsWith<IllegalStateException> { query(it) }.message }

        assertEquals(
            listOf("where is already set", "orderBy is already set", "limit is already set", "offset is already set"),
            messages,
        )
    }

    @Test
    fun `a query without parts selects everything in insertion order`() {
        val query = query { }

        assertEquals(null, query.condition)
        assertEquals(emptyList(), query.orderBy)
        assertEquals(Int.MAX_VALUE, query.limit)
        assertEquals(0, query.offset)
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
