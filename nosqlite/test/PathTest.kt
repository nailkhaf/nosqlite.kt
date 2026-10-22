package io.github.nailkhaf.nosqlite

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Covers typed paths: how they are built, and rendered to SQL. */
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
}
