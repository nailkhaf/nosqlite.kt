package io.github.nailkhaf.nosqlite

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CompletableDeferred
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

/** The bundled driver, whose statements can be held inside `step()` until a test lets them go. */
internal class GateDriver : SQLiteDriver {
    private val bundled = BundledSQLiteDriver()
    private val holding = atomic(false)
    private val released = atomic(false)

    /** Completes when a statement is held inside `step()`. */
    val entered = CompletableDeferred<Unit>()

    /** Holds the next `step()` on the thread that calls it until [release]. */
    fun hold() {
        holding.value = true
    }

    fun release() {
        released.value = true
    }

    override fun open(fileName: String): SQLiteConnection = GatedConnection(bundled.open(fileName))

    private inner class GatedConnection(private val connection: SQLiteConnection) : SQLiteConnection by connection {
        override fun prepare(sql: String): SQLiteStatement = GatedStatement(connection.prepare(sql))
    }

    private inner class GatedStatement(private val statement: SQLiteStatement) : SQLiteStatement by statement {
        override fun step(): Boolean {
            if (holding.compareAndSet(expect = true, update = false)) {
                entered.complete(Unit)
                while (!released.value) Unit
            }
            return statement.step()
        }
    }
}
