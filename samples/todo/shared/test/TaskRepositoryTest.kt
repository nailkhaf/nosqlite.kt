package io.github.nailkhaf.todo.data

import io.github.nailkhaf.nosqlite.Location
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class TaskRepositoryTest {
    private val repository = TaskRepository(Location.InMemory)

    private suspend fun titles(): List<Pair<String, Boolean>> = repository.tasks.first().map { it.title to it.done }

    @Test
    fun `added tasks are listed in order`() = runTest {
        repository.add("milk")
        repository.add("bread")

        assertEquals(listOf("milk" to false, "bread" to false), titles())
    }

    @Test
    fun `done tasks move after open ones`() = runTest {
        repository.add("milk")
        repository.add("bread")

        repository.toggle(repository.tasks.first().first())

        assertEquals(listOf("bread" to false, "milk" to true), titles())
    }

    @Test
    fun `toggling twice reopens a task`() = runTest {
        repository.add("milk")
        repository.toggle(repository.tasks.first().single())

        repository.toggle(repository.tasks.first().single())

        assertEquals(listOf("milk" to false), titles())
    }

    @Test
    fun `delete removes one task and clearDone every done one`() = runTest {
        listOf("milk", "bread", "eggs", "tea").forEach { repository.add(it) }
        val (milk, bread, eggs) = repository.tasks.first()
        repository.toggle(milk)
        repository.toggle(bread)

        repository.delete(eggs)
        repository.clearDone()

        assertEquals(listOf("tea" to false), titles())
    }
}
