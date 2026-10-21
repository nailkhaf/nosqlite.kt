package io.github.nailkhaf.nosqlite

import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Covers typed paths: how they are built, rendered to SQL and checked against serial names. */
internal class PathTest {

    @Test
    fun `property paths use the kotlin property names`() {
        assertEquals(listOf("age"), path(User::age).names)
        assertEquals(listOf("name", "last"), (User::name / Name::last).names)
        assertEquals(listOf("manager", "first"), (User::manager / Name::first).names)
    }

    @Test
    fun `serial paths split on dots`() {
        val path = path<String>("inner.zip_code")

        assertEquals(listOf("inner", "zip_code"), path.names)
        assertEquals("inner.zip_code", path.toString())
    }

    @Test
    fun `paths render as json extraction`() {
        val rendered = mapOf(
            path(User::age) to "data ->> '$.age'",
            User::name / Name::last to "data ->> '$.name.last'",
            path<String>("first name.it's") to "data ->> '$.\"first name\".\"it''s\"'",
        )

        for ((path, sql) in rendered) assertEquals(sql, path.sql)
    }

    @Test
    fun `paths with the same keys are equal`() {
        assertEquals(path<Int>("age"), path(User::age))
        assertEquals(path<String>("name.last").hashCode(), (User::name / Name::last).hashCode())
    }

    @Test
    fun `invalid segments are rejected`() {
        val error = assertFailsWith<IllegalArgumentException> { path<Int>("a..b") }

        assertEquals("Invalid path segment: '' in a..b", error.message)
        assertFailsWith<IllegalArgumentException> { path<Int>("a\"b") }
    }

    @Test
    fun `paths that follow the serial names pass the check`() {
        val descriptor = serializer<User>().descriptor

        for (path in listOf(path(User::age), User::name / Name::last, User::manager / Name::first, path(User::email))) {
            checkPath(path, descriptor)
        }
    }

    @Test
    fun `renamed property fails the check with a pointer to serial paths`() {
        val error = assertFailsWith<IllegalArgumentException> {
            checkPath(path(Renamed::fullName), serializer<Renamed>().descriptor)
        }

        assertEquals(
            "Path 'fullName' doesn't match the serial names of Renamed: Renamed has no element 'fullName'" +
                " (it has full_name, inner). For a property renamed with @SerialName use path<V>(\"serial.name\").",
            error.message,
        )
    }

    @Test
    fun `renamed nested property fails the check naming its parent`() {
        val error = assertFailsWith<IllegalArgumentException> {
            checkPath(Renamed::inner / Renamed.Inner::zip, serializer<Renamed>().descriptor)
        }

        assertEquals(
            "Path 'inner.zip' doesn't match the serial names of Renamed: Inner (at 'inner') has no element 'zip'" +
                " (it has zip_code). For a property renamed with @SerialName use path<V>(\"serial.name\").",
            error.message,
        )
    }

    @Test
    fun `serial paths are trusted as written`() {
        val descriptor = serializer<Renamed>().descriptor

        checkPath(path<String>("full_name"), descriptor)
        checkPath(path<String>("not.in.the.class"), descriptor)
    }
}
