package io.github.nailkhaf.nosqlite

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Covers conditions: the SQL they render, how values are encoded, and what they match in stored documents. */
internal class WhereTest {

    object Users : Collection<User>("users", User.serializer())

    @Test
    fun `comparisons render with bound values`() {
        val condition = where { ("age" gte 18) and ("name.last" eq "Smith") }

        val fragment = condition.render()

        assertEquals("(data ->> '$.age' >= ? AND data ->> '$.name.last' = ?)", fragment.sql)
        assertEquals(listOf(SqlValue.Integer(18), SqlValue.Text("Smith")), fragment.params)
    }

    @Test
    fun `values bind as plain SQL values`() {
        fun param(block: WhereScope.() -> Where) = where(block).render().params.single()

        assertEquals(SqlValue.Text("member"), param { "role" eq "member" })
        assertEquals(SqlValue.Integer(18), param { "age" eq 18 })
        assertEquals(SqlValue.Integer(Long.MAX_VALUE), param { "id" eq Long.MAX_VALUE })
        assertEquals(SqlValue.Real(1.5), param { "score" eq 1.5 })
        assertEquals(SqlValue.Integer(1), param { "active".isTrue() }) // ->> gives booleans back as 1 and 0
        assertEquals(SqlValue.Integer(0), param { "active".isFalse() })
    }

    @Test
    fun `unsupported numbers are rejected`() {
        val big = object : Number() {
            override fun toByte() = 0.toByte()
            override fun toDouble() = 0.0
            override fun toFloat() = 0f
            override fun toInt() = 0
            override fun toLong() = 0L
            override fun toShort() = 0.toShort()
        }

        assertFailsWith<IllegalArgumentException> { where { "age" eq big } }
    }

    @Test
    fun `null checks render as IS NULL`() {
        assertEquals("data ->> '$.nickname' IS NULL", where { "nickname".isNull() }.render().sql)
        assertEquals("data ->> '$.nickname' IS NOT NULL", where { "nickname".isNotNull() }.render().sql)
    }

    @Test
    fun `isIn binds one placeholder per value`() {
        val fragment = where { "age" isIn listOf(30, 40) }.render()

        assertEquals("data ->> '$.age' IN (?, ?)", fragment.sql)
        assertEquals(listOf(SqlValue.Integer(30), SqlValue.Integer(40)), fragment.params)
    }

    @Test
    fun `isIn of nothing matches nothing`() {
        assertEquals("0 = 1", where { "age" isIn emptyList<String>() }.render().sql)
    }

    @Test
    fun `contains looks for an array element`() {
        val fragment = where { "tags" contains "kotlin" }.render()

        assertEquals("EXISTS (SELECT 1 FROM json_each(data, '$.tags') WHERE value = ?)", fragment.sql)
        assertEquals(listOf(SqlValue.Text("kotlin")), fragment.params)
    }

    @Test
    fun `inline rendering spells values out as literals`() {
        val condition = where { not("active".isTrue() or ("nickname" eq "o'neil")) }

        val fragment = condition.render(inline = true)

        assertEquals("NOT ((data ->> '$.active' = 1 OR data ->> '$.nickname' = 'o''neil'))", fragment.sql)
        assertEquals(emptyList(), fragment.params)
        assertEquals(fragment.sql, condition.toString())
    }

    @Test
    fun `conditions match stored documents`() = runTest {
        val db = Database { +Users }
        db.write {
            Users.insert(user("ann", 17, tags = listOf("kotlin", "sql"), role = Role.Admin))
            Users.insert(user("bob", 30, active = false, score = 2.5, nickname = "bobby"))
            Users.insert(user("cid", 45, last = "Jones", tags = listOf("kotlin")))
        }
        val expected = listOf(
            matching("bob", "cid") { "age" gte 18 },
            matching("ann") { "role" eq "Admin" },
            matching("bob", "cid") { "role" eq "member" },
            matching("bob") { "active".isFalse() },
            matching("ann", "cid") { "active".isTrue() },
            matching { "missing".isFalse() },
            matching("bob") { "score" gt 1.0 },
            matching("ann", "cid") { "nickname".isNull() },
                        matching("bob") { "nickname".isNotNull() },
            matching("ann", "cid") { "tags" contains "kotlin" },
            matching("cid") { ("tags" contains "kotlin") and ("age" gt 18) },
            matching("ann", "cid") { ("age" lt 18) or ("name.last" eq "Jones") },
            matching("bob") { not("tags" contains "kotlin") },
            matching("ann", "bob") { "email" isIn listOf("ann@example.com", "bob@example.com") },
            matching("cid") { "name.last" eq "Jones" },
            matching { "age" isIn emptyList<String>() },
        )

        for ((condition, ids) in expected) {
            assertEquals(ids, db.idsWhere(condition), condition.toString())
        }
    }

    private fun matching(vararg ids: String, block: WhereScope.() -> Where) = where(block) to ids.toList()

    private suspend fun Database.idsWhere(condition: Where): List<String> {
        val fragment = condition.render()
        return pool().readSql {
            statement("SELECT data ->> '$.id' FROM users WHERE ${fragment.sql} ORDER BY rowid") { statement ->
                statement.bind(fragment.params)
                buildList { while (statement.step()) add(statement.getText(0)) }
            }
        }
    }
}
