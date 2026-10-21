package io.github.nailkhaf.nosqlite

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

/** The README example, so it keeps compiling and doing what it says. */
internal class ReadmeTest {

    @Serializable
    data class Name(val first: String, val last: String)

    @Serializable
    data class User(
        val id: String,
        val email: String,
        val name: Name,
        val age: Int,
        val tags: List<String> = emptyList(),
    )

    @Serializable
    data class Summary(val id: String, val age: Int)

    object Users : Collection("users") {
        val byId = unique(User::id)
        val byEmail = unique(User::email)
        val byAge = index(User::age)
        val byName = index(User::name / Name::last, User::name / Name::first)
    }

    @Test
    fun `the readme example does what it says`() = runTest {
        withTempFile { location ->
            val db = Database(location.path) { +Users }

            // Writes
            val id: DocumentId = db.write {
                Users.insert(User("u1", "ada@example.com", Name("Ada", "Lovelace"), 36, listOf("kotlin")))
            }
            db.write {
                Users.insert(User("u2", "kid@example.com", Name("Kid", "Lovelace"), 9))
                assertEquals(1, Users.byEmail.patch("ada@example.com", buildJsonObject { put("age", 37) }))
                assertEquals(1, Users.delete { User::age lt 18 })
            }

            // Reads
            db.read {
                val ada: User? = Users[id]
                val same: User? = Users.byEmail["ada@example.com"]
                assertEquals(37, ada?.age)
                assertEquals(ada, same)

                val adults = Users.select<User> {
                    where { (User::age gte 18) and (User::tags contains "kotlin") }
                    orderBy(User::age.desc)
                }
                assertEquals(listOf(ada), adults.list(limit = 20))
                assertEquals(1, adults.count())
            }

            // Pages and observers
            assertEquals(
                listOf(listOf(Summary("u1", 37))),
                Users.select<Summary> { orderBy(User::age) }.pages(db, size = 50).toList().map { it.items },
            )
            assertEquals(listOf(Summary("u1", 37)), Users.select<Summary>().observe(db, limit = 20).first())
            assertEquals(37, Users.byEmail.observe<User>(db, "ada@example.com").first()?.age)
            db.close()
        }
    }
}
