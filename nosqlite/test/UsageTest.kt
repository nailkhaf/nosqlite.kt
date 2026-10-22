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
        val byId = unique(User::id)
        val byEmail = unique(User::email)
        val byAge = index(User::age)
        val byName = index(User::name / Name::last, User::name / Name::first)
        val activeNickname = unique(User::nickname, where = { User::active eq true })
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
            assertEquals(ada, Users.byId["ada"])
            assertEquals(alan, Users.byEmail[Email("alan@example.com")])
            assertNull(Users.byId["grace"])
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

        assertEquals(alan, db.read { Users.byId["alan"] })
    }

    @Test
    fun `a plain index lets documents share a value`() = runTest {
        val db = Database { +Users }

        db.write {
            Users.insert(ada)
            Users.insert(alan.copy(age = ada.age))
        }

        assertEquals(ada.age, db.read { Users.byId["alan"] }?.age)
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

        assertNull(db.read { Users.byId["ada"] })
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
            assertEquals(ada, Users.byId["ada"])
            assertNull(Users.byId["alan"])
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

        assertEquals("cid", db.read { Users.activeNickname["twin"] }?.id)
    }

    @Test
    fun `documents outlive the database that wrote them`() = runTest {
        withTempFile { location ->
            Database(location) { +Users }.apply { write { Users.insert(ada) } }.close()

            val reopened = Database(location) { +Users }

            assertEquals(ada, reopened.read { Users.byId["ada"] })
            reopened.close()
        }
    }
}
