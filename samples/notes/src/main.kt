package io.github.nailkhaf.nosqlite.sample.notes

import io.github.nailkhaf.nosqlite.Collection
import io.github.nailkhaf.nosqlite.Database
import io.github.nailkhaf.nosqlite.Location
import io.github.nailkhaf.nosqlite.NosqliteException
import io.github.nailkhaf.nosqlite.count
import io.github.nailkhaf.nosqlite.delete
import io.github.nailkhaf.nosqlite.div
import io.github.nailkhaf.nosqlite.first
import io.github.nailkhaf.nosqlite.forEach
import io.github.nailkhaf.nosqlite.get
import io.github.nailkhaf.nosqlite.insert
import io.github.nailkhaf.nosqlite.list
import io.github.nailkhaf.nosqlite.observe
import io.github.nailkhaf.nosqlite.pages
import io.github.nailkhaf.nosqlite.patch
import io.github.nailkhaf.nosqlite.read
import io.github.nailkhaf.nosqlite.select
import io.github.nailkhaf.nosqlite.update
import io.github.nailkhaf.nosqlite.write
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.io.path.createTempDirectory

@Serializable
data class Author(val first: String, val last: String)

@Serializable
data class Note(
    val slug: String,
    val title: String,
    val author: Author,
    val stars: Int = 0,
    val tags: List<String> = emptyList(),
    val archived: Boolean = false,
)

/** A projection: only the fields a list screen needs. */
@Serializable
data class NoteTitle(val slug: String, val title: String, val stars: Int)

object Notes : Collection("notes") {
    val bySlug = unique(Note::slug)
    val byStars = index(Note::stars)
    val byAuthor = index(Note::author / Author::last, Note::author / Author::first)
    val liveByTitle = index(Note::title, where = { Note::archived eq false })
}

fun main(): Unit = runBlocking {
    val file = createTempDirectory("nosqlite-notes").resolve("notes.db").toString()
    val db = Database(Location.File(file)) { +Notes }
    println("Opened $file")

    // Watch the starred notes while the rest of the sample writes.
    val starred = launch {
        Notes.select<NoteTitle> {
            where { Note::stars gte 3 }
            orderBy(Note::stars.desc)
        }
            .observe(db, limit = 3)
            .take(4)
            .collect { titles -> println("  observed starred: ${titles.map { it.slug }}") }
    }
    yield()

    val ada = Author("Ada", "Lovelace")
    val alan = Author("Alan", "Turing")
    val id = db.write {
        val first = Notes.insert(
            Note("engines", "Analytical engines", ada, stars = 5, tags = listOf("history", "math")),
        )
        Notes.insert(Note("machines", "Computing machinery", alan, stars = 4, tags = listOf("ai")))
        Notes.insert(Note("numbers", "Computable numbers", alan, stars = 2, tags = listOf("math")))
        first
    }
    println("Inserted 3 notes, the first is $id")

    try {
        db.write { Notes.insert(Note("engines", "Duplicate", ada)) }
    } catch (e: NosqliteException.UniqueViolation) {
        println("Rejected duplicate slug: ${e.index}")
    }

    db.read {
        println("By id: ${Notes.get<Note>(id)?.title}")
        println("By slug: ${Notes.bySlug.get<Note>("machines")?.title}")
        val math = Notes.select<NoteTitle> {
            where { (Note::tags contains "math") and (Note::archived eq false) }
            orderBy(Note::stars.desc)
        }
        println("Math notes: ${math.list(limit = 10).map { it.title }}, count ${math.count()}")
        println(
            "Best by Turing: ${Notes.select<Note> {
                where { Note::author / Author::last eq "Turing" }
                orderBy(Note::stars.desc)
            }.first()?.title}",
        )
    }

    db.write {
        val patched = Notes.bySlug.patch("numbers", buildJsonObject { put("stars", 3) })
        val replaced = Notes.bySlug.update(
            "machines",
            Note("machines", "Computing machinery and intelligence", alan, stars = 4),
        )
        val archived = Notes.patch(buildJsonObject { put("archived", true) }) { Note::stars lt 4 }
        println("Patched $patched, replaced $replaced, archived $archived")
    }

    db.write { (1..7).forEach { Notes.insert(Note("draft-$it", "Draft $it", ada)) } }
    Notes.select<NoteTitle> { where { Note::author / Author::first eq "Ada" } }.pages(db, size = 3).collect { page ->
        println("Page ${page.number}${if (page.isLast) " (last)" else ""}: ${page.items.map { it.slug }}")
    }

    db.read {
        var total = 0
        Notes.select<NoteTitle>().forEach { total += it.stars }
        println("Stars across all notes (streamed one row at a time): $total")
    }

    val deleted = db.write { Notes.delete { Note::slug isIn (1..7).map { "draft-$it" } } }
    println("Deleted $deleted drafts")
    db.write { Notes.bySlug.patch("engines", buildJsonObject { put("tags", JsonNull) }) }
    db.write { Notes.delete(id) }

    starred.join()
    println("Remaining: ${db.read { Notes.select<NoteTitle>().list(10).map { it.slug } }}")
    db.close()
}
