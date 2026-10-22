package io.github.nailkhaf.nosqlite

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Covers field paths: how they are parsed, and rendered to SQL. */
internal class PathTest {

    @Test
    fun `fields split into keys on dots`() {
        assertEquals(listOf("age"), Path("age").names)
        assertEquals(listOf("name", "last"), Path("name.last").names)
    }

    @Test
    fun `paths render as json extraction`() {
        assertEquals("data ->> '$.age'", Path("age").sql)
        assertEquals("data ->> '$.name.last'", Path("name.last").sql)
    }

    @Test
    fun `invalid fields are rejected`() {
        val error = assertFailsWith<IllegalArgumentException> { Path("a..b") }

        assertEquals("Invalid field 'a..b': keys must be identifiers", error.message)
        for (invalid in listOf("", "first name", "it's", "a\"b", "1st")) {
            assertFailsWith<IllegalArgumentException>(invalid) { Path(invalid) }
        }
    }
}
