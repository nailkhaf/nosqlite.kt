package io.github.nailkhaf.nosqlite

import kotlinx.serialization.json.JsonObject

/** A write hook sees every document a collection writes. A plain collection has none. */
@RequiresOptIn("A write hook sees every document a collection writes. A plain collection has none.")
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
public annotation class ExperimentalWriteHook

/**
 * Called inside the write that changed the documents, once per document: [insert] with the stored document,
 * [update] with the document before and after a merge patch or replace, and [delete] with the document removed.
 * Installed with [BaseCollection.intercept], which is the opt-in entry point.
 */
public interface WriteInterceptor {
    context(_: WriteScope)
    public suspend fun insert(document: JsonObject)

    context(_: WriteScope)
    public suspend fun update(before: JsonObject, after: JsonObject)

    context(_: WriteScope)
    public suspend fun delete(document: JsonObject)
}
