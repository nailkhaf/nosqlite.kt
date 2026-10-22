package io.github.nailkhaf.nosqlite

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Covers property references: the same conditions, sort keys and indexes as their field names. */
internal class FieldTest {

    object Users : Collection<User>("users", User.serializer()) {
        val byEmail = unique(User::email)
        val byName = index(User::name / Name::last, User::name / Name::first)
        val activeNickname = unique(User::nickname, where = { User::active eq true })
    }

    @Serializable
    data class Renamed(@SerialName("full_name") val name: String, val owner: Owner)

    @Serializable
    data class Owner(val first: String, @SerialName("family") val last: String)

    private fun sql(block: WhereScope.() -> Where): Fragment = where(block).render()

    @Test
    fun `properties render as their field names`() {
        assertEquals(sql { "age" gte 18 }, sql { User::age gte 18 })
        assertEquals(sql { "age" gt 18 }, sql { User::age gt 18 })
        assertEquals(sql { "age" lt 18 }, sql { User::age lt 18 })
        assertEquals(sql { "age" lte 18 }, sql { User::age lte 18 })
        assertEquals(sql { "id" eq "ada" }, sql { User::id eq "ada" })
        assertEquals(sql { "id" neq "ada" }, sql { User::id neq "ada" })
        assertEquals(sql { "score" eq 1.5 }, sql { User::score eq 1.5 })
        assertEquals(sql { "active".isTrue() }, sql { User::active eq true })
        assertEquals(sql { "age" isIn listOf(30, 40) }, sql { User::age isIn listOf(30, 40) })
        assertEquals(sql { "tags" contains "kotlin" }, sql { User::tags contains "kotlin" })
        assertEquals(sql { "nickname" like "bo%" }, sql { User::nickname like "bo%" })
        assertEquals(sql { "name.last" like "Lo%" }, sql { (User::name / Name::last) like "Lo%" })
        assertEquals(sql { "nickname".isNull() }, sql { User::nickname.isNull() })
        assertEquals(sql { "nickname".isNotNull() }, sql { User::nickname.isNotNull() })
    }

    @Test
    fun `nested properties join with a slash`() {
        assertEquals("name.last", (User::name / Name::last).toString())
        assertEquals(sql { "name.last" eq "Smith" }, sql { (User::name / Name::last) eq "Smith" })
        assertEquals(sql { "name.last".isNull() }, sql { (User::name / Name::last).isNull() })
    }

    @Test
    fun `properties sort as their field names`() {
        val query = query { orderBy { User::age.desc; (User::name / Name::last).asc } }

        assertEquals("[age desc, name.last asc]", query.orderBy.toString())
    }

    @Test
    fun `property indexes are the same as field name indexes`() {
        // Read through the base type: Kotlin/Native fails to link an internal member read through a test object.
        val byStrings: Collection<User> = object : Collection<User>("users", User.serializer()) {
            val byEmail = unique("email")
            val byName = index("name.last", "name.first")
            val activeNickname = unique("nickname", where = { "active".isTrue() })
        }

        val byProperties: Collection<User> = Users

        assertEquals(byStrings.indexes.map { it.name }, byProperties.indexes.map { it.name })
    }

    @Test
    fun `a property renamed with SerialName can't be indexed by reference`() {
        val error = assertFailsWith<IllegalArgumentException> {
            object : Collection<Renamed>("renamed", Renamed.serializer()) {
                val byName = index(Renamed::name)
            }
        }

        assertEquals(
            "'name' is not a serial field of 'renamed': name a field renamed with @SerialName by its serial name",
            error.message,
        )
    }

    @Test
    fun `nested property indexes check every key`() {
        val error = assertFailsWith<IllegalArgumentException> {
            object : Collection<Renamed>("renamed", Renamed.serializer()) {
                val byFirst = index(Renamed::owner / Owner::first)
                val byLast = index(Renamed::owner / Owner::last)
            }
        }

        assertEquals(
            "'owner.last' is not a serial field of 'renamed': name a field renamed with @SerialName by its serial name",
            error.message,
        )
    }

    @Test
    fun `property queries find the documents`() = runTest {
        val db = Database { +Users }
        val ada = user("ada", 36, last = "Lovelace", tags = listOf("math"))
        val alan = user("alan", 41, last = "Turing", tags = listOf("math", "kotlin"))
        db.write {
            Users.insert(ada)
            Users.insert(alan)
            Users.insert(user("kid", 10))
        }

        val found = db.read {
            Users.list {
                where { (User::age gte 18) and (User::tags contains "math") }
                orderBy { (User::name / Name::last).desc }
            }
        }

        assertEquals(listOf(alan, ada), found)
    }
}
