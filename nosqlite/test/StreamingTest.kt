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

    object Users : Collection<User>("users", User.serializer())

    object Log : Collection<User>("log", User.serializer())

    private val users = listOf(
        User("ann", 30, 1.5),
        User("bob", null, 2.5),
        User("cid", 30, null),
        User("dan", 20, 1.5),
        User("eve", null, null),
        User("fay", 40, 0.5),
        User("gus", 20, 2.5),
    )

    private suspend fun database(): Database = Database {
        +Users
        +Log
    }.also { db -> db.write { users.forEach { Users.insert(it) } } }

    private val orders = listOf<QueryScope.() -> Unit>(
        { },
        { orderBy(asc("age")) },
        { orderBy(desc("age")) },
        { orderBy(asc("score"), desc("age")) },
        { orderBy(desc("score"), asc("age")) },
        { orderBy(desc("id")) },
    )

    @Test
    fun `forEach visits every document in insertion order`() = runTest {
        val db = database()
        val visited = mutableListOf<User>()

        db.read { Users.forEach { visited += it } }

        assertEquals(users, visited)
    }

    @Test
    fun `forEach follows the order and the condition`() = runTest {
        val db = database()

        for ((i, order) in orders.withIndex()) {
            val query: QueryScope.() -> Unit = { order(); where { "age".isNotNull() } }
            val visited = mutableListOf<User>()
            db.read { Users.forEach(query) { visited += it } }

            assertEquals(db.read { Users.list(query) }, visited, "order $i")
        }
    }

    @Test
    fun `forEach's action can run other queries and the same one too`() = runTest {
        val db = database()
        val pairs = mutableListOf<Pair<String, Int>>()

        db.read {
            Users.forEach { outer ->
                var inner = 0
                Users.forEach { inner++ }
                pairs += outer.id to inner + Users.list().size
            }
        }

        assertEquals(users.map { it.id to users.size * 2 }, pairs)
    }

    @Test
    fun `forEach inside a write can write to other collections`() = runTest {
        val db = database()

        db.write { Users.forEach { if (it.age == 20) Log.insert(it) } }

        assertEquals(users.filter { it.age == 20 }, db.read { Log.list() })
    }

    @Test
    fun `forEach stops when its action throws and the database keeps working`() = runTest {
        val db = database()
        var visited = 0

        assertFailsWith<IllegalStateException> {
            db.read {
                Users.forEach {
                    visited++
                    if (visited == 3) error("stop")
                }
            }
        }

        assertEquals(3, visited)
        assertEquals(users, db.read { Users.list() })
    }

    @Test
    fun `forEach stops when cancelled`() = runTest {
        val db = database()
        var visited = 0

        launch {
            db.read {
                Users.forEach {
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

        db.read { Users.forEach({ orderBy(asc("age")); limit(2); offset(1) }) { visited += it } }

        assertEquals(db.read { Users.list { orderBy(asc("age")); limit(2); offset(1) } }, visited)
    }
}
