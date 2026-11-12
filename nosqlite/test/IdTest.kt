package io.github.nailkhaf.nosqlite

import androidx.sqlite.SQLiteException
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.uuid.Uuid
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Covers the id every document carries: required, a UUID string, unique and immutable. */
internal class IdTest {

    object Users : Collection("users")

    private val adaId = uuid("ada")
    private val alanId = uuid("alan")

    private fun person(id: Uuid, name: String = "Ada", age: Int = 36, plan: String? = null): JsonObject =
        buildJsonObject {
            put("id", id.toString())
            put("name", name)
            put("age", age)
            if (plan != null) put("plan", plan)
        }

    @Test
    fun `insert and upsert reject an id that is not a lowercase uuid`() = runTest {
        val db = NoSqlite { +Users }
        val canonical = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"

        for (value in badIds()) {
            val inserted = assertFailsWith<NoSqliteException.InvalidId> { db.write { Users.insert(value) } }
            val upserted = assertFailsWith<NoSqliteException.InvalidId> { db.write { Users.upsert(value) } }
            assertEquals("users", inserted.collection)
            assertEquals("users", upserted.collection)
            assertEquals("A document of 'users' must have a UUID string id", inserted.message)
            assertEquals(inserted.message, upserted.message)
        }
        assertEquals(0, db.read { Users.count() })

        db.write { Users.insert(buildJsonObject { put("id", canonical); put("name", "Ada") }) }
        val stored = db.read { Users.id(Uuid.parse(canonical)).get<JsonObject>() }!!
            .getValue("id").jsonPrimitive.content

        assertEquals(canonical, stored)
        assertEquals(1, db.read { Users.count() })
    }

    @Test
    fun `replacing a document with a bad id is rejected`() = runTest {
        val db = NoSqlite { +Users }
        val ada = person(adaId)
        db.write { Users.insert(ada) }

        for (value in badIds()) {
            val error = assertFailsWith<NoSqliteException.ImmutableId> {
                db.write { Users.replace(value) { "id" eq adaId.toString() } }
            }
            assertEquals("users", error.collection)
            assertEquals("Id of a document in 'users' is immutable", error.message)
        }

        assertEquals(ada, db.read { Users.id(adaId).get<JsonObject>() })
    }

    @Test
    fun `an update that sets the id to null or a non uuid is rejected`() = runTest {
        val db = NoSqlite { +Users }
        val ada = person(adaId)
        db.write { Users.insert(ada) }

        val cleared = assertFailsWith<NoSqliteException.ImmutableId> {
            db.write { Users.id(adaId).update(buildJsonObject { put("id", JsonNull) }) }
        }
        val rewritten = assertFailsWith<NoSqliteException.ImmutableId> {
            db.write { Users.id(adaId).update(buildJsonObject { put("id", "ada") }) }
        }

        assertEquals("users", cleared.collection)
        assertEquals("users", rewritten.collection)
        assertEquals(ada, db.read { Users.id(adaId).get<JsonObject>() })
    }

    @Test
    fun `an id cannot be changed`() = runTest {
        val db = NoSqlite { +Users }
        val ada = person(adaId)
        db.write { Users.insert(ada) }

        val update = assertFailsWith<NoSqliteException.ImmutableId> {
            db.write { Users.id(adaId).update(buildJsonObject { put("id", alanId.toString()) }) }
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
                Users.id(adaId).update(buildJsonObject { put("id", alanId.toString()) })
            }
            Users.insert(person(alanId))
        }

        val ids = db.read { Users.list<JsonObject>() }.map { it.getValue("id").jsonPrimitive.content }
        assertEquals(setOf(adaId.toString(), alanId.toString()), ids.toSet())
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
    fun `upsert by id keeps unknown fields`() = runTest {
        val db = NoSqlite { +Users }
        db.write { Users.insert(person(adaId, plan = "pro")) }

        db.write { Users.upsert(buildJsonObject { put("id", adaId.toString()); put("name", "Augusta") }) }

        assertEquals(person(adaId, name = "Augusta", plan = "pro"), db.read { Users.id(adaId).get<JsonObject>() })
    }

    @Test
    fun `the id trigger is stored with the table and rejects a change from another connection`() = runTest {
        withTempFile { location ->
            val db = NoSqlite(location) { +Users }
            val ada = person(adaId)
            db.write { Users.insert(ada) }
            db.close()

            BundledSQLiteDriver().open(location.path).apply {
                val error = assertFailsWith<SQLiteException> {
                    execSQL("""UPDATE users SET data = jsonb('{"id":"$alanId","name":"Ada","age":36}')""")
                }
                assertTrue("id is immutable" in error.message.orEmpty(), error.message)
                close()
            }

            val reopened = NoSqlite(location) { +Users }
            val update = assertFailsWith<NoSqliteException.ImmutableId> {
                reopened.write { Users.id(adaId).update(buildJsonObject { put("id", alanId.toString()) }) }
            }
            assertEquals("users", update.collection)
            assertEquals(ada, reopened.read { Users.id(adaId).get<JsonObject>() })
            reopened.close()
        }
    }

    /** A missing id, a null id, a number, an uppercase UUID and a UUID with the hyphens removed. */
    private fun badIds(): List<JsonObject> {
        val canonical = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"
        return listOf(
            buildJsonObject { put("name", "Ada") },
            buildJsonObject { put("id", JsonNull); put("name", "Ada") },
            buildJsonObject { put("id", 36) },
            buildJsonObject { put("id", canonical.uppercase()) },
            buildJsonObject { put("id", canonical.replace("-", "")) },
        )
    }
}
