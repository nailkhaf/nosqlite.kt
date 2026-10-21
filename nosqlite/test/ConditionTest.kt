package io.github.nailkhaf.nosqlite

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Covers conditions: the SQL they render, how values are encoded, and what they match in stored documents. */
internal class ConditionTest {

    object Users : Collection("users")

    @Test
    fun `comparisons render with bound values`() {
        val condition = where { (User::age gte 18) and (User::name / Name::last eq "Smith") }

        val fragment = condition.render()

        assertEquals("(data ->> '$.age' >= ? AND data ->> '$.name.last' = ?)", fragment.sql)
        assertEquals(listOf(SqlValue.Integer(18), SqlValue.Text("Smith")), fragment.params)
    }

    @Test
    fun `values are encoded as they are stored`() {
        assertEquals(SqlValue.Text("member"), sqlValue(Role.Member)) // @SerialName
        assertEquals(SqlValue.Text("Admin"), sqlValue(Role.Admin))
        assertEquals(SqlValue.Text("ada@example.com"), sqlValue(Email("ada@example.com"))) // value class
        assertEquals(SqlValue.Integer(1), sqlValue(true)) // ->> gives booleans back as 0 and 1
        assertEquals(SqlValue.Real(1.5), sqlValue(1.5))
        assertEquals(SqlValue.Integer(Long.MAX_VALUE), sqlValue(Long.MAX_VALUE))
        assertEquals(SqlValue.Null, sqlValue<String?>(null))
    }

    @Test
    fun `structured values are rejected`() {
        val error = assertFailsWith<IllegalArgumentException> { where { User::name eq Name("Ada", "Lovelace") } }

        assertEquals(
            """Only scalars can be compared, not io.github.nailkhaf.nosqlite.Name: {"first":"Ada","last":"Lovelace"}""",
            error.message,
        )
    }

    @Test
    fun `null comparisons render as IS NULL`() {
        assertEquals("data ->> '$.nickname' IS NULL", where { User::nickname eq null }.render().sql)
        assertEquals("data ->> '$.nickname' IS NOT NULL", where { User::nickname neq null }.render().sql)
        assertEquals("data ->> '$.nickname' IS NULL", where { User::nickname.isNull() }.render().sql)
    }

    @Test
    fun `isIn binds one placeholder per value`() {
        val fragment = where { User::age isIn listOf(30, 40) }.render()

        assertEquals("data ->> '$.age' IN (?, ?)", fragment.sql)
        assertEquals(listOf(SqlValue.Integer(30), SqlValue.Integer(40)), fragment.params)
    }

    @Test
    fun `isIn of nothing matches nothing`() {
        assertEquals("0 = 1", where { User::age isIn emptyList() }.render().sql)
    }

    @Test
    fun `contains looks for an array element`() {
        val fragment = where { User::tags contains "kotlin" }.render()

        assertEquals("EXISTS (SELECT 1 FROM json_each(data, '$.tags') WHERE value = ?)", fragment.sql)
        assertEquals(listOf(SqlValue.Text("kotlin")), fragment.params)
    }

    @Test
    fun `inline rendering spells values out as literals`() {
        val condition = where { not((User::active eq true) or (User::nickname eq "o'neil")) }

        val fragment = condition.render(inline = true)

        assertEquals("NOT ((data ->> '$.active' = 1 OR data ->> '$.nickname' = 'o''neil'))", fragment.sql)
        assertEquals(emptyList(), fragment.params)
        assertEquals(fragment.sql, condition.toString())
    }

    @Test
    fun `conditions match stored documents`() = runTest {
        val db = Database { +Users }
        db.write {
            Users.insert(user("ann", 17, tags = listOf("kotlin", "sql"), role = Role.Admin).json())
            Users.insert(user("bob", 30, active = false, score = 2.5, nickname = "bobby").json())
            Users.insert(user("cid", 45, last = "Jones", tags = listOf("kotlin")).json())
        }
        val expected = listOf(
            matching("bob", "cid") { User::age gte 18 },
            matching("ann") { User::role eq Role.Admin },
            matching("bob", "cid") { User::role eq Role.Member },
            matching("bob") { User::active eq false },
            matching("bob") { User::score gt 1.0 },
            matching("ann", "cid") { User::nickname.isNull() },
            matching("bob") { User::nickname neq null },
            matching("ann", "cid") { User::tags contains "kotlin" },
            matching("cid") { (User::tags contains "kotlin") and (User::age gt 18) },
            matching("ann", "cid") { (User::age lt 18) or (User::name / Name::last eq "Jones") },
            matching("bob") { not(User::tags contains "kotlin") },
            matching("ann", "bob") { User::email isIn listOf(Email("ann@example.com"), Email("bob@example.com")) },
            matching("cid") { path<String>("name.last") eq "Jones" },
            matching { User::age isIn emptyList() },
        )

        for ((condition, ids) in expected) {
            assertEquals(ids, db.idsWhere(condition), condition.toString())
        }
    }

    private fun matching(vararg ids: String, block: WhereScope.() -> Condition) = where(block) to ids.toList()

    private suspend fun Database.idsWhere(condition: Condition): List<String> {
        val fragment = condition.render()
        return pool().readSql {
            prepare("SELECT data ->> '$.id' FROM users WHERE ${fragment.sql} ORDER BY rowid").use { statement ->
                statement.bind(fragment.params)
                buildList { while (statement.step()) add(statement.getText(0)) }
            }
        }
    }

    private fun User.json() = Database.Json.encodeToJsonElement(User.serializer(), this) as JsonObject
}
