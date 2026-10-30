package io.github.nailkhaf.nosqlite.tracking

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

internal class DiffJsonMergePatchTest {
    @Test
    fun `identical objects return empty diff`() {
        val value = buildJsonObject { put("a", 1) }
        assertEquals(JsonObject(emptyMap()), diffJsonMergePatch(value, value))
    }

    @Test
    fun `modified scalar fields are included in diff`() {
        val before = buildJsonObject { put("a", 1); put("b", 2) }
        val after = buildJsonObject { put("a", 1); put("b", 3) }
        assertEquals(buildJsonObject { put("b", 3) }, diffJsonMergePatch(before, after))
    }

    @Test
    fun `added fields are included in diff`() {
        val before = buildJsonObject { put("a", 1) }
        val after = buildJsonObject { put("a", 1); put("b", 2) }
        assertEquals(buildJsonObject { put("b", 2) }, diffJsonMergePatch(before, after))
    }

    @Test
    fun `removed fields produce json null in diff`() {
        val before = buildJsonObject { put("a", 1); put("b", 2) }
        val after = buildJsonObject { put("a", 1) }
        assertEquals(buildJsonObject { put("b", JsonNull) }, diffJsonMergePatch(before, after))
    }

    @Test
    fun `nested object changes produce nested diff`() {
        val before = buildJsonObject { putJsonObject("meta") { put("id", "a"); put("n", 1) } }
        val after = buildJsonObject { putJsonObject("meta") { put("id", "a"); put("n", 2) } }
        assertEquals(
            buildJsonObject { putJsonObject("meta") { put("n", 2) } },
            diffJsonMergePatch(before, after),
        )
    }

    @Test
    fun `nested object with identical values produces empty diff`() {
        val value = buildJsonObject { putJsonObject("meta") { put("id", "a") } }
        assertEquals(JsonObject(emptyMap()), diffJsonMergePatch(value, value))
    }

    @Test
    fun `replacing nested object with primitive replaces value`() {
        val before = buildJsonObject { putJsonObject("meta") { put("id", "a") } }
        val after = buildJsonObject { put("meta", "a") }
        assertEquals(buildJsonObject { put("meta", "a") }, diffJsonMergePatch(before, after))
    }

    @Test
    fun `replacing primitive with nested object replaces value`() {
        val before = buildJsonObject { put("meta", "a") }
        val after = buildJsonObject { putJsonObject("meta") { put("id", "a") } }
        assertEquals(after, diffJsonMergePatch(before, after))
    }
}
