package io.github.nailkhaf.nosqlite

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

/** Covers typed writes: insert, and update, patch and delete by id, by filter and by unique key. */
internal class WriteTest {

    object Users : Collection("users") {
        val byId = unique(User::id)
        val byEmail = unique(User::email)
    }

    /** No unique index, so several documents can be replaced by the same one. */
    object Tasks : Collection("tasks")

    @Serializable
    data class Task(val title: String, val done: Boolean)

    @Serializable
    data class Summary(val id: String, val age: Int)

    private val ann = user("ann", 17, tags = listOf("kotlin"))
    private val bob = user("bob", 30, nickname = "bobby")
    private val cid = user("cid", 45)

    /** A database holding ann, bob and cid, with their ids in that order. */
    private suspend fun seeded(): Pair<Database, List<DocumentId>> {
        val db = Database { +Users }
        return db to db.write { listOf(ann, bob, cid).map { Users.insert(it) } }
    }

    private suspend fun Database.user(id: DocumentId): User? = read { Users.get<User>(id) }

    private suspend fun Database.user(key: String): User? = read { Users.byId.get<User>(key) }

    @Test
    fun `insert encodes a serializable object`() = runTest {
        val (db, ids) = seeded()

        assertEquals(listOf(ann, bob, cid), ids.map { db.user(it) })
    }

    @Test
    fun `insert of a value that is not an object is rejected`() = runTest {
        val db = Database { +Users }

        val error = assertFailsWith<IllegalArgumentException> { db.write { Users.insert(42) } }

        assertEquals("A document of 'users' must be a JSON object, not 42", error.message)
    }

    @Test
    fun `get decodes into a smaller class`() = runTest {
        val (db, ids) = seeded()

        val summary = db.read { Users.get<Summary>(ids[1]) }

        assertEquals(Summary("bob", 30), summary)
    }

    @Test
    fun `update by id replaces the whole document`() = runTest {
        val (db, ids) = seeded()
        val replacement = ann.copy(age = 18, tags = emptyList())

        val updated = db.write { Users.update(ids[0], replacement) }

        assertEquals(1, updated)
        assertEquals(replacement, db.user(ids[0]))
    }

    @Test
    fun `update of an unknown id changes nothing`() = runTest {
        val (db, _) = seeded()

        val updated = db.write { Users.update(DocumentId(99), ann) }

        assertEquals(0, updated)
    }

    @Test
    fun `update by filter replaces every match`() = runTest {
        val db = Database { +Tasks }
        val ids = db.write {
            listOf(Task("a", done = true), Task("b", done = false), Task("c", done = true)).map { Tasks.insert(it) }
        }

        val updated = db.write { Tasks.update(Task("archived", done = true)) { Task::done eq true } }

        assertEquals(2, updated)
        assertEquals(listOf("archived", "b", "archived"), db.read { ids.map { Tasks.get<Task>(it)!!.title } })
    }

    @Test
    fun `patch by id merges fields and removes nulls`() = runTest {
        val (db, ids) = seeded()
        val patch = buildJsonObject {
            put("age", 31)
            put("nickname", JsonNull)
        }

        val patched = db.write { Users.patch(ids[1], patch) }

        assertEquals(1, patched)
        assertEquals(bob.copy(age = 31, nickname = null), db.user(ids[1]))
    }

    @Test
    fun `patch by filter changes every match`() = runTest {
        val (db, ids) = seeded()

        val patched = db.write { Users.patch(buildJsonObject { put("active", false) }) { User::age gte 18 } }

        assertEquals(2, patched)
        assertEquals(listOf(true, false, false), ids.map { db.user(it)!!.active })
    }

    @Test
    fun `delete by filter removes every match`() = runTest {
        val (db, ids) = seeded()

        val deleted = db.write { Users.delete { (User::age lt 18) or (User::nickname eq "bobby") } }

        assertEquals(2, deleted)
        assertEquals(listOf(null, null, cid), ids.map { db.user(it) })
    }

    @Test
    fun `update by unique key replaces that document`() = runTest {
        val (db, _) = seeded()

        val updated = db.write { Users.byId.update("ann", ann.copy(age = 20)) }

        assertEquals(1, updated)
        assertEquals(20, db.user("ann")?.age)
    }

    @Test
    fun `patch by unique key changes that document`() = runTest {
        val (db, _) = seeded()

        val patched = db.write { Users.byEmail.patch(bob.email, buildJsonObject { put("age", 33) }) }

        assertEquals(1, patched)
        assertEquals(33, db.user("bob")?.age)
    }

    @Test
    fun `delete by unique key removes that document once`() = runTest {
        val (db, _) = seeded()

        val first = db.write { Users.byId.delete("cid") }
        val second = db.write { Users.byId.delete("cid") }

        assertEquals(1, first)
        assertEquals(0, second)
        assertNull(db.user("cid"))
    }

    @Test
    fun `update into a taken key is rejected and changes nothing`() = runTest {
        val (db, ids) = seeded()

        val error = assertFailsWith<NosqliteException.UniqueViolation> {
            db.write { Users.update(ids[0], ann.copy(email = bob.email)) }
        }

        assertSame(Users.byEmail, error.index)
        assertEquals(ann, db.user(ids[0]))
    }

    @Test
    fun `patch into a taken key is rejected and changes nothing`() = runTest {
        val (db, ids) = seeded()

        val error = assertFailsWith<NosqliteException.UniqueViolation> {
            db.write { Users.patch(ids[0], buildJsonObject { put("id", "bob") }) }
        }

        assertSame(Users.byId, error.index)
        assertEquals(ann, db.user(ids[0]))
    }
}
