package io.github.nailkhaf.nosqlite

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

/** The library as an app uses it, through its public API only. */
internal class UsageTest {

    object Users : Collection<User>("users", User.serializer()) {
        val byId = unique("id")
        val byEmail = unique("email")
        val byAge = index("age")
        val byName = index("name.last", "name.first")
        val activeNickname = unique("nickname", where = { "active" eq true })
    }

    private val ada = user("ada", 36, first = "Ada", last = "Lovelace")
    private val alan = user("alan", 41, first = "Alan", last = "Turing")

    @Test
    fun `documents are found by their unique keys`() = runTest {
        val db = Database { +Users }

        db.write {
            Users.insert(ada)
            Users.insert(alan)
        }

        db.read {
            assertEquals(ada, Users.first { "id" eq "ada" })
            assertEquals(alan, Users.first { "email" eq Email("alan@example.com") })
            assertNull(Users.first { "id" eq "grace" })
        }
    }

    @Test
    fun `a duplicate key fails only its own insert`() = runTest {
        val db = Database { +Users }

        db.write {
            Users.insert(ada)
            val error = assertFailsWith<NosqliteException.UniqueViolation> {
                Users.insert(alan.copy(email = ada.email))
            }
            assertSame(Users.byEmail, error.index)
            Users.insert(alan)
        }

        assertEquals(alan, db.read { Users.first { "id" eq "alan" } })
    }

    @Test
    fun `a plain index lets documents share a value`() = runTest {
        val db = Database { +Users }

        db.write {
            Users.insert(ada)
            Users.insert(alan.copy(age = ada.age))
        }

        assertEquals(ada.age, db.read { Users.first { "id" eq "alan" } }?.age)
    }

    @Test
    fun `a write that throws stores nothing`() = runTest {
        val db = Database { +Users }

        assertFailsWith<IllegalStateException> {
            db.write {
                Users.insert(ada)
                error("Cancelled")
            }
        }

        assertNull(db.read { Users.first { "id" eq "ada" } })
    }

    @Test
    fun `a nested write rolls back alone`() = runTest {
        val db = Database { +Users }

        db.write {
            Users.insert(ada)
            runCatching {
                db.write {
                    Users.insert(alan)
                    error("Cancelled")
                }
            }
        }

        db.read {
            assertEquals(ada, Users.first { "id" eq "ada" })
            assertNull(Users.first { "id" eq "alan" })
        }
    }

    @Test
    fun `a partial unique index only covers matching documents`() = runTest {
        val db = Database { +Users }

        db.write {
            Users.insert(user("ann", 30, nickname = "twin", active = false))
            Users.insert(user("bob", 30, nickname = "twin", active = false))
            Users.insert(user("cid", 30, nickname = "twin"))
            assertFailsWith<NosqliteException.UniqueViolation> { Users.insert(user("dan", 30, nickname = "twin")) }
        }

        assertEquals("cid", db.read { Users.first { ("nickname" eq "twin") and ("active" eq true) } }?.id)
    }

    @Test
    fun `documents outlive the database that wrote them`() = runTest {
        withTempFile { location ->
            Database(location) { +Users }.apply { write { Users.insert(ada) } }.close()

            val reopened = Database(location) { +Users }

            assertEquals(ada, reopened.read { Users.first { "id" eq "ada" } })
            reopened.close()
        }
    }
}
