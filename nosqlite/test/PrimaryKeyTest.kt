package io.github.nailkhaf.nosqlite

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.uuid.Uuid

/** A primary key is one unique field, looked up and upserted like an index, and an update cannot change it. */
internal class PrimaryKeyTest {

    @Serializable
    data class Contact(val id: String, val address: String, val name: String)

    @Serializable
    data class Meta(val id: String)

    @Serializable
    data class Note(val meta: Meta, val text: String)

    @Serializable
    data class Measurement(val id: Long, val value: String)

    @Serializable
    data class Token(val id: Uuid, val label: String)

    object Contacts : Collection("contacts") {
        override val id = primaryKey(Contact::id)
        val byAddress = unique(Contact::address)
    }

    object Notes : Collection("notes") {
        override val id = primaryKey(Note::meta / Meta::id)
    }

    object Measurements : Collection("measurements") {
        override val id = primaryKey(Measurement::id)
    }

    object Tokens : Collection("tokens") {
        override val id = primaryKey(Token::id)
    }

    @Test
    fun `lookup and upsert use the primary key`() = runTest {
        val db = NoSqlite { +Contacts }
        val ada = Contact("ada", "ada@x.com", "Ada")

        db.write { Contacts.byId.upsert(ada) }
        db.write { Contacts.byId.upsert(ada.copy(name = "Augusta")) }

        assertEquals(ada.copy(name = "Augusta"), db.read { Contacts.byId("ada").get<Contact>() })
        assertEquals(1, db.read { Contacts.count() })
    }

    @Test
    fun `an update cannot change the primary key`() = runTest {
        val db = NoSqlite { +Contacts }
        val ada = Contact("ada", "ada@x.com", "Ada")
        db.write { Contacts.insert(ada) }

        val error = assertFailsWith<NoSqliteException.PrimaryKeyChanged> {
            db.write { Contacts.byId("ada").update(ada.copy(id = "alan")) }
        }

        assertEquals("id", error.field)
        assertEquals("contacts", error.collection)
        assertEquals(ada, db.read { Contacts.byId("ada").get<Contact>() })
        assertNull(db.read { Contacts.byId("alan").get<Contact>() })
    }

    @Test
    fun `upsert through another unique index cannot change the primary key`() = runTest {
        val db = NoSqlite { +Contacts }
        val ada = Contact("ada", "ada@x.com", "Ada")
        db.write { Contacts.insert(ada) }

        assertFailsWith<NoSqliteException.PrimaryKeyChanged> {
            db.write { Contacts.byAddress.upsert(ada.copy(id = "alan")) }
        }

        assertEquals(ada, db.read { Contacts.byAddress("ada@x.com").get<Contact>() })
    }

    @Test
    fun `a replace cannot change the primary key`() = runTest {
        val db = NoSqlite { +Contacts }
        db.write { Contacts.insert(Contact("ada", "ada@x.com", "Ada")) }

        assertFailsWith<NoSqliteException.PrimaryKeyChanged> {
            db.write { Contacts.replace(Contact("alan", "ada@x.com", "Ada")) { "address" eq "ada@x.com" } }
        }

        assertEquals("ada", db.read { Contacts.byAddress("ada@x.com").get<Contact>() }?.id)
    }

    @Test
    fun `insert without a primary key fails`() = runTest {
        val db = NoSqlite { +Contacts }

        assertFailsWith<NoSqliteException.PrimaryKeyChanged> {
            db.write {
                Contacts.insert(buildJsonObject {
                    put("address", "a@x.com")
                    put("name", "A")
                })
            }
        }
        assertEquals(0, db.read { Contacts.count() })
    }

    @Test
    fun `a nested primary key is looked up by its leaf`() = runTest {
        val db = NoSqlite { +Notes }
        val note = Note(Meta("milk"), "buy")
        db.write { Notes.insert(note) }

        assertEquals(note, db.read { Notes.byId("milk").get<Note>() })
        assertFailsWith<NoSqliteException.PrimaryKeyChanged> {
            db.write { Notes.byId("milk").update(note.copy(meta = Meta("bread"))) }
        }
        assertEquals(note, db.read { Notes.byId("milk").get<Note>() })
    }

    @Test
    fun `a long and a uuid are primary keys`() = runTest {
        val db = NoSqlite { +Measurements; +Tokens }
        val id = Uuid.parse("018f4f4a-7c2a-7000-8000-000000000001")
        db.write {
            Measurements.insert(Measurement(7, "m"))
            Tokens.insert(Token(id, "session"))
        }

        assertEquals(Measurement(7, "m"), db.read { Measurements.byId(7).get<Measurement>() })
        assertEquals(Token(id, "session"), db.read { Tokens.byId(id).get<Token>() })
    }
}
