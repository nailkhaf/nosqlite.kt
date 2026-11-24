package io.github.nailkhaf.nosqlite

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.uuid.Uuid

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
        assertEquals(SqlValue.Integer(1), param { "active" eq true })
        assertEquals(SqlValue.Integer(0), param { "active" neq false })
        assertEquals(SqlValue.Integer(1), param { "flags" contains true })
        assertEquals(SqlValue.Text(uuid("ada").toString()), param { "id" eq uuid("ada") })
    }

    @Test
    fun `integer literals and Int values bind as integers`() {
        fun bound(block: WhereScope.() -> Where): SqlValue = where(block).render().params.single()
        val age = 18

        assertEquals(SqlValue.Integer(18), bound { "age" eq 18 })
        assertEquals(SqlValue.Integer(-2), bound { "age" neq -2 })
        assertEquals(SqlValue.Integer(1), bound { "age" gt 1 })
        assertEquals(SqlValue.Integer(2), bound { "age" gte 2 })
        assertEquals(SqlValue.Integer(0), bound { "age" lt 0 })
        assertEquals(SqlValue.Integer(7), bound { "age" lte 7 })
        assertEquals(SqlValue.Integer(4), bound { "counts" contains 4 })
        assertEquals(SqlValue.Integer(18), bound { "age" eq age })
        assertEquals(SqlValue.Integer(18), bound { "counts" contains age })
        assertEquals(SqlValue.Integer(18), bound { "age" eq age.toLong() })
        assertEquals(
            listOf(SqlValue.Text("[1,2]")),
            where { "age" isIn listOf(1, 2) }.render().params,
        )
        assertEquals(
            listOf(SqlValue.Text("[1,2]")),
            where { "age" isIn listOf(1L, 2L) }.render().params,
        )
    }

    @Test
    fun `long double boolean and uuid overloads bind as SQL scalars`() {
        fun bound(block: WhereScope.() -> Where): SqlValue = where(block).render().params.single()
        val ada = uuid("ada")
        val alan = uuid("alan")

        assertEquals(SqlValue.Integer(Int.MIN_VALUE.toLong()), bound { "age" gt Int.MIN_VALUE.toLong() })
        assertEquals(SqlValue.Integer(Int.MAX_VALUE.toLong()), bound { "age" gte Int.MAX_VALUE.toLong() })
        assertEquals(SqlValue.Integer(18L), bound { "id" eq 18L })
        assertEquals(SqlValue.Integer(-2L), bound { "id" neq -2L })
        assertEquals(SqlValue.Integer(Long.MIN_VALUE), bound { "id" gt Long.MIN_VALUE })
        assertEquals(SqlValue.Integer(Long.MAX_VALUE), bound { "id" gte Long.MAX_VALUE })
        assertEquals(SqlValue.Integer(0L), bound { "id" lt 0L })
        assertEquals(SqlValue.Integer(7L), bound { "id" lte 7L })
        assertEquals(SqlValue.Integer(4L), bound { "ids" contains 4L })

        assertEquals(SqlValue.Real(1.5), bound { "score" eq 1.5 })
        assertEquals(SqlValue.Real(-0.5), bound { "score" neq -0.5 })
        assertEquals(SqlValue.Real(0.1), bound { "score" gt 0.1 })
        assertEquals(SqlValue.Real(0.0), bound { "score" gte 0.0 })
        assertEquals(SqlValue.Real(-1.0), bound { "score" lt -1.0 })
        assertEquals(SqlValue.Real(2.5), bound { "score" lte 2.5 })
        assertEquals(SqlValue.Real(0.1), bound { "scores" contains 0.1 })

        assertEquals(SqlValue.Integer(1), bound { "active" eq true })
        assertEquals(SqlValue.Integer(0), bound { "active" eq false })
        assertEquals(SqlValue.Integer(1), bound { "active" neq true })
        assertEquals(SqlValue.Text("[1,0]"), bound { "active" isIn listOf(true, false) })
        assertEquals(SqlValue.Integer(0), bound { "flags" contains false })

        assertEquals(SqlValue.Text(ada.toString()), bound { "id" eq ada })
        assertEquals(SqlValue.Text(ada.toString()), bound { "id" neq ada })
        assertEquals(SqlValue.Text(ada.toString()), bound { "id" gt ada })
        assertEquals(SqlValue.Text(ada.toString()), bound { "id" gte ada })
        assertEquals(SqlValue.Text(ada.toString()), bound { "id" lt ada })
        assertEquals(SqlValue.Text(ada.toString()), bound { "id" lte ada })
        assertEquals(SqlValue.Text(ada.toString()), bound { "refs" contains ada })
        assertEquals(
            SqlValue.Text("""["$ada","$alan"]"""),
            bound { "id" isIn listOf(ada, alan) },
        )
    }

    @Test
    fun `NaN and infinite numbers are rejected`() {
        val nan = assertFailsWith<IllegalArgumentException> { where { "score" eq Double.NaN } }
        assertFailsWith<IllegalArgumentException> { where { "score" gt Double.POSITIVE_INFINITY } }
        val infinite = assertFailsWith<IllegalArgumentException> { where { "score" lt Double.NEGATIVE_INFINITY } }
        assertFailsWith<IllegalArgumentException> { where { "score" isIn listOf(1.0, Double.NaN) } }
        assertFailsWith<IllegalArgumentException> { where { "scores" contains Double.POSITIVE_INFINITY } }

        assertEquals("Unsupported number: NaN, SQLite has no NaN or infinity", nan.message)
        assertEquals("Unsupported number: -Infinity, SQLite has no NaN or infinity", infinite.message)
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
        val literals = where { "age" isIn listOf(1, 2) }.render()
        val longs = where { "id" isIn listOf(Long.MIN_VALUE, Long.MAX_VALUE) }.render()
        val doubles = where { "score" isIn listOf(30.0, 40.5) }.render()

        assertEquals("data ->> '$.age' IN (SELECT value FROM json_each(?))", literals.sql)
        assertEquals("data ->> '$.id' IN (SELECT value FROM json_each(?))", longs.sql)
        assertEquals("data ->> '$.score' IN (SELECT value FROM json_each(?))", doubles.sql)
        assertEquals(listOf(SqlValue.Text("[1,2]")), literals.params)
        assertEquals(listOf(SqlValue.Text("[${Long.MIN_VALUE},${Long.MAX_VALUE}]")), longs.params)
        assertEquals(listOf(SqlValue.Text("[30.0,40.5]")), doubles.params)
        assertEquals("data ->> '$.age' IN (1, 2)", where { "age" isIn listOf(1, 2) }.toString())
    }

    @Test
    fun `isIn spells out its values inline`() {
        assertEquals("data ->> '$.id' IN ('ann', 'o''brien')", where { "id" isIn listOf("ann", "o'brien") }.toString())
    }

    @Test
    fun `isIn of nothing matches nothing`() {
        assertEquals("0 = 1", where { "age" isIn emptyList<String>() }.render().sql)
        assertEquals("0 = 1", where { "id" isIn emptyList<Long>() }.render().sql)
        assertEquals("0 = 1", where { "score" isIn emptyList<Double>() }.render().sql)
        assertEquals("0 = 1", where { "active" isIn emptyList<Boolean>() }.render().sql)
        assertEquals("0 = 1", where { "id" isIn emptyList<Uuid>() }.render().sql)
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
            matching("bob") { "active" eq false },
            matching("ann", "cid") { "active" eq true },
            matching("bob") { "active" neq true },
            matching("ann", "cid") { "active" isIn listOf(true) },
            matching("bob") { "active" isIn listOf(false) },
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
            matching("bob") { "email" like "b_b@example.com" },
            matching { "email" like "b_" },
            matching { "age" isIn emptyList<String>() },
            matching("ann", "cid") { "age" isIn listOf(17, 45) },
            matching("bob") { "score" isIn listOf(2.5, 3.0) },
            matching("ann") { "id" eq uuid("ann") },
            matching("ann", "bob") { "id" gt uuid("cid") },
            matching("bob", "cid") { "id" isIn listOf(uuid("bob"), uuid("cid")) },
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

        val ids = db.idsWhere(where { "age" isIn (17L..50_000L).filter { it != 40_000L } })

        assertEquals(listOf("ann"), ids)
    }

    private fun matching(vararg ids: String, block: WhereScope.() -> Where) = where(block) to ids.toList()

    private suspend fun NoSqlite.idsWhere(condition: Where): List<String> {
        val fragment = condition.render()
        return pool().readSql {
            statement(
                "SELECT substr(data ->> '$.email', 1, instr(data ->> '$.email', '@') - 1) " +
                    "FROM nsq_users WHERE ${fragment.sql} ORDER BY rowid",
            ) { statement ->
                statement.bind(fragment.params)
                buildList { while (statement.step()) add(statement.getText(0)) }
            }
        }
    }
}
