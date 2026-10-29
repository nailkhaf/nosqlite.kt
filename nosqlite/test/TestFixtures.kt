package io.github.nailkhaf.nosqlite

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteException
import androidx.sqlite.SQLiteStatement
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

/**
 * The bundled driver, whose connections fail to run [failingSql] the first [times] times
 * and remember whether they were closed.
 */
internal class FailingDriver(private val failingSql: String, private val times: Int = Int.MAX_VALUE) : SQLiteDriver {
    private val bundled = BundledSQLiteDriver()
    val connections = mutableListOf<FailingConnection>()

    override fun open(fileName: String): SQLiteConnection =
        FailingConnection(bundled.open(fileName), failingSql, times).also { connections += it }
}

internal class FailingConnection(
    private val sqlite: SQLiteConnection,
    private val failingSql: String,
    private val times: Int,
) : SQLiteConnection by sqlite {
    var closed: Boolean = false
        private set
    private var failed = 0

    override fun prepare(sql: String): SQLiteStatement {
        if (sql == failingSql && failed < times) {
            failed += 1
            throw SQLiteException("$sql failed")
        }
        return sqlite.prepare(sql)
    }

    override fun close() {
        closed = true
        sqlite.close()
    }
}

/** The bundled driver, running [after] each time a statement for [sql] has stepped. */
internal class SteppingDriver(private val sql: String, private val after: () -> Unit) : SQLiteDriver {
    private val bundled = BundledSQLiteDriver()

    override fun open(fileName: String): SQLiteConnection = Stepping(bundled.open(fileName))

    private inner class Stepping(private val connection: SQLiteConnection) : SQLiteConnection by connection {
        override fun prepare(sql: String): SQLiteStatement {
            val statement = connection.prepare(sql)
            if (sql != this@SteppingDriver.sql) return statement
            return object : SQLiteStatement by statement {
                override fun step(): Boolean = statement.step().also { after() }
            }
        }
    }
}
