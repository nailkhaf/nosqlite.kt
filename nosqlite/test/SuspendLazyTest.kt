package io.github.nailkhaf.nosqlite

import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

/** Covers [SuspendLazy]: one initialization shared by every caller, retried after a failure. */
internal class SuspendLazyTest {

    @Test
    fun `concurrent callers share one initialization`() = runTest {
        var runs = 0
        val lazy = suspendLazy { ++runs }

        val values = List(10) { async { lazy.get() } }.awaitAll()

        assertEquals(List(10) { 1 }, values)
        assertEquals(1, runs)
    }

    @Test
    fun `a failed initialization runs again on the next call`() = runTest {
        var runs = 0
        val lazy = suspendLazy { if (++runs == 1) error("first run fails") else runs }

        assertFails { lazy.get() }

        assertEquals(2, lazy.get())
    }
}
