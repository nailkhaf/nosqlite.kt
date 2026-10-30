package io.github.nailkhaf.nosqlite

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Covers property references: the same conditions, sort keys and indexes as their field names. */
internal class FieldTest {

    object Users : Collection("users") {
        val byEmail = unique(User::email)
        val byName = index(User::name / Name::last, User::name / Name::first)
        val activeNickname = unique(User::nickname, where = { User::active eq true })
    }

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
        assertEquals(sql { "score" gt 0.1 }, sql { User::score gt 0.1 })
        assertEquals(sql { "active".isTrue() }, sql { User::active eq true })
        assertEquals(sql { "active".isFalse() }, sql { User::active eq false })
        assertEquals(sql { "active" neq 0 }, sql { User::active neq false })
        assertEquals(sql { "active" gt 0 }, sql { User::active gt false })
        assertEquals(sql { "age" isIn listOf<Int>(30, 40) }, sql { User::age isIn listOf(30, 40) })
        assertEquals(sql { "age" isIn emptyList<Int>() }, sql { User::age isIn emptyList() })
        assertEquals(sql { "id" isIn emptyList<String>() }, sql { User::id isIn emptyList() })
        assertEquals(sql { "tags" contains "kotlin" }, sql { User::tags contains "kotlin" })
        assertEquals(sql { "nickname" eq "bobby" }, sql { User::nickname eq "bobby" })
        assertEquals(sql { "nickname" like "bo%" }, sql { User::nickname like "bo%" })
        assertEquals(sql { "name.last" like "Lo%" }, sql { (User::name / Name::last) like "Lo%" })
        assertEquals(sql { "nickname".isNull() }, sql { User::nickname.isNull() })
        assertEquals(sql { "nickname".isNotNull() }, sql { User::nickname.isNotNull() })
        assertEquals(sql { "role".isNull() }, sql { User::role.isNull() })
        assertEquals(sql { "serial" eq Long.MAX_VALUE }, sql { Measure::serial eq Long.MAX_VALUE })
        assertEquals(sql { "serial" lte 1L }, sql { Measure::serial lte 1L })
        assertEquals(
            sql { "serial" isIn listOf(1L, Long.MAX_VALUE) },
            sql { Measure::serial isIn listOf(1L, Long.MAX_VALUE) },
        )
        assertEquals(sql { "serial" isIn emptyList<Long>() }, sql { Measure::serial isIn emptyList() })
        assertEquals(sql { "score" isIn listOf(0.1, 2.5) }, sql { Measure::score isIn listOf(0.1, 2.5) })
        assertEquals(sql { "score" isIn emptyList<Double>() }, sql { Measure::score isIn emptyList() })
        assertEquals(sql { "on" isIn listOf<Int>(1, 0) }, sql { Measure::on isIn listOf(true, false) })
        assertEquals(sql { "on" isIn emptyList<Int>() }, sql { Measure::on isIn emptyList() })
        assertEquals(sql { "counts" contains 2 }, sql { Measure::counts contains 2 })
        assertEquals(
            sql { "serials" contains Long.MAX_VALUE },
            sql { Measure::serials contains Long.MAX_VALUE },
        )
        assertEquals(sql { "scores" contains 0.1 }, sql { Measure::scores contains 0.1 })
        assertEquals(sql { "flags" contains 1 }, sql { Measure::flags contains true })
        assertEquals(sql { "flags" contains 0 }, sql { Measure::flags contains false })
    }

    @Test
    fun `nested properties join with a slash`() {
        assertEquals("name.last", (User::name / Name::last).toString())
        assertEquals(sql { "name.last" eq "Smith" }, sql { (User::name / Name::last) eq "Smith" })
        assertEquals(sql { "name.last".isNull() }, sql { (User::name / Name::last).isNull() })
    }

    @Test
    fun `properties sort as their field names`() {
        val query = query { orderBy { User::age.desc; User::role.asc; (User::name / Name::last).asc } }

        assertEquals("[age desc, role asc, name.last asc]", query.orderBy.toString())
    }

    @Test
    fun `property indexes are the same as field name indexes`() {
        // Read through the base type: Kotlin/Native fails to link an internal member read through a test object.
        val byStrings: Collection = object : Collection("users") {
            val byEmail = unique("email")
            val byName = index("name.last", "name.first")
            val activeNickname = unique("nickname", where = { "active".isTrue() })
        }

        val byProperties: Collection = Users

        assertEquals(byStrings.indexes.map { it.name }, byProperties.indexes.map { it.name })
    }

    @Test
    fun `property queries find the documents`() = runTest {
        val db = NoSqlite { +Users }
        val ada = user("ada", 36, last = "Lovelace", tags = listOf("math"))
        val alan = user("alan", 41, last = "Turing", tags = listOf("math", "kotlin"))
        db.write {
            Users.insert(ada)
            Users.insert(alan)
            Users.insert(user("kid", 10))
        }

        val found = db.read {
            Users.list<User> {
                where { (User::age gte 18) and (User::tags contains "math") }
                orderBy { (User::name / Name::last).desc }
            }
        }

        assertEquals(listOf(alan, ada), found)
    }

    @Test
    fun `each SQL scalar matches the value it is stored as`() = runTest {
        val db = NoSqlite { +Measures }
        val tenth = measure(label = "a", score = 0.1, on = true, note = "kept", counts = listOf(2, 3))
        val other = measure(label = "b", count = 9, serial = 1, score = 2.5, on = false)
        db.write {
            Measures.insert(tenth)
            Measures.insert(other)
        }

        val found = db.read {
            Measures.list<Measure> {
                where {
                    (Measure::label eq "a") and
                        (Measure::label neq "b") and
                        (Measure::count gte 2) and
                        (Measure::count gt 1) and
                        (Measure::count lte 2) and
                        (Measure::count lt 3) and
                        (Measure::serial eq Long.MAX_VALUE) and
                        (Measure::score eq 0.1) and
                        (Measure::score gt 0.0) and
                        (Measure::on eq true) and
                        (Measure::on gt false) and
                        (Measure::note eq "kept") and
                        (Measure::counts contains 2) and
                        (Measure::serials contains Long.MAX_VALUE) and
                        (Measure::scores contains 0.1) and
                        (Measure::flags contains true) and
                        (Measure::labels contains "a") and
                        (Measure::count isIn listOf(2, 4)) and
                        (Measure::serial isIn listOf(Long.MAX_VALUE)) and
                        (Measure::score isIn listOf(0.1, 0.2)) and
                        (Measure::on isIn listOf(true)) and
                        (Measure::label isIn listOf("a", "z")) and
                        (Measure::label like "a%")
                }
            }
        }
        val missing = db.read { Measures.list<Measure> { where { Measure::note.isNull() } } }
        val nan = assertFailsWith<IllegalArgumentException> { where { Measure::score eq Double.NaN } }

        assertEquals(listOf(tenth), found)
        assertEquals(listOf(other), missing)
        assertEquals("Unsupported number: NaN, SQLite has no NaN or infinity", nan.message)
    }
}

@Serializable
internal data class Measure(
    val label: String,
    val count: Int,
    val serial: Long,
    val score: Double,
    val on: Boolean,
    val note: String? = null,
    val counts: List<Int> = emptyList(),
    val serials: List<Long> = emptyList(),
    val scores: List<Double> = emptyList(),
    val flags: List<Boolean> = emptyList(),
    val labels: List<String> = emptyList(),
)

internal object Measures : Collection("measures")

private fun measure(
    label: String,
    count: Int = 2,
    serial: Long = Long.MAX_VALUE,
    score: Double = 0.1,
    on: Boolean = true,
    note: String? = null,
    counts: List<Int> = listOf(2),
    serials: List<Long> = listOf(serial),
    scores: List<Double> = listOf(score),
    flags: List<Boolean> = listOf(on),
    labels: List<String> = listOf(label),
): Measure = Measure(label, count, serial, score, on, note, counts, serials, scores, flags, labels)
