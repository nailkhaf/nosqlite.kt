package io.github.nailkhaf.todo.data

import io.github.nailkhaf.nosqlite.Collection
import io.github.nailkhaf.nosqlite.asc
import io.github.nailkhaf.nosqlite.desc
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
data class Task(val id: Uuid, val title: String, val done: Boolean = false)

internal object Tasks : Collection("tasks") {
    val byDone = index {
        Task::done.asc
        Task::id.desc
    }
}
