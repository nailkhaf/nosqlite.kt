package io.github.nailkhaf.nosqlite

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/** Covers update, replace and delete of the documents matching a condition. */
internal class WriteTest {

    object Users : Collection("users") {
        val byId = unique("id")
        val byEmail = unique("email")
    }

    object Raw : Collection("raw") {
        val byId = unique("id")
    }

    private val ann = user("ann", 17, tags = listOf("kotlin"))
    private val bob = user("bob", 30, nickname = "bobby")
    private val cid = user("cid", 45)

    private suspend fun seeded(): NoSqlite {
        val db = NoSqlite { +Users }
        db.write { listOf(ann, bob, cid).forEach { Users.insert(it) } }
        return db
    }

    private suspend fun NoSqlite.users(): List<User> = read { Users.list<User>() }

    @Test
    fun `update writes the value into the matching document`() = runTest {
        val db = seeded()
        val replacement = ann.copy(age = 18, tags = emptyList())

        val updated = db.write { Users.byId("ann").update(replacement) }

        assertEquals(true, updated)
        assertEquals(listOf(replacement, bob, cid), db.users())
    }

    @Test
    fun `replace stores the value as the whole document`() = runTest {
        val db = NoSqlite { +Raw }
        db.write { Raw.insert(buildJsonObject { put("id", "a"); put("age", 30); put("nickname", "x") }) }
        val replacement = buildJsonObject { put("id", "a"); put("age", 31) }

        val replaced = db.write { Raw.byId("a").replace(replacement) }

        assertEquals(true, replaced)
        assertEquals(listOf(replacement), db.read { Raw.list<JsonObject>() })
    }

    @Test
    fun `delete removes the one document`() = runTest {
        val db = seeded()

        val deleted = db.write {
            Users.byId("ann").delete() && Users.byEmail(bob.email.value).delete()
        }

        assertEquals(true, deleted)
        assertEquals(listOf(cid), db.users())
    }

    @Test
    fun `writes that match nothing change nothing`() = runTest {
        val db = seeded()

        db.write {
            assertEquals(false, Users.byId("nobody").update(ann))
            assertEquals(false, Users.byId("nobody").replace(ann))
            assertEquals(false, Users.byId("nobody").delete())
        }

        assertEquals(listOf(ann, bob, cid), db.users())
    }

    @Test
    fun `update merges fields and removes nulls`() = runTest {
        val db = NoSqlite { +Raw }
        db.write {
            Raw.insert(buildJsonObject { put("id", "a"); put("age", 30); put("nickname", "x") })
            Raw.insert(buildJsonObject { put("id", "b"); put("age", 40) })
        }
        val patch = buildJsonObject {
            put("age", 31)
            put("nickname", JsonNull)
        }

        val patched = db.write { Raw.byId("a").update(patch) }

        val expected = buildJsonObject { put("id", "a"); put("age", 31) }
        assertEquals(true, patched)
        val untouched = buildJsonObject { put("id", "b"); put("age", 40) }
        assertEquals(listOf(expected, untouched), db.read { Raw.list<JsonObject>() })
    }

    @Test
    fun `update of a value that is not an object is rejected`() = runTest {
        val db = NoSqlite { +Users }

        val error = assertFailsWith<IllegalArgumentException> {
            db.write { Users.byId("ann").update(JsonPrimitive(42)) }
        }

        assertEquals("A document of 'users' must be a JSON object, not 42", error.message)
    }

    @Test
    fun `update into a taken key is rejected and changes nothing`() = runTest {
        val db = seeded()

        val error = assertFailsWith<NoSqliteException.UniqueViolation> {
            db.write { Users.byId("ann").update(ann.copy(email = bob.email)) }
        }

        assertSame(Users.byEmail, error.index)
        assertEquals(listOf(ann, bob, cid), db.users())
    }

    @Test
    fun `writes to an undeclared collection are rejected`() = runTest {
        val db = NoSqlite { +Raw }

        assertFailsWith<NoSqliteException.CollectionNotDeclared> { db.write { Users.byId("ann").delete() } }
        assertFailsWith<NoSqliteException.CollectionNotDeclared> { db.write { Users.byId("ann").update(ann) } }
    }
}
