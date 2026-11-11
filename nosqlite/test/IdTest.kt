package io.github.nailkhaf.nosqlite

import androidx.sqlite.SQLiteException
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Covers the id every document carries: required, a UUID string, unique and immutable. */
internal class IdTest {

    object Users : Collection("users")

    private val adaId = uuid("ada")
    private val alanId = uuid("alan")

    private fun person(id: String, name: String = "Ada", age: Int = 36, plan: String? = null): JsonObject =
        buildJsonObject {
            put("id", id)
            put("name", name)
            put("age", age)
            if (plan != null) put("plan", plan)
        }

    @Test
    fun `insert rejects a missing id`() = runTest {
        val db = NoSqlite { +Users }

        val error = assertFailsWith<IllegalArgumentException> {
            db.write { Users.insert(buildJsonObject { put("name", "Ada") }) }
        }

        assertEquals("A document of 'users' must have an id", error.message)
        assertEquals(0, db.read { Users.count() })
    }

    @Test
    fun `an id has to be a UUID string`() = runTest {
        val db = NoSqlite { +Users }
        val canonical = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"
        val upper = canonical.uppercase()
        val cases = listOf(
            buildJsonObject { put("id", "ada") } to "A document of 'users' must have a UUID string id, not 'ada'",
            buildJsonObject { put("id", 36) } to "A document of 'users' must have a UUID string id, not 36",
            buildJsonObject { put("id", upper) } to "A document of 'users' must have a UUID string id, not '$upper'",
            buildJsonObject { put("id", canonical.replace("-", "")) } to
                "A document of 'users' must have a UUID string id, not '${canonical.replace("-", "")}'",
        )

        for ((value, message) in cases) {
            val error = assertFailsWith<IllegalArgumentException> { db.write { Users.insert(value) } }
            assertEquals(message, error.message)
        }
        db.write { Users.insert(buildJsonObject { put("id", canonical); put("name", "Ada") }) }
        val stored = db.read { Users.id(canonical).get<JsonObject>() }!!.getValue("id").jsonPrimitive.content
        val lookup = assertFailsWith<IllegalArgumentException> { Users.id("ada") }

        assertEquals(canonical, stored)
        assertEquals("Id must be a UUID string, not 'ada'", lookup.message)
        assertEquals(1, db.read { Users.count() })
    }

    @Test
    fun `an id cannot be changed`() = runTest {
        val db = NoSqlite { +Users }
        val ada = person(adaId)
        db.write { Users.insert(ada) }

        val update = assertFailsWith<NoSqliteException.ImmutableId> {
            db.write { Users.id(adaId).update(buildJsonObject { put("id", alanId) }) }
        }
        val replace = assertFailsWith<NoSqliteException.ImmutableId> {
            db.write { Users.id(adaId).replace(person(alanId)) }
        }

        assertEquals("users", update.collection)
        assertEquals("Id of a document in 'users' is immutable", update.message)
        assertEquals("users", replace.collection)
        assertEquals(ada, db.read { Users.id(adaId).get<JsonObject>() })
    }

    @Test
    fun `a caught id change leaves the rest of the write`() = runTest {
        val db = NoSqlite { +Users }

        db.write {
            Users.insert(person(adaId))
            assertFailsWith<NoSqliteException.ImmutableId> {
                Users.id(adaId).update(buildJsonObject { put("id", alanId) })
            }
            Users.insert(person(alanId))
        }

        val ids = db.read { Users.list<JsonObject>() }.map { it.getValue("id").jsonPrimitive.content }
        assertEquals(setOf(adaId, alanId), ids.toSet())
    }

    @Test
    fun `a duplicate id is rejected`() = runTest {
        val db = NoSqlite { +Users }
        val ada = person(adaId, age = 36)
        db.write { Users.insert(ada) }

        val error = assertFailsWith<NoSqliteException.DuplicateId> {
            db.write { Users.insert(person(adaId, age = 37)) }
        }

        assertEquals("users", error.collection)
        assertEquals("Duplicate id in 'users'", error.message)
        assertEquals(ada, db.read { Users.id(adaId).get<JsonObject>() })
        assertNull(db.read { Users.id(alanId).get<JsonObject>() })
    }

    @Test
    fun `a caught duplicate id leaves the write usable`() = runTest {
        val db = NoSqlite { +Users }

        db.write {
            Users.insert(person(adaId))
            assertFailsWith<NoSqliteException.DuplicateId> { Users.insert(person(adaId, name = "Other")) }
            Users.insert(person(alanId))
        }

        assertEquals(2, db.read { Users.count() })
    }

    @Test
    fun `a unique constraint on another index is not a duplicate id`() = runTest {
        val db = NoSqlite { +Users }
        db.pool().writeSql { execute("CREATE UNIQUE INDEX mine ON users (data ->> '$.email')") }
        val first = buildJsonObject { put("id", adaId); put("email", "a@x.com") }
        val second = buildJsonObject { put("id", alanId); put("email", "a@x.com") }
        db.write { Users.insert(first) }

        val error = assertFailsWith<SQLiteException> { db.write { Users.insert(second) } }

        assertTrue("UNIQUE constraint failed" in error.message.orEmpty(), error.message)
        assertEquals(first, db.read { Users.id(adaId).get<JsonObject>() })
    }

    @Test
    fun `opening with duplicate ids fails`() = runTest {
        withTempFile { location ->
            BundledSQLiteDriver().open(location.path).apply {
                execSQL("CREATE TABLE users (data BLOB NOT NULL) STRICT;")
                execSQL(
                    "INSERT INTO users (data) VALUES (jsonb('{\"id\":\"$adaId\"}')), (jsonb('{\"id\":\"$adaId\"}'));",
                )
                close()
            }

            val error = assertFailsWith<NoSqliteException.DuplicateId> { NoSqlite(location) { +Users }.read { } }

            assertEquals("users", error.collection)
        }
    }

    @Test
    fun `upsert by id keeps unknown fields`() = runTest {
        val db = NoSqlite { +Users }
        db.write { Users.insert(person(adaId, plan = "pro")) }

        db.write { Users.upsert(buildJsonObject { put("id", adaId); put("name", "Augusta") }) }

        assertEquals(person(adaId, name = "Augusta", plan = "pro"), db.read { Users.id(adaId).get<JsonObject>() })
    }
}
