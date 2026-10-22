package io.github.nailkhaf.nosqlite

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile

/** Like [lazy] for a suspending [initializer]: it runs once, on the first [SuspendLazy.get]; a failure is retried. */
internal fun <T : Any> suspendLazy(initializer: suspend () -> T): SuspendLazy<T> = SuspendLazy(initializer)

internal class SuspendLazy<T : Any>(initializer: suspend () -> T) {
    private val lock = Mutex()

    // Dropped once it has run, so whatever it captured can be collected.
    private var initializer: (suspend () -> T)? = initializer

    @Volatile
    private var value: T? = null

    suspend fun get(): T = value ?: lock.withLock {
        value ?: checkNotNull(initializer)().also {
            value = it
            initializer = null
        }
    }
}
