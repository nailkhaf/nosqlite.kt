package io.github.nailkhaf.nosqlite

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.uuid.Uuid
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Covers sorting and counting documents. */
internal class QueryTest {

    @Serializable
    data class Name(val first: String, val last: String)

    @Serializable
    data class User(val id: Uuid, val name: Name, val age: Int? = null)

    object Users : Collection("users") {
        val byAge = index("age")
        val byName = index("name.last", "name.first")
    }

    object Teams : Collection("teams") {
        val byName = index("name")
    }

    private val ada = User(uuid("ada"), Name("Ada", "Lovelace"), 36)
    private val alan = User(uuid("alan"), Name("Alan", "Turing"), 41)
    private val grace = User(uuid("grace"), Name("Grace", "Hopper"), 36)
    private val linus = User(uuid("linus"), Name("Linus", "Torvalds"))

    private suspend fun database(): NoSqlite = NoSqlite { +Users }.also { db ->
        db.write { for (user in listOf(ada, alan, grace, linus)) Users.insert(user) }
    }

    @Test
    fun `asc puts the smallest first and nulls first`() = runTest {
        val db = database()

        val users = db.read { Users.list<User> { orderBy { "age".asc } } }

        assertEquals(listOf(null, 36, 36, 41), users.map { it.age })
        assertEquals(setOf(linus, ada, grace, alan), users.toSet())
    }

    @Test
    fun `desc puts the largest first and nulls last`() = runTest {
        val db = database()

        val users = db.read { Users.list<User> { orderBy { "age".desc } } }

        assertEquals(listOf(41, 36, 36, null), users.map { it.age })
        assertEquals(setOf(alan, ada, grace, linus), users.toSet())
    }

    @Test
    fun `a unique last key orders equal sort keys`() = runTest {
        val db = database()

        val users = db.read { Users.list<User> { where { "age" eq 36 }; orderBy { "age".asc; "id".asc } } }

        assertEquals(listOf(ada, grace), users)
    }

    @Test
    fun `later keys break ties of earlier ones`() = runTest {
        val db = database()

        val users = db.read {
            Users.list<User> { orderBy { "age".desc; "name.last".asc } }
        }

        assertEquals(listOf(alan, grace, ada, linus), users)
    }

    @Test
    fun `nested fields sort`() = runTest {
        val db = database()

        db.read {
            assertEquals(listOf(grace, ada, linus, alan), Users.list<User> { orderBy { "name.last".asc } })
            assertEquals(listOf(alan, linus, ada, grace), Users.list<User> { orderBy { "name.last".desc } })
        }
    }

    @Test
    fun `pages follow the order`() = runTest {
        val db = database()
        db.read {
            assertEquals(listOf(alan, ada), Users.list<User> { orderBy { "age".desc; "id".asc }; limit(2) })
            assertEquals(
                listOf(grace, linus),
                Users.list<User> { orderBy { "age".desc; "id".asc }; limit(2); offset(2) },
            )
            assertEquals(
                listOf(ada),
                Users.list<User> {
                    where { "age".isNotNull() }
                    orderBy { "age".desc; "id".asc }
                    limit(1)
                    offset(1)
                },
            )
        }
    }

    @Test
    fun `sorting by an indexed field reads the index in order`() = runTest {
        val db = database()
        val sql = Queries.selectSql(Users.name, query { orderBy { "age".asc } }).sql

        val plan = db.querySql("EXPLAIN QUERY PLAN $sql".replace("?", "1")).joinToString { it.last()!! }

        assertTrue(Users.byAge.name in plan, plan)
        assertFalse("TEMP B-TREE" in plan, plan)
    }

    @Test
    fun `a descending sort on an indexed field uses the index without a temp b-tree`() = runTest {
        val db = database()
        val sql = Queries.selectSql(Users.name, query { orderBy { "age".desc } }).sql

        val plan = db.querySql("EXPLAIN QUERY PLAN $sql".replace("?", "1")).joinToString { it.last()!! }

        assertTrue(Users.byAge.name in plan, plan)
        assertFalse("TEMP B-TREE" in plan, plan)
    }

    @Test
    fun `without orderBy documents come in id order whatever order they were written in`() = runTest {
        val db = NoSqlite { +Users }
        db.write { for (user in listOf(linus, grace, alan, ada)) Users.insert(user) }

        db.read {
            assertEquals(listOf(ada, alan, grace, linus), Users.list<User>())
            assertEquals(listOf(grace, linus), Users.list<User> { limit(2); offset(2) })
        }
    }

    @Test
    fun `equal keys of an indexed sort come in id order ascending and newest first descending`() = runTest {
        val db = NoSqlite { +Users }
        db.write { for (user in listOf(linus, grace, alan, ada)) Users.insert(user) }

        db.read {
            assertEquals(listOf(linus, ada, grace, alan), Users.list<User> { orderBy { "age".asc } })
            assertEquals(listOf(alan, grace, ada, linus), Users.list<User> { orderBy { "age".desc } })
        }
    }

    @Test
    fun `sorting by id reads the key in either direction`() = runTest {
        val db = NoSqlite { +Users }
        db.write { for (user in listOf(linus, grace, alan, ada)) Users.insert(user) }

        for (descending in listOf(false, true)) {
            val query = query { orderBy { if (descending) "id".desc else "id".asc } }
            val sql = Queries.selectSql(Users.name, query).sql

            val plan = db.querySql("EXPLAIN QUERY PLAN $sql".replace("?", "1")).joinToString { it.last()!! }

            assertFalse("TEMP B-TREE" in plan, plan)
        }
        db.read {
            assertEquals(listOf(ada, alan, grace, linus), Users.list<User> { orderBy { "id".asc } })
            assertEquals(listOf(linus, grace, alan, ada), Users.list<User> { orderBy { "id".desc } })
        }
    }

    @Test
    fun `an index sorts by its fields`() = runTest {
        val db = database()

        db.read {
            assertEquals(listOf(linus, ada, grace, alan), Users.list<User> { orderBy { Users.byAge.asc } })
            assertEquals(listOf(alan, grace, ada, linus), Users.list<User> { orderBy { Users.byAge.desc } })
            assertEquals(listOf(grace, ada, linus, alan), Users.list<User> { orderBy { Users.byName.asc } })
            assertEquals(listOf(alan, linus, ada, grace), Users.list<User> { orderBy { Users.byName.desc } })
        }
    }

    @Test
    fun `byId sorts by id alone`() = runTest {
        val db = database()

        db.read {
            assertEquals(listOf(ada, alan, grace, linus), Users.list<User> { orderBy { Users.byId.asc } })
            assertEquals(listOf(linus, grace, alan, ada), Users.list<User> { orderBy { Users.byId.desc } })
        }
    }

    @Test
    fun `nothing is added to a sort`() {
        fun orderOf(block: QueryBlock) = Queries.selectSql(Users.name, query(block)).sql.substringAfter("\"nsq_users\"")

        assertEquals(" ORDER BY data ->> '$.age' DESC LIMIT ? OFFSET ?;", orderOf { orderBy { "age".desc } })
        assertEquals(
            " ORDER BY data ->> '$.name.last' ASC, data ->> '$.name.first' ASC LIMIT ? OFFSET ?;",
            orderOf { orderBy { Users.byName.asc } },
        )
        assertEquals(" ORDER BY id DESC LIMIT ? OFFSET ?;", orderOf { orderBy { Users.byId.desc } })
    }

    @Test
    fun `sorting by an index reads the index without sorting`() = runTest {
        val db = database()

        for ((index, order) in listOf<Pair<FieldIndex, QueryBlock>>(
            Users.byAge to { orderBy { Users.byAge.asc } },
            Users.byAge to { orderBy { Users.byAge.desc } },
            Users.byName to { orderBy { Users.byName.asc } },
            Users.byName to { orderBy { Users.byName.desc } },
        )) {
            val sql = Queries.selectSql(Users.name, query { order(); limit(2) }).sql

            val plan = db.querySql("EXPLAIN QUERY PLAN $sql".replace("?", "1")).joinToString { it.last()!! }

            assertTrue(index.name in plan, plan)
            assertFalse("TEMP B-TREE" in plan, plan)
        }
    }

    @Test
    fun `sorting by an index of another collection is rejected`() {
        val error = assertFailsWith<IllegalArgumentException> {
            Queries.selectSql(Users.name, query { orderBy { Teams.byName.asc } })
        }

        assertEquals("Collection 'users' can't sort by an index of 'teams'", error.message)
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
        assertEquals(
            "[name.last desc, name.first desc]",
            query { orderBy { Users.byName.desc } }.orderBy.toString(),
        )
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
    fun `a query without parts selects everything`() {
        val query = query { }

        assertEquals(null, query.condition)
        assertEquals(emptyList(), query.orderBy)
        assertEquals(Int.MAX_VALUE, query.limit)
        assertEquals(0, query.offset)
        assertFalse("ORDER BY" in Queries.selectSql(Users.name, query).sql)
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
        val db = NoSqlite { +Users }

        assertEquals(0, db.read { Users.count() })
    }
}
