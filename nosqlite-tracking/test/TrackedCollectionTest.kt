package io.github.nailkhaf.nosqlite.tracking

import io.github.nailkhaf.nosqlite.NoSqlite
import io.github.nailkhaf.nosqlite.NoSqliteException
import io.github.nailkhaf.nosqlite.PrimaryKey
import io.github.nailkhaf.nosqlite.count
import io.github.nailkhaf.nosqlite.delete
import io.github.nailkhaf.nosqlite.get
import io.github.nailkhaf.nosqlite.insert
import io.github.nailkhaf.nosqlite.invoke
import io.github.nailkhaf.nosqlite.read
import io.github.nailkhaf.nosqlite.update
import io.github.nailkhaf.nosqlite.write
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import io.github.nailkhaf.nosqlite.upsert
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

internal class TrackedCollectionTest {

    @Serializable
    data class Item(val id: String, val name: String)

    @Serializable
    data class Meta(val id: String)

    @Serializable
    data class Note(val meta: Meta, val text: String)

    object Items : TrackedCollection("items") {
        override val id: PrimaryKey = primaryKey(Item::id)
        val byName = unique(Item::name)
        val byAge = index("age")
    }

    object Notes : TrackedCollection("notes") {
        override val id: PrimaryKey = primaryKey("meta.id")
    }

    object Borrowed : TrackedCollection("borrowed") {
        override val id: PrimaryKey = Items.id
    }

    private val now = Instant.fromEpochSeconds(1_800_000_000)
    private val clock = object : Clock {
        override fun now(): Instant = now
    }

    private fun open(): Pair<NoSqlite, ChangeTracker> {
        val db = NoSqlite { +Items; +Notes }
        return db to ChangeTracker(db, clock)
    }

    @Test
    fun `pending changes are created with the tracked collection`() = runTest {
        val (db, _) = open()

        assertEquals(0, db.read { PendingChanges.count() })
    }

    @Test
    fun `a primary key from another collection fails when the database opens`() = runTest {
        val error = assertFailsWith<IllegalArgumentException> { NoSqlite { +Borrowed }.read { } }

        assertTrue(error.message!!.contains("borrowed"))
    }

    @Test
    fun `insert update and delete record the primary key`() = runTest {
        val (db, tracker) = open()
        val milk = Item("milk", "Milk")

        db.write { Items.insert(milk) }
        db.write { Items.byId("milk").update(milk.copy(name = "Oat milk")) }

        val (insert, update) = tracker.observePending(Items).first()
        assertEquals(PendingChange.Action.Insert, insert.action)
        assertEquals(JsonPrimitive("milk"), insert.changeSet["id"])
        assertEquals(JsonPrimitive("Milk"), insert.changeSet["name"])
        assertEquals(now, insert.timestamp)
        assertEquals(
            buildJsonObject { put("id", "milk"); put("name", "Oat milk") },
            update.changeSet,
        )
        assertEquals(PendingChange.Action.Update, update.action)
        assertEquals(Item("milk", "Oat milk"), db.read { Items.byId("milk").get<Item>() })
    }

    @Test
    fun `delete records only the primary key`() = runTest {
        val (db, tracker) = open()
        db.write { Items.insert(Item("milk", "Milk")) }

        db.write { Items.byId("milk").delete() }

        val delete = db.read { tracker.pending(Items) }.last()
        assertEquals(PendingChange.Action.Delete, delete.action)
        assertEquals(buildJsonObject { put("id", "milk") }, delete.changeSet)
    }

    @Test
    fun `a nested primary key is nested in the change set`() = runTest {
        val (db, tracker) = open()
        db.write { Notes.insert(Note(Meta("milk"), "buy")) }
        db.write { Notes.byId("milk").update(Note(Meta("milk"), "bought")) }
        db.write { Notes.byId("milk").delete() }

        val (insert, update, delete) = db.read { tracker.pending(Notes) }
        assertEquals(JsonPrimitive("milk"), insert.changeSet["meta"]!!.let { (it as JsonObject)["id"] })
        assertEquals(JsonPrimitive("buy"), insert.changeSet["text"])
        assertEquals(
            buildJsonObject {
                putJsonObject("meta") { put("id", "milk") }
                put("text", "bought")
            },
            update.changeSet,
        )
        assertEquals(buildJsonObject { putJsonObject("meta") { put("id", "milk") } }, delete.changeSet)
    }

    @Test
    fun `a bulk write is rejected`() = runTest {
        val (db, tracker) = open()
        db.write { Items.insert(Item("milk", "Milk")) }

        val error = assertFailsWith<IllegalArgumentException> { db.write { Items.byAge(1).delete() } }

        assertTrue(error.message!!.contains("bulk"))
        assertEquals(Item("milk", "Milk"), db.read { Items.byId("milk").get<Item>() })
        assertEquals(1, db.read { tracker.pending(Items) }.size)
    }

    @Test
    fun `applyRemote writes without recording`() = runTest {
        val (db, tracker) = open()

        tracker.applyRemote { Items.insert(Item("milk", "Milk")) }
        db.write { Items.byId("milk").update(Item("milk", "Oat")) }

        assertEquals(Item("milk", "Oat"), db.read { Items.byId("milk").get<Item>() })
        val change = db.read { tracker.pending(Items) }.single()
        assertEquals(PendingChange.Action.Update, change.action)
        tracker.applyRemote { Items.byId("milk").delete() }
        assertNull(db.read { Items.byId("milk").get<Item>() })
        assertEquals(1, db.read { tracker.pending(Items) }.size)
    }

    @Test
    fun `acknowledge drops changes and an unchanged update records nothing`() = runTest {
        val (db, tracker) = open()
        db.write {
            Items.insert(Item("milk", "Milk"))
            Items.insert(Item("bread", "Bread"))
        }
        db.write { Items.byId("milk").update(Item("milk", "Milk")) }
        val (milk, bread) = db.read { tracker.pending(Items) }

        assertEquals(1, db.write { tracker.acknowledge(listOf(milk)) })
        assertEquals(listOf(bread.id), db.read { tracker.pending(Items) }.map { it.id })
        assertEquals(0, db.write { tracker.acknowledge(emptyList()) })
        assertFalse(tracker.isEmpty())
    }

    @Test
    fun `a primary key change rolls back and records nothing new`() = runTest {
        val (db, tracker) = open()
        db.write { Items.insert(Item("milk", "Milk")) }

        assertFailsWith<NoSqliteException.PrimaryKeyChanged> {
            db.write { Items.byName.upsert(Item("oat", "Milk")) }
        }

        assertEquals(Item("milk", "Milk"), db.read { Items.byId("milk").get<Item>() })
        assertEquals(1, db.read { tracker.pending(Items) }.size)
        assertEquals(PendingChange.Action.Insert, db.read { tracker.pending(Items) }.single().action)
    }
}
