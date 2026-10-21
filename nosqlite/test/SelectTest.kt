package io.github.nailkhaf.nosqlite

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Covers the query DSL: filters, ordering, projections and the first, list and count terminals. */
internal class SelectTest {

    object Users : Collection("users") {
        val byId = unique(User::id)
        val byAge = index(User::age)
    }

    object Empty : Collection("empty")

    @Serializable
    data class Summary(val id: String, val age: Int)

    private val ann = user("ann", 17, tags = listOf("kotlin", "sql"))
    private val bob = user("bob", 30, tags = listOf("go"))
    private val cid = user("cid", 45, last = "Jones", tags = listOf("kotlin"))
    private val dan = user("dan", 30, last = "Adams", tags = listOf("kotlin"))

    /** A database holding ann, bob, cid and dan, inserted in that order. */
    private suspend fun seeded(): Database = Database {
        +Users
        +Empty
    }.apply { write { listOf(ann, bob, cid, dan).forEach { Users.insert(it) } } }

    @Test
    fun `without filter or order documents come in insertion order`() = runTest {
        val db = seeded()

        val all = db.read { Users.select<User>().list(limit = 10) }

        assertEquals(listOf(ann, bob, cid, dan), all)
    }

    @Test
    fun `list returns at most limit documents`() = runTest {
        val db = seeded()

        db.read {
            assertEquals(listOf(ann, bob), Users.select<User>().list(limit = 2))
            assertEquals(emptyList(), Users.select<User>().list(limit = 0))
        }
    }

    @Test
    fun `where keeps matching documents`() = runTest {
        val db = seeded()

        val adults = db.read {
            Users.select<User> { where { (User::age gte 18) and (User::tags contains "kotlin") } }.list(limit = 10)
        }

        assertEquals(listOf(cid, dan), adults)
    }

    @Test
    fun `several wheres must all match`() = runTest {
        val db = seeded()

        val adultSmiths = db.read {
            Users.select<User> {
                where { User::age gte 18 }
                where { User::name / Name::last eq "Smith" }
            }.list(limit = 10)
        }

        assertEquals(listOf(bob), adultSmiths)
    }

    @Test
    fun `orderBy sorts with rowid breaking ties`() = runTest {
        val db = seeded()
        val expected = listOf<Pair<SelectScope.() -> Unit, List<User>>>(
            Pair({ orderBy(User::age) }, listOf(ann, bob, dan, cid)),
            Pair({ orderBy(User::age.desc) }, listOf(cid, bob, dan, ann)),
            Pair({ orderBy(User::age.asc, (User::name / Name::last).asc) }, listOf(ann, dan, bob, cid)),
        )

        for ((order, users) in expected) {
            val select = Users.select<User>(order)
            assertEquals(users, db.read { select.list(limit = 10) }, select.toString())
        }
    }

    @Test
    fun `first returns the first match or null`() = runTest {
        val db = seeded()

        db.read {
            assertEquals(cid, Users.select<User> { orderBy(User::age.desc) }.first())
            assertNull(Users.select<User> { where { User::age gt 100 } }.first())
        }
    }

    @Test
    fun `count counts matching documents`() = runTest {
        val db = seeded()

        db.read {
            assertEquals(4, Users.select<User>().count())
            assertEquals(3, Users.select<User> { where { User::tags contains "kotlin" } }.count())
            assertEquals(0, Empty.select<User>().count())
        }
    }

    @Test
    fun `the type argument is only the decode target`() = runTest {
        val db = seeded()

        val summaries = db.read {
            Users.select<Summary> {
                where { User::age gte 18 }
                orderBy(User::age.desc)
            }.list(limit = 2)
        }

        assertEquals(listOf(Summary("cid", 45), Summary("bob", 30)), summaries)
    }

    @Test
    fun `a select runs again against the current data`() = runTest {
        val db = seeded()
        val kotlinUsers = Users.select<Summary> { where { User::tags contains "kotlin" } }

        val before = db.read { kotlinUsers.count() }
        db.write { Users.byId.delete("ann") }
        val after = db.read { kotlinUsers.count() }

        assertEquals(3, before)
        assertEquals(2, after)
    }

    @Test
    fun `a select describes itself`() {
        val select = Users.select<User> {
            where { User::age gte 18 }
            orderBy(User::age.desc)
        }

        assertEquals("select from users where data ->> '$.age' >= 18 order by age desc", select.toString())
    }

    @Test
    fun `read inside a write sees uncommitted documents`() = runTest {
        val db = seeded()

        val count = db.write {
            Users.insert(user("eve", 50))
            db.read { Users.select<User>().count() }
        }

        assertEquals(5, count)
    }

    @Test
    fun `filter and order use the declared index`() = runTest {
        val db = seeded()
        val select = Users.select<User> {
            where { User::age gte 18 }
            orderBy(User::age)
        }
        val sql = Queries.select(select, limit = 10).sql.replace("?", "10")

        val plan = db.querySql("EXPLAIN QUERY PLAN $sql").joinToString { it.last()!! }

        assertTrue(Users.byAge.name in plan, plan)
    }

    @Test
    fun `negative limit is rejected`() = runTest {
        val db = seeded()

        val error = assertFailsWith<IllegalArgumentException> { db.read { Users.select<User>().list(limit = -1) } }

        assertEquals("Negative limit: -1", error.message)
    }

    @Test
    fun `select of an undeclared collection is rejected`() = runTest {
        val db = Database { +Empty }

        assertFailsWith<NosqliteException.CollectionNotDeclared> { db.read { Users.select<User>().count() } }
    }
}
