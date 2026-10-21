package io.github.nailkhaf.nosqlite

import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Covers streaming reads: forEach over an open cursor and keyset-paginated pages. */
internal class StreamingTest {

    object Users : Collection("users") {
        val byId = unique(User::id)
        val byAge = index(User::age)
    }

    // Ages repeat and a third of the nicknames are missing, so paging must break ties and step over nulls.
    private val users = List(23) { i ->
        user("u$i", age = 20 + i % 4, nickname = if (i % 3 == 0) null else "n${i % 5}", score = (i % 7) / 2.0)
    }

    /** A database holding [users], inserted in order. */
    private suspend fun seeded(): Database = Database { +Users }.apply { write { users.forEach { Users.insert(it) } } }

    @Test
    fun `forEach visits every match in order`() = runTest {
        val db = seeded()
        val select = Users.select<User> { orderBy(User::age.desc) }
        val visited = mutableListOf<User>()

        db.read { select.forEach { visited += it } }

        assertEquals(db.read { select.list(limit = 100) }, visited)
    }

    @Test
    fun `forEach can run other queries while its cursor is open`() = runTest {
        val db = seeded()
        val looked = mutableListOf<User?>()

        db.read {
            Users.select<User> { where { User::age eq 20 } }.forEach { looked += Users.byId.get<User>(it.id) }
        }

        assertEquals<List<User?>>(users.filter { it.age == 20 }, looked)
    }

    @Test
    fun `forEach that throws closes its cursor`() = runTest {
        val db = seeded()

        assertFailsWith<IllegalStateException> { db.read { Users.select<User>().forEach { error("stop") } } }

        assertEquals(users.size.toLong(), db.read { Users.select<User>().count() })
    }

    @Test
    fun `pages split the matches by size`() = runTest {
        val db = seeded()

        val sizes = Users.select<User>().pages(db, size = 10).map { it.items.size }.toList()

        assertEquals(listOf(10, 10, 3), sizes)
    }

    @Test
    fun `pages are numbered and the last one says so`() = runTest {
        val db = seeded()

        val pages = Users.select<User>().pages(db, size = 10).toList()

        assertEquals(listOf(0, 1, 2), pages.map { it.number })
        assertEquals(listOf(false, false, true), pages.map { it.isLast })
    }

    @Test
    fun `pages cover every match once in the select's order`() = runTest {
        val db = seeded()
        val orders = listOf<SelectScope.() -> Unit>(
            {},
            { orderBy(User::age) },
            { orderBy(User::age.desc) },
            { orderBy(User::nickname.asc, User::age.desc) },
            { orderBy(User::nickname.desc, User::score.asc) },
            {
                where { User::age gte 21 }
                orderBy(User::score.desc, User::nickname.desc)
            },
        )

        for (order in orders) {
            val select = Users.select<User>(order)
            val expected = db.read { select.list(limit = 100) }
            for (size in listOf(1, 4, 5, 23, 50)) {
                val paged = select.pages(db, size).toList().flatMap { it.items }
                assertEquals(expected, paged, "$select in pages of $size")
            }
        }
    }

    @Test
    fun `no match means no page`() = runTest {
        val db = seeded()

        val pages = Users.select<User> { where { User::age gt 99 } }.pages(db, size = 10).toList()

        assertEquals(emptyList(), pages)
    }

    @Test
    fun `writes between pages neither repeat nor skip`() = runTest {
        val db = seeded()
        val seen = mutableListOf<String>()

        Users.select<User> { orderBy(User::age) }.pages(db, size = 5).collect { page ->
            seen += page.items.map { it.id }
            if (page.number == 0) {
                db.write {
                    Users.insert(user("late", age = 99)) // sorts after the cursor: shows up
                    Users.insert(user("early", age = 1)) // sorts before the cursor: stays behind
                }
            }
        }

        assertEquals(users.size + 1, seen.size)
        assertEquals(seen.size, seen.toSet().size)
        assertEquals("late", seen.last())
    }

    @Test
    fun `page size must be positive`() = runTest {
        val db = seeded()

        val error = assertFailsWith<IllegalArgumentException> { Users.select<User>().pages(db, size = 0) }

        assertEquals("Page size must be positive: 0", error.message)
    }
}
