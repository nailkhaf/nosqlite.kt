package io.github.nailkhaf.nosqlite

import kotlin.jvm.JvmInline

/** A document's `rowid`. It is never part of the document's JSON: documents are addressed by their unique keys. */
@JvmInline
internal value class DocumentId(val value: Long) {
    override fun toString(): String = "DocumentId($value)"
}
