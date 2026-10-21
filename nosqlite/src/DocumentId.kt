package io.github.nailkhaf.nosqlite

import kotlin.jvm.JvmInline

/** The id SQLite gives a document on insert: its table's `rowid`. It is never part of the document's JSON. */
@JvmInline
public value class DocumentId(public val value: Long) {
    override fun toString(): String = "DocumentId($value)"
}
