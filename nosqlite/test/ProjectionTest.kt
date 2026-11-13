package io.github.nailkhaf.nosqlite

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.uuid.Uuid
import kotlin.test.assertEquals

/** Covers reading documents as different types, and typed updates keeping the fields their type doesn't declare. */
internal class ProjectionTest {

    @Serializable
    data class Address(val city: String)

    @Serializable
    data class Profile(
        val id: Uuid,
        val address: Address,
        val settings: Map<String, String> = emptyMap(),
        val nickname: String? = null,
    )

    @Serializable
    data class Summary(val id: Uuid)

    object Profiles : Collection("profiles")

    private val ada = uuid("ada")
    private val alan = uuid("alan")

    // What a newer backend sends: fields this app's Profile doesn't know yet, at the top and nested.
    private val downloaded = json(
        """
        {"id":"$ada","address":{"city":"London","zip":"N1"},"settings":{"theme":"dark","lang":"en"},
         "nickname":"countess","plan":"pro"}
        """,
    )

    private suspend fun seeded(): NoSqlite {
        val db = NoSqlite { +Profiles }
        db.write { Profiles.insert(downloaded) }
        return db
    }

    private suspend fun NoSqlite.stored(): JsonObject = read { Profiles.id(ada).get<JsonObject>()!! }

    @Test
    fun `one collection reads as any type its documents decode to`() = runTest {
        val db = seeded()

        db.read {
            assertEquals(listOf(Summary(ada)), Profiles.list<Summary>())
            assertEquals(downloaded, Profiles.list<JsonObject>().single())
            assertEquals(
                Profile(ada, Address("London"), mapOf("theme" to "dark", "lang" to "en"), "countess"),
                Profiles.id(ada).get<Profile>(),
            )
        }
        assertEquals(Summary(ada), with(db) { Profiles.id(ada).observe<Summary>().first() })
    }

    @Test
    fun `a typed update keeps the fields its type does not declare`() = runTest {
        val db = seeded()
        val profile = db.read { Profiles.id(ada).get<Profile>()!! }

        db.write { Profiles.id(ada).update(profile.copy(address = Address("Paris"))) }

        val expected = json(
            """
            {"id":"$ada","address":{"city":"Paris","zip":"N1"},"settings":{"theme":"dark","lang":"en"},
             "nickname":"countess","plan":"pro"}
            """,
        )
        assertEquals(expected, db.stored())
    }

    @Test
    fun `a typed update removes a declared field set to null`() = runTest {
        val db = seeded()

        db.write { Profiles.update(Profile(ada, Address("London"), nickname = null)) { "id" eq ada.toString() } }

        val expected = json(
            """
            {"id":"$ada","address":{"city":"London","zip":"N1"},"settings":{"theme":"dark","lang":"en"},"plan":"pro"}
            """,
        )
        assertEquals(expected, db.stored())
    }

    @Test
    fun `a typed update merges a map field`() = runTest {
        val db = seeded()

        db.write { Profiles.id(ada).update(Profile(ada, Address("London"), mapOf("theme" to "light"))) }

        assertEquals(json("""{"theme":"light","lang":"en"}"""), db.stored()["settings"])
    }

    @Test
    fun `an update without merge drops the fields its type does not declare`() = runTest {
        val db = seeded()

        val replacement = Profile(ada, Address("Rome"), mapOf("theme" to "light"))
        db.write { Profiles.id(ada).update(replacement, merge = false) }

        val expected = json("""{"id":"$ada","address":{"city":"Rome"},"settings":{"theme":"light"},"nickname":null}""")
        assertEquals(expected, db.stored())
    }

    @Test
    fun `an upsert into an existing document keeps the fields its type does not declare`() = runTest {
        val db = seeded()

        db.write {
            Profiles.upsert(Profile(ada, Address("Paris"), mapOf("theme" to "dark", "lang" to "en"), "countess"))
            Profiles.upsert(Profile(alan, Address("Wilmslow")))
        }

        val kept = json(
            """
            {"id":"$ada","address":{"city":"Paris","zip":"N1"},"settings":{"theme":"dark","lang":"en"},
             "nickname":"countess","plan":"pro"}
            """,
        )
        val inserted = json("""{"id":"$alan","address":{"city":"Wilmslow"},"settings":{},"nickname":null}""")
        assertEquals(listOf(kept, inserted), db.read { Profiles.list<JsonObject>() })
    }

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject
}
