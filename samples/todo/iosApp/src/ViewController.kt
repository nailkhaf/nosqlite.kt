package io.github.nailkhaf.todo

import androidx.compose.ui.window.ComposeUIViewController
import io.github.nailkhaf.nosqlite.Location
import io.github.nailkhaf.todo.data.TaskRepository
import io.github.nailkhaf.todo.ui.App
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask

private val repository by lazy {
    val documents = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).first() as String
    TaskRepository(Location.File("$documents/todo.db"))
}

fun ViewController() = ComposeUIViewController { App(repository) }
