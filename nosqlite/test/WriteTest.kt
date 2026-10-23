package io.github.nailkhaf.nosqlite

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/** Covers update and delete of the documents matching a condition, and JSON merge patches. */
internal class WriteTest {

    object Users : Collection<User>("users", User.serializer()) {
        val byId = unique("id")
        val byEmail = unique("email")
    }

    object Numbers : Collection<Int>("numbers", Int.serializer())

    object Raw : Collection<JsonObject>("raw", JsonObject.serializer())

    private val ann = user("ann", 17, tags = listOf("kotlin"))
    private val bob = user("bob", 30, nickname = "bobby")
    private val cid = user("cid", 45)

    private suspend fun seeded(): NoSqlite {
        val db = NoSqlite { +Users }
        db.write { listOf(ann, bob, cid).forEach { Users.insert(it) } }
        return db
    }

    private suspend fun NoSqlite.users(): List<User> = read { Users.list() }

    @Test
    fun `update replaces the matching document`() = runTest {
        val db = seeded()
        val replacement = ann.copy(age = 18, tags = emptyList())

        val updated = db.write { Users.update(replacement) { "id" eq "ann" } }

        assertEquals(1, updated)
        assertEquals(listOf(replacement, bob, cid), db.users())
    }

    @Test
    fun `update replaces every match with the same value`() = runTest {
        val db = NoSqlite { +Raw }
        val done = buildJsonObject { put("done", true) }
        db.write {
            Raw.insert(buildJsonObject { put("done", false) })
            Raw.insert(buildJsonObject { put("done", false) })
        }

        val updated = db.write { Raw.update(done) { "done".isFalse() } }

        assertEquals(2, updated)
        assertEquals(listOf(done, done), db.read { Raw.list() })
    }

    @Test
    fun `delete removes every match and counts it`() = runTest {
        val db = seeded()

        val deleted = db.write { Users.delete { ("age" lt 18) or ("nickname" eq "bobby") } }

        assertEquals(2, deleted)
        assertEquals(listOf(cid), db.users())
    }

    @Test
    fun `writes that match nothing change nothing`() = runTest {
        val db = seeded()

        db.write {
            assertEquals(0, Users.update(ann) { "id" eq "nobody" })
            assertEquals(0, Users.delete { "id" eq "nobody" })
        }

        assertEquals(listOf(ann, bob, cid), db.users())
    }

    @Test
    fun `patch merges fields and removes nulls`() = runTest {
        val db = NoSqlite { +Raw }
        db.write {
            Raw.insert(buildJsonObject { put("id", "a"); put("age", 30); put("nickname", "x") })
            Raw.insert(buildJsonObject { put("id", "b"); put("age", 40) })
        }
        val patch = buildJsonObject {
            put("age", 31)
            put("nickname", JsonNull)
        }

        val patched = db.write { Raw.patch(patch) { "id" eq "a" } }

        val expected = buildJsonObject { put("id", "a"); put("age", 31) }
        assertEquals(1, patched)
        assertEquals(listOf(expected, buildJsonObject { put("id", "b"); put("age", 40) }), db.read { Raw.list() })
    }

    @Test
    fun `update of a value that is not an object is rejected`() = runTest {
        val db = NoSqlite { +Numbers }

        val error = assertFailsWith<IllegalArgumentException> { db.write { Numbers.update(42) { "id" eq 1 } } }

        assertEquals("A document of 'numbers' must be a JSON object, not 42", error.message)
    }

    @Test
    fun `update into a taken key is rejected and changes nothing`() = runTest {
        val db = seeded()

        val error = assertFailsWith<NoSqliteException.UniqueViolation> {
            db.write { Users.update(ann.copy(email = bob.email)) { "id" eq "ann" } }
        }

        assertSame(Users.byEmail, error.index)
        assertEquals(listOf(ann, bob, cid), db.users())
    }

    @Test
    fun `writes to an undeclared collection are rejected`() = runTest {
        val db = NoSqlite { +Raw }

        assertFailsWith<NoSqliteException.CollectionNotDeclared> { db.write { Users.delete { "id" eq "ann" } } }
        assertFailsWith<NoSqliteException.CollectionNotDeclared> { db.write { Users.update(ann) { "id" eq "ann" } } }
    }
}
