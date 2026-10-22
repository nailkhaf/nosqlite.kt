package io.github.nailkhaf.todo

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import io.github.nailkhaf.nosqlite.Location
import io.github.nailkhaf.todo.data.TaskRepository
import io.github.nailkhaf.todo.ui.App
import java.io.File

fun main() {
    val dir = File(System.getProperty("user.home"), ".nosqlite-todo").apply { mkdirs() }
    val repository = TaskRepository(Location.File(dir.resolve("todo.db").path))
    application {
        Window(onCloseRequest = ::exitApplication, title = "Todo") {
            App(repository)
        }
    }
}
