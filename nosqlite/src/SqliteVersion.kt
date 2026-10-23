package io.github.nailkhaf.nosqlite

import androidx.sqlite.SQLiteConnection

/** The oldest SQLite with `jsonb()` and `jsonb_patch()`. */
internal const val MinSqliteVersion: String = "3.45.0"

internal fun SQLiteConnection.version(): String =
    prepare("SELECT sqlite_version()").use { statement ->
        statement.step()
        statement.getText(0)
    }

internal fun requireSupported(version: String) {
    if (compareVersions(version, MinSqliteVersion) < 0) throw NoSqliteException.UnsupportedSqlite(version)
}

private fun compareVersions(a: String, b: String): Int {
    val left = a.split('.').map(String::toInt)
    val right = b.split('.').map(String::toInt)
    return left.zip(right).map { (l, r) -> l.compareTo(r) }.firstOrNull { it != 0 } ?: left.size.compareTo(right.size)
}
