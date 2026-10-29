@file:OptIn(org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class)

package io.github.nailkhaf.nosqlite

import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import java.io.OutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The typed operators are overloads, so a property of any other type fails to compile. kotlin.test cannot assert
 * that, so these snippets compile against the library on the JVM.
 */
internal class TypedWhereCompileTest {

    @Test
    fun `sql scalar properties comparisons indexes and sorts compile`() {
        val result = compile(SourceFile.kotlin("Accepted.kt", accepted))

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    }

    @Test
    fun `properties without an sql scalar have no typed comparison`() {
        val files = rejected.map { (name, expression) -> name to SourceFile.kotlin(name, probe(expression)) }
        val result = compile(*files.map { it.second }.toTypedArray())

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        for ((name, _) in files) {
            assertTrue(name in result.messages, "Expected $name to fail\n${result.messages}")
        }
    }

    private fun compile(vararg sources: SourceFile): JvmCompilationResult =
        KotlinCompilation().apply {
            this.sources = sources.toList()
            inheritClassPath = true
            messageOutputStream = OutputStream.nullOutputStream()
        }.compile()

    private fun probe(expression: String): String = """
        import io.github.nailkhaf.nosqlite.*

        $models

        context(_: WhereScope)
        fun probe(): Where = $expression
    """.trimIndent()

    private val rejected = listOf(
        "RejectEnum.kt" to "Person::role eq Role.Member",
        "RejectEnumIn.kt" to "Person::role isIn listOf(Role.Member)",
        "RejectEnumContains.kt" to "Person::roles contains Role.Member",
        "RejectFloat.kt" to "Person::ratio eq 0.1f",
        "RejectFloatContains.kt" to "Person::ratios contains 0.1f",
        "RejectChar.kt" to "Person::initial eq 'a'",
        "RejectUnsigned.kt" to "Person::mask eq 1u",
        "RejectUnsignedLong.kt" to "Person::wide eq 1uL",
        "RejectShort.kt" to "Person::small eq 1.toShort()",
        "RejectValueClass.kt" to """Person::email eq Email("a@b.c")""",
        "RejectComparable.kt" to "Person::stamp eq Stamp(1)",
        "RejectWrongValue.kt" to "Person::age gte \"18\"",
        "RejectNoWidening.kt" to "Person::serial eq 1",
    )
}

private val models = """
    data class Name(val first: String, val last: String)
    enum class Role { Member }
    @JvmInline value class Email(val value: String)
    data class Stamp(val epoch: Long) : Comparable<Stamp> {
        override fun compareTo(other: Stamp): Int = epoch.compareTo(other.epoch)
    }
    data class Person(
        val id: String,
        val email: Email,
        val name: Name,
        val age: Int,
        val serial: Long,
        val score: Double,
        val active: Boolean,
        val nickname: String?,
        val small: Short,
        val tags: List<String>,
        val counts: List<Int>,
        val serials: List<Long>,
        val scores: List<Double>,
        val flags: List<Boolean>,
        val role: Role,
        val roles: List<Role>,
        val ratio: Float,
        val ratios: List<Float>,
        val initial: Char,
        val mask: UInt,
        val wide: ULong,
        val stamp: Stamp,
    )
""".trimIndent()

private val accepted = """
    import io.github.nailkhaf.nosqlite.*
    import io.github.nailkhaf.nosqlite.Collection

    $models

    context(_: WhereScope)
    @Suppress("unused")
    fun accepted(): List<Where> = listOf(
        Person::id eq "ada",
        Person::id neq "ada",
        Person::id gt "a",
        Person::id gte "a",
        Person::id lt "z",
        Person::id lte "z",
        Person::nickname eq "countess",
        Person::age gt 18,
        Person::age gte 18,
        Person::age lt 18,
        Person::age lte 18,
        Person::age isIn listOf(18, 36),
        Person::age isIn emptyList(),
        Person::serial eq 1L,
        Person::serial gt 0L,
        Person::serial gte 0L,
        Person::serial lt 2L,
        Person::serial lte 2L,
        Person::serial isIn listOf(1L),
        Person::serial isIn emptyList(),
        Person::score eq 0.1,
        Person::score gt 0.0,
        Person::score gte 0.0,
        Person::score lt 1.0,
        Person::score lte 1.0,
        Person::score isIn listOf(0.1),
        Person::score isIn emptyList(),
        Person::active eq true,
        Person::active neq false,
        Person::active gt false,
        Person::active gte false,
        Person::active lt true,
        Person::active lte true,
        Person::active isIn listOf(true, false),
        Person::active isIn emptyList(),
        Person::id isIn emptyList(),
        Person::tags contains "kotlin",
        Person::counts contains 1,
        Person::serials contains 1L,
        Person::scores contains 0.1,
        Person::flags contains true,
        (Person::name / Name::last) eq "Lovelace",
        (Person::name / Name::last) neq "Smith",
        (Person::name / Name::last) gt "A",
        (Person::name / Name::last) gte "A",
        (Person::name / Name::last) lt "Z",
        (Person::name / Name::last) lte "Z",
        (Person::name / Name::last) like "Lo%",
        (Person::name / Name::last) isIn listOf("Lovelace"),
        Person::nickname.isNull(),
        Person::nickname.isNotNull(),
        Person::role.isNull(),
        Person::ratio.isNotNull(),
        Person::email.isNull(),
    )

    context(_: OrderScope)
    @Suppress("unused")
    fun sorts() {
        Person::age.asc
        Person::role.desc
        Person::ratio.asc
        Person::email.desc
        (Person::name / Name::last).asc
    }

    @Suppress("unused")
    object People : Collection("people") {
        val byEmail = unique(Person::email)
        val byRole = index(Person::role)
        val byRatio = index(Person::ratio)
        val byName = index(Person::name / Name::last, Person::name / Name::first)
        val byNickname = unique(Person::nickname, where = { Person::active eq true })
    }
""".trimIndent()
