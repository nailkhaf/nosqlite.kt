package io.github.nailkhaf.nosqlite

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random

/** Runs [block] with a fresh database file in the temporary directory, deleted with its sidecars afterwards. */
internal suspend fun <R> withTempFile(maxReaders: Int = 3, block: suspend (Location.File) -> R): R {
    val name = "nosqlite-test-${Random.nextLong().toULong()}.db"
    val location = Location.File(Path(SystemTemporaryDirectory, name).toString(), maxReaders)
    try {
        return block(location)
    } finally {
        location.delete()
    }
}

internal fun Location.File.exists(): Boolean = SystemFileSystem.exists(Path(path))

/** The bundled driver, counting the connections it opens. */
internal class CountingDriver : SQLiteDriver {
    private val bundled = BundledSQLiteDriver()
    var opened: Int = 0
        private set

    override fun open(fileName: String): SQLiteConnection = bundled.open(fileName).also { opened++ }
}
