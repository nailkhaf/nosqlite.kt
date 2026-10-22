package io.github.nailkhaf.todo.data

import io.github.nailkhaf.nosqlite.Collection
import kotlinx.serialization.Serializable

@Serializable
data class Task(val id: String, val title: String, val done: Boolean = false)

internal object Tasks : Collection<Task>("tasks", Task.serializer()) {
    val byId = unique("id")
    val byDone = index("done")
}
