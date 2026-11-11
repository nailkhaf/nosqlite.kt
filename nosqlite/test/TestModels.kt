package io.github.nailkhaf.nosqlite

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline
import kotlin.uuid.Uuid

/** The documents the tests store. */
@Serializable
internal data class Name(val first: String, val last: String)

@Serializable
internal enum class Role {
    Admin,

    @SerialName("member")
    Member,
}

@Serializable
@JvmInline
internal value class Email(val value: String)

@Serializable
internal data class User(
    val id: String,
    val email: Email,
    val name: Name,
    val age: Int,
    val role: Role = Role.Member,
    val active: Boolean = true,
    val score: Double = 0.0,
    val tags: List<String> = emptyList(),
    val nickname: String? = null,
)

/**
 * A canonical [Uuid] string for [label]. Labels tests sort against each other have a fixed order:
 * ada < alan < byron < grace < cid. Any other label hashes to its own UUID.
 */
internal fun uuid(label: String): String {
    val text = LABEL_IDS[label]?.let { n ->
        "00000000-0000-4000-8000-" + n.toString(16).padStart(12, '0')
    } ?: run {
        val hex = fnv1a64(label) + fnv1a64(".$label")
        hex.substring(0, 8) + "-" +
            hex.substring(8, 12) + "-" +
            "4" + hex.substring(13, 16) + "-" +
            "8" + hex.substring(17, 20) + "-" +
            hex.substring(20, 32)
    }
    check(Uuid.parse(text).toString() == text) { "Not a canonical UUID: $text" }
    return text
}

// Fixed so tests that order by id keep the order they were written for.
private val LABEL_IDS: Map<String, Int> = mapOf(
    "ada" to 0x10,
    "ada2" to 0x11,
    "ada3" to 0x12,
    "alan" to 0x20,
    "byron" to 0x30,
    "grace" to 0x40,
    "cid" to 0x50,
    "ann" to 0x60,
    "bob" to 0x61,
    "carol" to 0x62,
    "core" to 0x63,
    "dan" to 0x64,
    "eve" to 0x65,
    "fay" to 0x66,
    "gus" to 0x67,
    "kid" to 0x68,
    "linus" to 0x69,
    "lord" to 0x6a,
    "neo" to 0x6b,
    "nobody" to 0x6c,
    "note" to 0x6d,
    "note-2" to 0x6e,
    "other" to 0x6f,
    "x" to 0x70,
    "a" to 0x71,
    "b" to 0x72,
)

/** A [User] whose other fields follow from [id], so tests only spell out what they check. [id] is stored as a UUID. */
internal fun user(
    id: String,
    age: Int,
    first: String = id.replaceFirstChar { it.uppercase() },
    last: String = "Smith",
    role: Role = Role.Member,
    active: Boolean = true,
    score: Double = 0.0,
    tags: List<String> = emptyList(),
    nickname: String? = null,
): User = User(uuid(id), Email("$id@example.com"), Name(first, last), age, role, active, score, tags, nickname)
