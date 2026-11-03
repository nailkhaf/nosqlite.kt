package io.github.nailkhaf.nosqlite.tracking

import io.github.nailkhaf.nosqlite.NoSqlite
import io.github.nailkhaf.nosqlite.NoSqliteException
import io.github.nailkhaf.nosqlite.WriteScope
import io.github.nailkhaf.nosqlite.div
import io.github.nailkhaf.nosqlite.read
import io.github.nailkhaf.nosqlite.write
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/** Covers tracked writes, the one pending change each records, and the reads forwarded around them. */
internal class TrackedCollectionTest {
    private val clock = ManualClock(Instant.fromEpochMilliseconds(1_700_000_000_000))
    private val ada = Item("ada", "ada@example.com", "Ada", 36)
    private val alan = Item("alan", "alan@example.com", "Alan", 41)

    private fun TestScope.open(): Opened {
        clock.instant = Instant.fromEpochMilliseconds(1_700_000_000_000)
        val db = NoSqlite(dispatcher = UnconfinedTestDispatcher(testScheduler)) {
            +Items
            +Stock
            +Notes
            +PendingChanges
        }
        return Opened(db, ChangeTracker(db, clock))
    }

    private suspend fun NoSqlite.change(block: suspend context(WriteScope) () -> Unit) {
        clock.instant = Instant.fromEpochMilliseconds(clock.instant.toEpochMilliseconds() + 1_000)
        write(block)
    }

    @Test
    fun `each upsert records the encoded value`() = runTest {
        val (db, tracker) = open()

        db.change {
            Items.upsert(ada)
            Items.upsert(alan)
        }

        val changes = db.read { tracker.pending(Items) }
        assertEquals(2, changes.size)
        assertEquals(
            setOf(encoded(ada), encoded(alan)),
            changes.map { it.changeSet }.toSet(),
        )
        changes.forEach { change ->
            assertEquals(Items.name, change.collection)
            assertEquals(PendingChange.Action.Upsert, change.action)
            assertEquals(clock.instant, change.timestamp)
            assertEquals('7', change.id.toString()[14])
        }
    }

    @Test
    fun `upsert merges and records the patch, not the stored document`() = runTest {
        val (db, tracker) = open()
        val original = buildJsonObject {
            put("id", "ada")
            put("email", "ada@example.com")
            put("name", "Ada")
            put("age", 36)
            put("extra", "keep")
        }
        val patch = buildJsonObject {
            put("id", "ada")
            put("name", "A.")
        }

        db.change { Items.upsert(original) }
        db.change { Items.upsert(patch) }

        val stored = db.read { Items.byEmail("ada@example.com").get<JsonObject>() }!!
        assertEquals("keep", stored["extra"]!!.jsonPrimitive.content)
        assertEquals("A.", stored["name"]!!.jsonPrimitive.content)
        val changes = db.read { tracker.pending(Items) }
        assertEquals(listOf(original, patch), changes.map { it.changeSet })
        assertNull(changes[1].changeSet["extra"])
    }

    @Test
    fun `delete records only the primary key`() = runTest {
        val (db, tracker) = open()
        db.change { Items.upsert(ada) }

        var deleted = false
        db.change { deleted = Items.delete(ada.id) }

        assertTrue(deleted)
        assertNull(db.read { Items.byId(ada.id).get<Item>() })
        val removal = db.read { tracker.pending(Items) }.single { it.action == PendingChange.Action.Delete }
        assertEquals(buildJsonObject { put("id", "ada") }, removal.changeSet)
        assertEquals(Items.name, removal.collection)
    }

    @Test
    fun `a nested primary key stays nested in the change set`() = runTest {
        val (db, tracker) = open()
        val milk = Goods(Meta("milk"), "Milk")

        db.change { Stock.upsert(milk) }
        db.change { Stock.delete("milk") }

        val changes = db.read { tracker.pending(Stock) }
        assertEquals(encoded(milk), changes[0].changeSet)
        assertEquals(
            buildJsonObject { put("meta", buildJsonObject { put("id", "milk") }) },
            changes[1].changeSet,
        )
    }

    @Test
    fun `a string primary key records that field`() = runTest {
        val (db, tracker) = open()
        val note = buildJsonObject { put("id", "n1"); put("text", "hi") }

        db.change { Notes.upsert(note) }
        db.change { Notes.delete("n1") }

        val changes = db.read { tracker.pending(Notes) }
        assertEquals(note, changes[0].changeSet)
        assertEquals(buildJsonObject { put("id", "n1") }, changes[1].changeSet)
    }

    @Test
    fun `a missing primary key in the value writes nothing`() = runTest {
        val (db, tracker) = open()
        db.change { Items.upsert(ada) }

        val failure = assertFailsWith<IllegalArgumentException> {
            db.change { Items.upsert(buildJsonObject { put("name", "no id") }) }
        }

        assertTrue("primary key" in failure.message!!)
        assertEquals(listOf(ada), db.read { Items.list<Item>() })
        assertEquals(1, db.read { tracker.pending(Items).size })
    }

    @Test
    fun `a write and its change roll back together`() = runTest {
        val (db, tracker) = open()

        assertFailsWith<IllegalStateException> {
            db.change {
                Items.upsert(ada)
                error("boom")
            }
        }

        assertEquals(0, db.read { Items.count() })
        assertTrue(db.read { tracker.pending(Items).isEmpty() })
    }

    @Test
    fun `a nested write rolls back its own document and change`() = runTest {
        val (db, tracker) = open()

        db.change {
            Items.upsert(ada)
            assertFailsWith<IllegalStateException> {
                db.write {
                    Items.upsert(alan)
                    error("boom")
                }
            }
        }

        assertEquals(listOf(ada), db.read { Items.list<Item>() })
        assertEquals(listOf(encoded(ada)), db.read { tracker.pending(Items).map { it.changeSet } })
    }

    @Test
    fun `a write rolls back when pending changes are not declared`() = runTest {
        val db = NoSqlite(dispatcher = UnconfinedTestDispatcher(testScheduler)) { +Items }
        ChangeTracker(db, clock)

        assertFailsWith<NoSqliteException.CollectionNotDeclared> {
            db.write { Items.upsert(ada) }
        }

        assertEquals(0, db.read { Items.count() })
    }

    @Test
    fun `a failed unique write records nothing`() = runTest {
        val (db, tracker) = open()
        db.change { Items.upsert(ada) }

        assertFailsWith<NoSqliteException.UniqueViolation> {
            db.change { Items.upsert(ada.copy(id = "other")) }
        }

        assertEquals(listOf(ada), db.read { Items.list<Item>() })
        assertEquals(1, db.read { tracker.pending(Items).size })
    }

    @Test
    fun `applyRemote writes without recording`() = runTest {
        val (db, tracker) = open()
        db.change { Items.upsert(ada) }

        tracker.applyRemote {
            Items.upsert(buildJsonObject { put("id", ada.id); put("name", "Remote") })
            Items.delete("nobody")
        }

        val stored = db.read { Items.byId(ada.id).get<Item>() }
        assertEquals("Remote", stored!!.name)
        assertEquals(ada.email, stored.email)
        assertEquals(listOf(encoded(ada)), db.read { tracker.pending(Items).map { it.changeSet } })

        tracker.applyRemote { Items.delete(ada.id) }
        assertEquals(0, db.read { Items.count() })
        assertEquals(1, db.read { tracker.pending(Items).size })
    }

    @Test
    fun `acknowledge removes the changes it is given`() = runTest {
        val (db, tracker) = open()
        db.change { Items.upsert(ada) }
        db.change { Items.upsert(alan) }
        val changes = db.read { tracker.pending(Items) }

        val removed = db.write { tracker.acknowledge(changes.take(1)) }

        assertEquals(1, removed)
        assertEquals(changes.drop(1), db.read { tracker.pending(Items) })
        assertEquals(setOf(ada, alan), db.read { Items.list<Item>() }.toSet())
        assertEquals(0, db.write { tracker.acknowledge(emptyList()) })
    }

    @Test
    fun `delete of a missing key records nothing`() = runTest {
        val (db, tracker) = open()

        var deleted = true
        db.change { deleted = Items.delete("missing") }

        assertFalse(deleted)
        assertTrue(db.read { tracker.pending(Items).isEmpty() })
    }

    @Test
    fun `reads are forwarded through the collection and its indexes`() = runTest {
        val (db, _) = open()
        db.change { Items.upsert(ada) }
        db.change { Items.upsert(alan) }

        assertEquals(2, db.read { Items.count() })
        assertEquals(setOf(ada, alan), db.read { Items.list<Item>() }.toSet())
        assertEquals(ada, db.read { Items.byId(ada.id).get<Item>() })
        assertEquals(ada, db.read { Items.byEmail(ada.email).get<Item>() })
        assertEquals(listOf(ada), db.read { Items.byAge(ada.age).list<Item>() })
        assertEquals(1, db.read { Items.byAge(ada.age).count() })
        val seen = mutableListOf<String>()
        db.read { Items.forEach<Item> { seen += it.id } }
        assertEquals(setOf(ada.id, alan.id), seen.toSet())
        assertEquals(ada, with(db) { Items.byId(ada.id).observe<Item>().first() })
        assertEquals(listOf(ada), with(db) { Items.byAge(ada.age).observe<Item>().first() })
    }

    @Test
    fun `observePending emits the log and drops acknowledged changes`() = runTest {
        val (db, tracker) = open()
        val seen = collect(tracker.observePending(Items))

        db.change { Items.upsert(ada) }
        db.write { tracker.acknowledge(tracker.pending(Items)) }

        assertEquals(listOf(0, 1, 0), seen.map { it.size })
        assertEquals(encoded(ada), seen[1].single().changeSet)
    }

    @Test
    fun `a tracked collection without a primary key fails when declared`() {
        val failure = assertFailsWith<IllegalStateException> {
            object : TrackedCollection("bare") {
                // no primary key
            }.let { bare -> NoSqlite { +bare } }
        }

        assertTrue("no primary key" in failure.message!!)
    }

    @Test
    fun `a second primary key fails immediately`() {
        val failure = assertFailsWith<IllegalStateException> {
            object : TrackedCollection("twice") {
                val first = primaryKey("id")
                val second = primaryKey("email")
            }
        }

        assertTrue("already has a primary key" in failure.message!!)
    }

    private fun <T> TestScope.collect(flow: Flow<T>): List<T> {
        val values = mutableListOf<T>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.toList(values) }
        return values
    }
}

private data class Opened(val db: NoSqlite, val tracker: ChangeTracker)

private class ManualClock(var instant: Instant) : Clock {
    override fun now(): Instant = instant
}

private inline fun <reified T> encoded(value: T): JsonObject =
    NoSqlite.Json.encodeToJsonElement(value) as JsonObject

@Serializable
internal data class Item(val id: String, val email: String, val name: String, val age: Int)

@Serializable
internal data class Meta(val id: String)

@Serializable
internal data class Goods(val meta: Meta, val name: String)

private object Items : TrackedCollection("items") {
    val byId = primaryKey(Item::id)
    val byEmail = unique(Item::email)
    val byAge = index(Item::age)
}

private object Stock : TrackedCollection("stock") {
    val byId = primaryKey(Goods::meta / Meta::id)
}

private object Notes : TrackedCollection("notes") {
    val byId = primaryKey("id")
}
