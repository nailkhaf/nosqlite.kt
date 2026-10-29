package io.github.nailkhaf.nosqlite

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Covers the SQLite version check: JSONB storage needs 3.45 or newer. */
internal class SqliteVersionTest {

    @Test
    fun `the bundled SQLite is supported`() = runTest {
        val sqlite = BundledSQLiteDriver().open(":memory:")

        requireSupported(sqlite.version())

        sqlite.close()
    }

    @Test
    fun `versions from the minimum on are supported`() {
        for (version in listOf("3.45.0", "3.45.1", "3.50.1", "3.100.0", "4.0.0")) requireSupported(version)
    }

    @Test
    fun `older versions are rejected`() {
        for (version in listOf("3.44.3", "3.38.5", "2.99.99")) {
            val error = assertFailsWith<NoSqliteException.UnsupportedSqlite> { requireSupported(version) }

            assertEquals(version, error.version)
            assertEquals("SQLite $version is older than 3.45.0; use BundledSQLiteDriver", error.message)
        }
    }
}
