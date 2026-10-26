package io.github.nailkhaf.nosqlite


/** A value bound to a statement, or spelled out in SQL where SQLite allows no parameters. */
internal sealed interface SqlValue {
    data class Text(val value: String) : SqlValue
    data class Integer(val value: Long) : SqlValue
    data class Real(val value: Double) : SqlValue
    data object Null : SqlValue
}

/** The value as an SQL literal, for partial index conditions which can't have parameters. */
internal val SqlValue.literal: String
    get() = when (this) {
        is SqlValue.Text -> "'${value.replace("'", "''")}'"
        is SqlValue.Integer -> value.toString()
        is SqlValue.Real -> value.toString()
        SqlValue.Null -> "NULL"
    }

// SQL and its bound parameters travel together, so they can't drift apart.
internal data class Fragment(val sql: String, val params: List<SqlValue> = emptyList()) {
    companion object {
        val Empty = Fragment("")
    }
}

internal operator fun Fragment.plus(other: Fragment): Fragment = Fragment(sql + other.sql, params + other.params)

internal operator fun Fragment.plus(sql: String): Fragment = copy(sql = this.sql + sql)

internal fun List<Fragment>.joinToFragment(separator: String, prefix: String = "", postfix: String = ""): Fragment =
    Fragment(joinToString(separator, prefix, postfix) { it.sql }, flatMap { it.params })

internal fun <O> Fragment.statement(decode: suspend LockedStatement.() -> O): Statement<Unit, O> =
    Statement(sql, encode = { bind(params) }, decode = decode)

internal suspend fun LockedStatement.bind(params: List<SqlValue>, startIndex: Int = 1) =
    params.forEachIndexed { offset, param ->
        val index = startIndex + offset
        when (param) {
            is SqlValue.Text -> bindText(index, param.value)
            is SqlValue.Integer -> bindLong(index, param.value)
            is SqlValue.Real -> bindDouble(index, param.value)
            SqlValue.Null -> bindNull(index)
        }
    }
