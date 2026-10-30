@file:OptIn(org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class)

package io.github.nailkhaf.nosqlite.tracking

import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import java.io.OutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Where-writes are extensions on Collection, so they do not resolve for a TrackedCollection. */
internal class BulkWriteCompileTest {

    @Test
    fun `where writes do not resolve on a tracked collection`() {
        val result = compile(SourceFile.kotlin("Rejected.kt", rejected))

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue("delete" in result.messages || "Unresolved" in result.messages, result.messages)
    }

    @Test
    fun `key writes resolve on a tracked collection`() {
        val result = compile(SourceFile.kotlin("Accepted.kt", accepted))

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    }

    private fun compile(vararg sources: SourceFile): JvmCompilationResult =
        KotlinCompilation().apply {
            this.sources = sources.toList()
            inheritClassPath = true
            jvmTarget = "25"
            messageOutputStream = OutputStream.nullOutputStream()
        }.compile()
}

private val rejected = """
    import io.github.nailkhaf.nosqlite.*
    import io.github.nailkhaf.nosqlite.tracking.TrackedCollection

    object Contacts : TrackedCollection("contacts") {
        override val id = primaryKey("id")
    }

    context(_: WriteScope)
    suspend fun bulk() {
        Contacts.delete { "id" eq "a" }
        Contacts.update("x") { "id" eq "a" }
        Contacts.replace("x") { "id" eq "a" }
    }
""".trimIndent()

private val accepted = """
    import io.github.nailkhaf.nosqlite.*
    import io.github.nailkhaf.nosqlite.tracking.TrackedCollection
    import kotlinx.serialization.json.buildJsonObject
    import kotlinx.serialization.json.put

    object Contacts : TrackedCollection("contacts") {
        override val id = primaryKey("id")
        val byAddress = unique("address")
    }

    context(_: WriteScope)
    suspend fun keys() {
        Contacts.insert(buildJsonObject { put("id", "a"); put("address", "a@x.com") })
        Contacts.byId("a").update(buildJsonObject { put("id", "a"); put("name", "Ada") })
        Contacts.byId("a").delete()
        Contacts.byAddress("a@x.com").delete()
        Contacts.byAddress.upsert(buildJsonObject { put("id", "a"); put("address", "a@x.com") })
    }
""".trimIndent()
