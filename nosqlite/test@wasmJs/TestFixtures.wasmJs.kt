package io.github.nailkhaf.nosqlite

import kotlin.random.Random

// The test browser starts with an empty OPFS, and the files can't be deleted on the web.
internal actual suspend fun <R> withTempFile(maxReaders: Int, block: suspend (Location.File) -> R): R =
    block(Location.File("nosqlite-test-${Random.nextLong().toULong()}.db", maxReaders))
