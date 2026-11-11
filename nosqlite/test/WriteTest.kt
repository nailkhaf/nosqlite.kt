package io.github.nailkhaf.nosqlite

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Covers update, replace and delete of the documents matching a condition. */
internal class WriteTest {

    object Users : Collection("users")

    object Elements : Collection("elements")

    object Raw : Collection("raw")

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

        val updated = db.write { Users.update(replacement) { "id" eq ann.id } }

        assertEquals(1, updated)
        assertEquals(listOf(replacement, bob, cid), db.users())
    }

    @Test
    fun `update writes the value into every match`() = runTest {
        val db = NoSqlite { +Raw }
        val done = buildJsonObject { put("done", true) }
        val a = buildJsonObject { put("id", uuid("a")); put("done", true) }
        val b = buildJsonObject { put("id", uuid("b")); put("done", true) }
        db.write {
            Raw.insert(buildJsonObject { put("id", uuid("a")); put("done", false) })
            Raw.insert(buildJsonObject { put("id", uuid("b")); put("done", false) })
        }

        val updated = db.write { Raw.update(done) { "done".isFalse() } }

        assertEquals(2, updated)
        assertEquals(listOf(a, b), db.read { Raw.list<JsonObject>() })
    }

    @Test
    fun `replace stores the value as the whole document`() = runTest {
        val db = NoSqlite { +Raw }
        val id = uuid("a")
        db.write { Raw.insert(buildJsonObject { put("id", id); put("age", 30); put("nickname", "x") }) }
        val replacement = buildJsonObject { put("id", id); put("age", 31) }

        val replaced = db.write { Raw.replace(replacement) { "id" eq id } }

        assertEquals(1, replaced)
        assertEquals(listOf(replacement), db.read { Raw.list<JsonObject>() })
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
            assertEquals(0, Users.update(ann) { "id" eq uuid("nobody") })
            assertEquals(0, Users.replace(ann) { "id" eq uuid("nobody") })
            assertEquals(0, Users.delete { "id" eq uuid("nobody") })
        }

        assertEquals(listOf(ann, bob, cid), db.users())
    }

    @Test
    fun `update merges fields and removes nulls`() = runTest {
        val db = NoSqlite { +Raw }
        db.write {
            val a = uuid("a")
            val b = uuid("b")
            Raw.insert(buildJsonObject { put("id", a); put("age", 30); put("nickname", "x") })
            Raw.insert(buildJsonObject { put("id", b); put("age", 40) })
        }
        val patch = buildJsonObject {
            put("age", 31)
            put("nickname", JsonNull)
        }

        val patched = db.write { Raw.update(patch) { "id" eq uuid("a") } }

        val expected = buildJsonObject { put("id", uuid("a")); put("age", 31) }
        assertEquals(1, patched)
        val untouched = buildJsonObject { put("id", uuid("b")); put("age", 40) }
        assertEquals(listOf(expected, untouched), db.read { Raw.list<JsonObject>() })
    }

    @Test
    fun `update of a value that is not an object is rejected`() = runTest {
        val db = NoSqlite { +Elements }

        val error = assertFailsWith<IllegalArgumentException> {
            db.write { Elements.update(JsonPrimitive(42)) { "id" eq 1 } }
        }

        assertEquals("A document of 'elements' must be a JSON object, not 42", error.message)
    }

    @Test
    fun `writes to an undeclared collection are rejected`() = runTest {
        val db = NoSqlite { +Raw }

        assertFailsWith<NoSqliteException.CollectionNotDeclared> { db.write { Users.delete { "id" eq "ann" } } }
        assertFailsWith<NoSqliteException.CollectionNotDeclared> { db.write { Users.update(ann) { "id" eq "ann" } } }
    }
}
