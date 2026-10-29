package io.github.nailkhaf.nosqlite

import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Covers reading documents one at a time with forEach. */
internal class StreamingTest {

    @Serializable
    data class User(val id: String, val age: Int? = null, val score: Double? = null)

    object Users : Collection("users")

    object Log : Collection("log")

    private val users = listOf(
        User("ann", 30, 1.5),
        User("bob", null, 2.5),
        User("cid", 30, null),
        User("dan", 20, 1.5),
        User("eve", null, null),
        User("fay", 40, 0.5),
        User("gus", 20, 2.5),
    )

    private suspend fun database(): NoSqlite = NoSqlite {
        +Users
        +Log
    }.also { db -> db.write { users.forEach { Users.insert(it) } } }

    private val orders = listOf<QueryScope.() -> Unit>(
        { },
        { orderBy { "age".asc } },
        { orderBy { "age".desc } },
        { orderBy { "score".asc; "age".desc } },
        { orderBy { "score".desc; "age".asc } },
        { orderBy { "id".desc } },
    )

    @Test
    fun `forEach visits every document`() = runTest {
        val db = database()
        val visited = mutableListOf<User>()

        db.read { Users.forEach<User> { visited += it } }

        assertEquals(users.size, visited.size)
        assertEquals(users.toSet(), visited.toSet())
    }

    @Test
    fun `forEach follows the order and the condition`() = runTest {
        val db = database()

        for ((i, order) in orders.withIndex()) {
            val query: QueryScope.() -> Unit = { order(); where { "age".isNotNull() } }
            val visited = mutableListOf<User>()
            db.read { Users.forEach<User>(query) { visited += it } }

            assertEquals(db.read { Users.list<User>(query) }, visited, "order $i")
        }
    }

    @Test
    fun `forEach's action can run other queries and the same one too`() = runTest {
        val db = database()
        val pairs = mutableListOf<Pair<String, Int>>()

        db.read {
            Users.forEach<User> { outer ->
                var inner = 0
                Users.forEach<User> { inner++ }
                pairs += outer.id to inner + Users.list<User>().size
            }
        }

        assertEquals(users.map { it.id to users.size * 2 }, pairs)
    }

    @Test
    fun `forEach inside a write can write to other collections`() = runTest {
        val db = database()

        db.write { Users.forEach<User> { if (it.age == 20) Log.insert(it) } }

        assertEquals(users.filter { it.age == 20 }, db.read { Log.list<User>() })
    }

    @Test
    fun `forEach stops when its action throws and the database keeps working`() = runTest {
        val db = database()
        var visited = 0

        assertFailsWith<IllegalStateException> {
            db.read {
                Users.forEach<User> {
                    visited++
                    if (visited == 3) error("stop")
                }
            }
        }

        assertEquals(3, visited)
        assertEquals(users, db.read { Users.list<User>() })
    }

    @Test
    fun `forEach stops when cancelled`() = runTest {
        val db = database()
        var visited = 0

        launch {
            db.read {
                Users.forEach<User> {
                    visited++
                    if (visited == 2) cancel()
                }
            }
        }.join()

        assertEquals(2, visited)
    }

    @Test
    fun `forEach keeps to the limit and offset`() = runTest {
        val db = database()
        val visited = mutableListOf<User>()

        db.read { Users.forEach<User>({ orderBy { "age".asc }; limit(2); offset(1) }) { visited += it } }

        assertEquals(db.read { Users.list<User> { orderBy { "age".asc }; limit(2); offset(1) } }, visited)
    }
}
