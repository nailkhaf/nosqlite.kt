@file:OptIn(org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class)

package io.github.nailkhaf.nosqlite.tracking

import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import java.io.OutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Insert, update, replace, where-delete and index writes are not on [TrackedCollection]. kotlin.test cannot assert
 * that, so these snippets compile against the library on the JVM.
 */
internal class TrackedWriteCompileTest {
    @Test
    fun `the forwarded reads and the two writes compile`() {
        val result = compile(SourceFile.kotlin("Accepted.kt", accepted))

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    }

    @Test
    fun `bulk writes and index writes do not compile`() {
        val files = rejected.map { (name, body) -> name to SourceFile.kotlin(name, probe(body)) }
        val result = compile(*files.map { it.second }.toTypedArray())

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        for ((name, _) in files) {
            assertTrue(name in result.messages, "Expected $name to fail\n${result.messages}")
        }
    }

    private fun compile(vararg sources: SourceFile): JvmCompilationResult =
        KotlinCompilation().apply {
            this.sources = sources.toList()
            inheritClassPath = true
            jvmTarget = "25"
            messageOutputStream = OutputStream.nullOutputStream()
        }.compile()

    private fun probe(body: String): String = """
        $header

        context(_: io.github.nailkhaf.nosqlite.WriteScope)
        @Suppress("unused")
        suspend fun rejected() {
            $body
        }
    """.trimIndent()

    private val rejected = listOf(
        "Insert.kt" to "Items.insert(obj())",
        "Update.kt" to """Items.update(obj()) { "id" eq "a" }""",
        "Replace.kt" to """Items.replace(obj()) { "id" eq "a" }""",
        "WhereDelete.kt" to """Items.delete { "id" eq "a" }""",
        "UniqueUpsert.kt" to "Items.byId.upsert(obj())",
        "UniqueUpdate.kt" to """Items.byId("a").update(obj())""",
        "UniqueDelete.kt" to """Items.byId("a").delete()""",
        "IndexUpdate.kt" to "Items.byAge(1).update(obj())",
        "IndexDelete.kt" to "Items.byAge(1).delete()",
        "NotACollection.kt" to "val wrong: io.github.nailkhaf.nosqlite.Collection = Items",
    )
}

private val header = """
    import io.github.nailkhaf.nosqlite.*
    import io.github.nailkhaf.nosqlite.tracking.*
    import kotlinx.serialization.json.JsonObject
    import kotlinx.serialization.json.buildJsonObject
    import kotlinx.serialization.json.put

    object Items : TrackedCollection("items") {
        val byId = primaryKey("id")
        val byEmail = unique("email")
        val byAge = index("age")
    }

    fun obj(): JsonObject = buildJsonObject {
        put("id", "a")
        put("email", "a@b.c")
        put("age", 1)
    }
""".trimIndent()

private val accepted = """
    $header

    @Suppress("unused")
    suspend fun accepted(db: NoSqlite, tracker: ChangeTracker) {
        val item = obj()
        db.write {
            Items.upsert(item)
            Items.delete("a")
            Items.delete(1)
            Items.delete(true)
            tracker.acknowledge(tracker.pending(Items))
        }
        db.read {
            Items.list<JsonObject> { where { "id" eq "a" } }
            Items.count()
            Items.forEach<JsonObject> { }
            Items.byId("a").get<JsonObject>()
            Items.byEmail("a@b.c").get<JsonObject>()
            Items.byAge(1).list<JsonObject>()
            Items.byAge(1).count()
        }
        with(db) {
            Items.observe<JsonObject>()
            Items.byId("a").observe<JsonObject>()
            Items.byEmail("a@b.c").observe<JsonObject>()
            Items.byAge(1).observe<JsonObject>()
            tracker.observePending(Items)
        }
        tracker.applyRemote {
            Items.upsert(item)
            Items.delete("a")
        }
    }
""".trimIndent()
