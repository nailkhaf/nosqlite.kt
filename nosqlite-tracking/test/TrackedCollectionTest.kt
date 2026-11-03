package io.github.nailkhaf.nosqlite.tracking

import io.github.nailkhaf.nosqlite.NoSqlite
import io.github.nailkhaf.nosqlite.NoSqliteException
import io.github.nailkhaf.nosqlite.WriteScope
import io.github.nailkhaf.nosqlite.delete
import io.github.nailkhaf.nosqlite.get
import io.github.nailkhaf.nosqlite.insert
import io.github.nailkhaf.nosqlite.invoke
import io.github.nailkhaf.nosqlite.list
import io.github.nailkhaf.nosqlite.observe
import io.github.nailkhaf.nosqlite.read
import io.github.nailkhaf.nosqlite.replace
import io.github.nailkhaf.nosqlite.update
import io.github.nailkhaf.nosqlite.upsert
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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/** Covers one pending change per write, recorded in the same transaction as the document. */
internal class TrackedCollectionTest {
    private val ada = Item("ada", "ada@example.com", "Ada", 36)
    private val alan = Item("alan", "alan@example.com", "Alan", 41)

    private fun TestScope.open(): Opened {
        clock.instant = Instant.fromEpochMilliseconds(1_700_000_000_000)
        val db = NoSqlite(dispatcher = UnconfinedTestDispatcher(testScheduler)) {
            +Items
            +Stock
            +PendingChanges
        }
        return Opened(db, ChangeTracker(db))
    }

    private suspend fun Opened.change(block: suspend context(WriteScope) () -> Unit) {
        clock.instant = Instant.fromEpochMilliseconds(clock.instant.toEpochMilliseconds() + 1_000)
        db.write(block)
    }

    @Test
    fun `insert upsert update and replace each record one change set`() = runTest {
        val opened = open()
        val (db, tracker) = opened

        opened.change { Items.insert(ada) }
        opened.change { Items.id.upsert(ada.copy(name = "A.")) }
        opened.change { Items.byEmail(ada.email).update(buildJsonObject { put("age", 37) }) }
        opened.change { Items.id(ada.id).replace(ada.copy(name = "Ada", age = 37)) }

        val changes = db.read { tracker.pending(Items) }
        assertEquals(
            listOf(
                PendingChange.Action.Insert,
                PendingChange.Action.Upsert,
                PendingChange.Action.Update,
                PendingChange.Action.Replace,
            ),
            changes.map { it.action },
        )
        assertEquals(encoded(ada), changes[0].changeSet)
        assertEquals(encoded(ada.copy(name = "A.")), changes[1].changeSet)
        assertEquals(buildJsonObject { put("age", 37); put("id", "ada") }, changes[2].changeSet)
        assertEquals(encoded(ada.copy(name = "Ada", age = 37)), changes[3].changeSet)
        assertTrue(changes.all { it.collection == Items.name })
        assertEquals(clock.instant, changes.last().timestamp)
        assertEquals('7', changes.last().id.toString()[14])
        assertEquals(ada.copy(name = "Ada", age = 37), db.read { Items.id(ada.id).get<Item>() })
    }

    @Test
    fun `upsert keeps unknown fields and records the patch`() = runTest {
        val opened = open()
        val (db, tracker) = opened
        val original = buildJsonObject {
            put("id", "ada")
            put("email", "ada@example.com")
            put("name", "Ada")
            put("age", 36)
            put("extra", "keep")
        }
        val patch = buildJsonObject { put("id", "ada"); put("name", "A.") }

        opened.change { Items.id.upsert(original) }
        opened.change { Items.id.upsert(patch) }

        val stored = db.read { Items.byEmail("ada@example.com").get<JsonObject>() }!!
        assertEquals("keep", stored["extra"]!!.jsonPrimitive.content)
        assertEquals("A.", stored["name"]!!.jsonPrimitive.content)
        assertEquals(listOf(original, patch), db.read { tracker.pending(Items).map { it.changeSet } })
    }

    @Test
    fun `delete records only the primary key, including a delete through another index`() = runTest {
        val opened = open()
        val (db, tracker) = opened
        opened.change { Items.id.upsert(ada) }
        opened.change { Items.id.upsert(alan) }

        opened.change { Items.byEmail(ada.email).delete() }
        opened.change { Items.id(alan.id).delete() }

        val deletions = db.read { tracker.pending(Items) }.filter { it.action == PendingChange.Action.Delete }
        assertEquals(
            listOf(buildJsonObject { put("id", "ada") }, buildJsonObject { put("id", "alan") }),
            deletions.map { it.changeSet },
        )
        assertEquals(0, db.read { Items.list<Item>().size })
    }

    @Test
    fun `a nested primary key stays nested in the change set`() = runTest {
        val opened = open()
        val (db, tracker) = opened
        val milk = Goods(Meta("milk"), "Milk")

        opened.change { Stock.id.upsert(milk) }
        opened.change { Stock.id("milk").delete() }

        val changes = db.read { tracker.pending(Stock) }
        assertEquals(encoded(milk), changes[0].changeSet)
        assertEquals(buildJsonObject { put("meta", buildJsonObject { put("id", "milk") }) }, changes[1].changeSet)
    }

    @Test
    fun `a patch that changes the primary key is rejected`() = runTest {
        val opened = open()
        val (db, tracker) = opened
        opened.change { Items.id.upsert(ada) }

        val failure = assertFailsWith<IllegalArgumentException> {
            opened.change { Items.byEmail(ada.email).update(ada.copy(id = "other")) }
        }

        assertTrue("immutable" in failure.message!!)
        assertEquals(ada, db.read { Items.id(ada.id).get<Item>() })
        assertEquals(1, db.read { tracker.pending(Items).size })
    }

    @Test
    fun `a write and its change roll back together`() = runTest {
        val opened = open()
        val (db, tracker) = opened

        assertFailsWith<IllegalStateException> {
            opened.change {
                Items.id.upsert(ada)
                error("boom")
            }
        }

        assertEquals(0, db.read { Items.list<Item>().size })
        assertTrue(db.read { tracker.pending(Items).isEmpty() })
    }

    @Test
    fun `a nested write rolls back its own document and change`() = runTest {
        val opened = open()
        val (db, tracker) = opened

        opened.change {
            Items.id.upsert(ada)
            assertFailsWith<IllegalStateException> {
                db.write {
                    Items.id.upsert(alan)
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

        assertFailsWith<NoSqliteException.CollectionNotDeclared> {
            db.write { Items.insert(ada) }
        }

        assertEquals(0, db.read { Items.list<Item>().size })
    }

    @Test
    fun `untracked writes record nothing`() = runTest {
        val opened = open()
        val (db, tracker) = opened
        opened.change { Items.id.upsert(ada) }

        tracker.untracked {
            Items.byEmail(ada.email).update(buildJsonObject { put("name", "Patched") })
            Items.id("nobody").delete()
        }

        assertEquals("Patched", db.read { Items.id(ada.id).get<Item>() }!!.name)
        assertEquals(ada.email, db.read { Items.id(ada.id).get<Item>() }!!.email)
        assertEquals(listOf(encoded(ada)), db.read { tracker.pending(Items).map { it.changeSet } })
    }

    @Test
    fun `acknowledge removes the changes it is given`() = runTest {
        val opened = open()
        val (db, tracker) = opened
        opened.change { Items.id.upsert(ada) }
        opened.change { Items.id.upsert(alan) }
        val changes = db.read { tracker.pending(Items) }

        val removed = db.write { tracker.acknowledge(changes.take(1)) }

        assertEquals(1, removed)
        assertEquals(changes.drop(1), db.read { tracker.pending(Items) })
        assertEquals(setOf(ada, alan), db.read { Items.list<Item>() }.toSet())
        assertEquals(0, db.write { tracker.acknowledge(emptyList()) })
    }

    @Test
    fun `delete of a missing key records nothing`() = runTest {
        val opened = open()
        val (db, tracker) = opened

        assertEquals(false, opened.changeResult { Items.id("missing").delete() })
        assertTrue(db.read { tracker.pending(Items).isEmpty() })
        assertNull(db.read { Items.id("missing").get<Item>() })
    }

    @Test
    fun `reads go through the collection and its indexes`() = runTest {
        val opened = open()
        val (db, _) = opened
        opened.change { Items.id.upsert(ada) }
        opened.change { Items.id.upsert(alan) }

        assertEquals(setOf(ada, alan), db.read { Items.list<Item>() }.toSet())
        assertEquals(ada, db.read { Items.id(ada.id).get<Item>() })
        assertEquals(listOf(ada), db.read { Items.byAge(ada.age).list<Item>() })
        assertEquals(ada, with(db) { Items.id(ada.id).observe<Item>().first() })
    }

    @Test
    fun `observePending emits the log in id order and drops acknowledged changes`() = runTest {
        val opened = open()
        val (db, tracker) = opened
        val seen = collect(tracker.observePending(Items))

        opened.change { Items.id.upsert(ada) }
        db.write { tracker.acknowledge(tracker.pending(Items)) }

        assertEquals(listOf(0, 1, 0), seen.map { it.size })
        assertEquals(encoded(ada), seen[1].single().changeSet)
    }

    private fun <T> TestScope.collect(flow: Flow<T>): List<T> {
        val values = mutableListOf<T>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.toList(values) }
        return values
    }

    private suspend fun <T> Opened.changeResult(block: suspend context(WriteScope) () -> T): T {
        clock.instant = Instant.fromEpochMilliseconds(clock.instant.toEpochMilliseconds() + 1_000)
        return db.write(block)
    }
}

private data class Opened(val db: NoSqlite, val tracker: ChangeTracker)

private val clock = ManualClock(Instant.fromEpochMilliseconds(1_700_000_000_000))

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

private object Items : TrackedCollection("items", clock) {
    override val id = unique(Item::id)
    val byEmail = unique(Item::email)
    val byAge = index(Item::age)
}

private object Stock : TrackedCollection("stock", clock) {
    override val id = unique("meta.id")
}
