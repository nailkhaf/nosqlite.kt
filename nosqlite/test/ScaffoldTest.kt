package io.github.nailkhaf.nosqlite

import kotlin.test.Test
import kotlin.test.assertTrue

/** Checks that the module builds and its tests run on every platform. */
internal class ScaffoldTest {

    @Test
    fun `version is set`() {
        assertTrue(NOSQLITE_VERSION.isNotBlank())
    }
}
