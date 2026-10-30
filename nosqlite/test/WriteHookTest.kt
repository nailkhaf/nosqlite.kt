package io.github.nailkhaf.nosqlite

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Every public write reports the documents it changed. A collection without a hook is unchanged. */
@OptIn(ExperimentalWriteHook::class)
internal class WriteHookTest {

    @Serializable
    data class Doc(val id: String, val name: String, val age: Int)

    private class Watched(private val events: MutableList<String>) : Collection("watched") {
        override val id = primaryKey("id")
        val byAge = index("age")

        init {
            intercept(object : WriteInterceptor {
                context(_: WriteScope)
                override suspend fun insert(document: JsonObject) {
                    events += "insert ${text(document, "id")}"
                }

                context(_: WriteScope)
                override suspend fun update(before: JsonObject, after: JsonObject) {
                    events += "update ${text(before, "id")} ${text(after, "name")}"
                }

                context(_: WriteScope)
                override suspend fun delete(document: JsonObject) {
                    events += "delete ${text(document, "id")}"
                }
            })
        }
    }

    @Test
    fun `every public write reaches the interceptor`() = runTest {
        val events = mutableListOf<String>()
        val docs = Watched(events)
        val db = NoSqlite { +docs }
        val ann = Doc("a", "ann", 1)
        val bob = Doc("b", "bob", 2)

        db.write {
            docs.insert(ann)
            docs.insert(Doc.serializer(), bob)
            docs.update(ann.copy(name = "Ann")) { "id" eq "a" }
            docs.update(Doc.serializer(), bob.copy(name = "Bob")) { "id" eq "b" }
            docs.replace(ann.copy(name = "ANN")) { "id" eq "a" }
            docs.replace(Doc.serializer(), bob.copy(name = "BOB")) { "id" eq "b" }
            docs.byAge(1).update(buildJsonObject { put("name", "ann2") })
            docs.byAge(2).update(JsonObject.serializer(), buildJsonObject { put("name", "bob2") })
            docs.byAge(1).replace(ann.copy(name = "ann3"))
            docs.byAge(2).replace(Doc.serializer(), bob.copy(name = "bob3"))
            docs.byId("a").update(ann.copy(name = "ann4"))
            docs.byId("a").update(Doc.serializer(), ann.copy(name = "ann5"))
            docs.byId("a").replace(ann.copy(name = "ann6"))
            docs.byId("b").replace(Doc.serializer(), bob.copy(name = "bob6"))
            docs.byId.upsert(ann.copy(name = "ann6", age = 9))
            docs.byId.upsert(Doc.serializer(), Doc("c", "cy", 3))
            docs.byAge(9).delete()
            docs.byId("b").delete()
            docs.delete { "id" eq "c" }
        }

        assertEquals(
            listOf(
                "insert a",
                "insert b",
                "update a Ann",
                "update b Bob",
                "update a ANN",
                "update b BOB",
                "update a ann2",
                "update b bob2",
                "update a ann3",
                "update b bob3",
                "update a ann4",
                "update a ann5",
                "update a ann6",
                "update b bob6",
                "update a ann6",
                "insert c",
                "delete a",
                "delete b",
                "delete c",
            ),
            events,
        )
        assertTrue(db.read { docs.count() } == 0L)
    }

}

private fun text(document: JsonObject, key: String): String = document[key].toString().trim('"')
