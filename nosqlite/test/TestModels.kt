package io.github.nailkhaf.nosqlite

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

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
    val manager: Name? = null,
)

/** A [User] whose other fields follow from [id], so tests only spell out what they check. */
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
    manager: Name? = null,
): User = User(id, Email("$id@example.com"), Name(first, last), age, role, active, score, tags, nickname, manager)
