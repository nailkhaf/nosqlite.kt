package io.github.nailkhaf.nosqlite

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Covers conditions: the SQL they render, how values are encoded, and what they match in stored documents. */
internal class WhereTest {

    object Users : Collection("users")

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
    fun `NaN and infinite numbers are rejected`() {
        val nan = assertFailsWith<IllegalArgumentException> { where { "score" eq Double.NaN } }
        assertFailsWith<IllegalArgumentException> { where { "score" gt Double.POSITIVE_INFINITY } }
        assertFailsWith<IllegalArgumentException> { where { "score" lt Float.NEGATIVE_INFINITY } }
        assertFailsWith<IllegalArgumentException> { where { "score" isIn listOf(1.0, Float.NaN) } }

        assertEquals("Unsupported number: NaN, SQLite has no NaN or infinity", nan.message)
    }

    @Test
    fun `null checks render as IS NULL`() {
        assertEquals("data ->> '$.nickname' IS NULL", where { "nickname".isNull() }.render().sql)
        assertEquals("data ->> '$.nickname' IS NOT NULL", where { "nickname".isNotNull() }.render().sql)
    }

    @Test
    fun `like binds its pattern`() {
        val fragment = where { "name.last" like "Lo%" }.render()

        assertEquals("data ->> '$.name.last' LIKE ?", fragment.sql)
        assertEquals(listOf(SqlValue.Text("Lo%")), fragment.params)
    }

    @Test
    fun `isIn binds its values as one JSON array`() {
        val fragment = where { "age" isIn listOf(30, 40.5) }.render()

        assertEquals("data ->> '$.age' IN (SELECT value FROM json_each(?))", fragment.sql)
        assertEquals(listOf(SqlValue.Text("[30,40.5]")), fragment.params)
    }

    @Test
    fun `isIn spells out its values inline`() {
        assertEquals("data ->> '$.id' IN ('ann', 'o''brien')", where { "id" isIn listOf("ann", "o'brien") }.toString())
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
        val db = NoSqlite { +Users }
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
            matching("ann", "bob", "cid") { "email" like "%@EXAMPLE.com" },
            matching("cid") { "name.last" like "jo%" },
            matching("bob") { "id" like "b_b" },
            matching { "id" like "b_" },
            matching { "age" isIn emptyList<String>() },
            matching("ann", "cid") { "age" isIn listOf(17, 45) },
            matching("bob") { "score" isIn listOf(2.5, 3) },
        )

        for ((condition, ids) in expected) {
            assertEquals(ids, db.idsWhere(condition), condition.toString())
        }
    }

    @Test
    fun `isIn takes more values than SQLite takes parameters`() = runTest {
        val db = NoSqlite { +Users }
        db.write {
            Users.insert(user("ann", 17))
            Users.insert(user("bob", 40_000))
        }

        val ids = db.idsWhere(where { "age" isIn (17..50_000).filter { it != 40_000 } })

        assertEquals(listOf("ann"), ids)
    }

    private fun matching(vararg ids: String, block: WhereScope.() -> Where) = where(block) to ids.toList()

    private suspend fun NoSqlite.idsWhere(condition: Where): List<String> {
        val fragment = condition.render()
        return pool().readSql {
            statement("SELECT data ->> '$.id' FROM users WHERE ${fragment.sql} ORDER BY rowid") { statement ->
                statement.bind(fragment.params)
                buildList { while (statement.step()) add(statement.getText(0)) }
            }
        }
    }
}
