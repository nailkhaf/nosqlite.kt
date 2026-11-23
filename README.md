# nosqlite

A Kotlin Multiplatform document store on SQLite JSONB with kotlinx.serialization.

> [!WARNING]
> nosqlite is experimental, an early 0.0.x library, and not ready for production. The public API and the on-disk
> storage format may change between releases, with no migration path. Don't store data in it that you can't afford
> to lose.

Documents are JSON objects stored as JSONB, one STRICT table per collection. Each read decodes them into the
`@Serializable` class it asks for, and an update is a JSON merge patch, so fields the app doesn't know yet, such as
ones a newer backend sent, survive it. Fields are named by property references
(`User::age`, `User::name / Name::last`) or by their JSON keys (`"age"`, `"name.last"`), and a document is addressed
by its `id`.

- JVM, Android and iOS (arm64, simulator arm64)
- One writer and several `query_only` readers in WAL mode; read and write transactions with nested savepoints
- Composite and partial indexes on JSON fields, synced when the database opens, plus an index on every document's id
- Queries with filters, array `contains`, ordering, `limit` and `offset`; lookups by index and by id
- Streaming with an open cursor (`forEach`)
- `observe` flows that read again after each committed write to the collection
- Optional change tracking: a tracked collection appends each insert, update and delete to a queue, which can be observed

## Installation

nosqlite 0.0.2 is available on Maven Central as one Kotlin Multiplatform library. Change tracking is part of
that artifact. With the Kotlin toolchain, in `module.yaml`:

```yaml
dependencies:
  - io.github.nailkhaf:nosqlite:0.0.2
```

With Gradle:

```kotlin
// build.gradle.kts
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("io.github.nailkhaf:nosqlite:0.0.2")
        }
    }
}
```

Documents are encoded with kotlinx.serialization. The library exports `kotlinx-serialization-json`, and the compiler
plugin still has to be enabled (`settings.kotlin.serialization: json` with the Kotlin toolchain, `plugin.serialization`
with Gradle).

## Example

```kotlin
import io.github.nailkhaf.nosqlite.*
import io.github.nailkhaf.nosqlite.Collection // kotlin.collections.Collection wins over a star import
import kotlin.uuid.Uuid

@Serializable
data class Name(val first: String, val last: String)

@Serializable
data class User(val id: Uuid, val email: String, val name: Name, val age: Int, val tags: List<String> = emptyList())

object Users : Collection("users") {
    val byAge = index(User::age)
    val byName = index(User::name / Name::last, User::name / Name::first)
}

val db = NoSqlite("app.db") { +Users }

val adaId = Uuid.generateV7()
val alanId = Uuid.generateV7()

db.write {
    Users.insert(User(adaId, "ada@example.com", Name("Ada", "Lovelace"), 36, listOf("kotlin")))
    Users.upsert(User(alanId, "alan@example.com", Name("Alan", "Turing"), 41))
    Users.byId(alanId).update(User(alanId, "alan@example.com", Name("Alan", "Turing"), 42))
    Users.delete { User::age lt 18 }
}

db.read {
    val ada: User? = Users.byId(adaId).get<User>()
    val raw: JsonObject? = Users.byId(adaId).get<JsonObject>()
    val adas: List<User> = Users.byName("Lovelace", "Ada").list<User>()
    val adults: List<User> = Users.list<User> {
        where { (Users.byAge gte 18) and (User::tags contains "kotlin") }
        orderBy { Users.byAge.desc }
        limit(20)
    }
    val count: Long = Users.count { User::age gte 18 }
    Users.forEach<User> { user -> println(user) }
}

with(db) {
    Users.observe<User> { where { User::age gte 18 } }.collect { adults -> render(adults) }
    Users.byId(adaId).observe<User>().collect { ada -> render(ada) }
}
```

## Guide

### Collections

A collection is a name and its indexes. Declare each one as an object extending `Collection`, `object Notes :
Collection("notes")`, and declare its indexes there with `index(...)`. A database lists the collections it serves,
`NoSqlite("app.db") { +Users }`, and creates their tables and indexes on the first `read` or `write`.
A collection's table is `nsq_<collection>`, its indexes are `nsq_idx_<collection>_` plus a suffix, and its triggers
are `nsq_<collection>_` plus a suffix. Collection names can't start with `idx`.
Using a collection it doesn't list fails with `NoSqliteException.CollectionNotDeclared`.
A collection's name is permanent. Opening a database without listing a collection leaves that collection's table
and documents in place.

`NoSqlite()` without a path is in memory. `NoSqlite(location, driver, dispatcher) { }` takes a `Location.File` with
its number of readers, an `SQLiteDriver` and the dispatcher connections run on, `Dispatchers.IO` by default.
The driver's SQLite must be 3.45 or newer for JSONB; the default `BundledSQLiteDriver` ships 3.50, and an older one
fails to open with `NoSqliteException.UnsupportedSqlite`.
`db.close()` closes its connections; `db.delete()` closes them and deletes the file with its WAL sidecars.

### Fields

A field is its JSON key, as stored: `"age"`, `"name.last"` for a nested object, and the serial name of a property
renamed with `@SerialName`. Keys are identifiers.

### Indexes

`index("age")` takes one field or several, in order, and an optional `where` that makes the index partial:
`index("nickname", where = { "active".isTrue() })`. Each index is named `nsq_idx_<collection>_` plus a hash of its
definition, so a changed index is dropped and created again when the database opens, and a removed one dropped.

Calling an index looks documents up by its values: one per field, in order, `null` matching a null or missing field.
Fewer or more values throw. It finds any number: `list<T> { }`,
`count { condition }`, `update(value)` and `delete()` return the documents or how many there were, and
`forEach<T> { action }` streams them, as on a collection.

An index also filters and sorts in a query, and SQLite reads it for both:

```kotlin
Users.list<User> {
    where { (Users.byAge gte 18) and Users.byName.gt("Lovelace", "Ada") }
    orderBy { Users.byName.asc }
}
```

In `where`, calling an index, `Users.byAge(36)`, matches as its lookup does. A one-field index takes `eq`, `gt`,
`gte`, `lt` and `lte`. A multi-field index takes `gt`, `gte`, `lt` and `lte` with one value per field, compared as a
whole, so `Users.byName.gt("Lovelace", "Ada")` matches Lovelace Byron and Turing Alan: the next page after a
document. A partial index's own condition holds too. In `orderBy`, `Users.byName.asc` sorts by every field of the
index. `Users.byId` is the collection's own key: `Users.byId.desc` sorts by id alone, newest first.

### Ids

Every document needs an `id`: a `kotlin.uuid.Uuid` string, stored as `Uuid.toString()`. A `Uuid` property serializes
to that string. SQLite checks it on every write. Create ids with `Uuid.generateV7()`: v7 ids start with their
creation time, so id order is creation order, oldest first (see Reads and queries). Any UUID works, but
`Uuid.random()` (v4) ids sort in no meaningful order. `generateV7()` needs
`@OptIn(ExperimentalUuidApi::class)`. `Users.byId(value)` takes that `Uuid` and addresses the one document:
`get<T>()` returns it or `null`, and
`update(value)` and `delete()` report whether there was one. `observe` on it emits that document, or `null` while
there is none. `upsert(value)` inserts the document, or updates the one with its id as `update` does.
`update` and `upsert` take `merge = false` to store the value as the whole document instead of merging it.
A condition on `id` with a UUID string, such as `"id" gt lastId.toString()`, searches the table's key.
An id never changes: a write that would change it throws `NoSqliteException.ImmutableId`. A missing or non-UUID id
throws `NoSqliteException.InvalidId`. A second document with the same id throws `NoSqliteException.DuplicateId`.

### Writes

Inside `db.write { }`:

- `insert(value)` adds a document. It must encode to a JSON object whose `id` is `Uuid.toString()`.
- `update(value) { condition }` applies the value to every match as a JSON merge patch: its fields replace theirs,
  a `null` removes one, and fields it doesn't have are kept. `update(value, merge = false) { condition }` stores the
  value as the whole document of every match instead; the value must carry each match's id, since an id is immutable.
- `delete { condition }` deletes every match.
- `upsert(value)` inserts the document, or updates the one with its id as `update` does, `merge = false` included.

To change a few fields without the whole class, pass a `JsonObject` as the patch:
`Users.byId(adaId).update(buildJsonObject { put("age", 37) })`.

`insert` and `upsert` return nothing. The others return how many documents they touched, and the by-id forms of
`update` and `delete` return whether there was one. A write commits when its block returns and rolls back when it
throws; a nested `write` is a savepoint that rolls back alone. A duplicate id throws `NoSqliteException.DuplicateId`,
and a changed id throws `NoSqliteException.ImmutableId`; caught inside the write, the transaction goes on. When another
`NoSqlite` or process keeps the same
file locked for over 5 seconds, the operation throws `NoSqliteException.Busy` and can be retried; one `NoSqlite` per
file never hits it.

### Transactions

A `read` or `write` block runs on one connection and must stay sequential. Don't use the database from coroutines
launched inside it (`launch`, `async`, parallel flow operators, etc.) concurrently: that is unsupported and can
crash. Sequential suspension inside the block is fine.

Do parallel work outside the transaction, so each coroutine opens its own `read` or `write`, or gather results in
parallel first and write them in one sequential block.

### Types and forward compatibility

A collection holds JSON, not a type: every read, lookup and `observe` names the type it decodes to, `list<User>()`,
`get<UserSummary>()` or `list<JsonObject>()` for the raw documents, and each takes a `KSerializer` instead, as in
`list(User.serializer())`. Unknown keys are ignored, so a smaller class reads a projection; a document missing a field
the class requires fails with `NoSqliteException.DecodingFailed`.

`update` and `upsert` apply the value as a JSON merge patch, so the fields it doesn't have are kept, in nested objects
too. An app can store what a newer backend sent, update it through its own class, and send it back with the fields
it doesn't know yet:

```kotlin
db.write { Users.insert(downloaded) } // a JsonObject with a "plan" field User doesn't have
db.write { Users.byId(ada.id).update(ada.copy(age = 37)) } // "plan" is still there
val upload: JsonObject? = db.read { Users.byId(ada.id).get<JsonObject>() }
```

A merge patch replaces lists and merges maps like any object, so it never removes a field the value leaves out.
To drop a field removed from the class, a map key or a sealed subtype's old fields, update the document with
`merge = false`.

### Reads and queries

Inside `db.read { }` or a write: `list<T> { }`, `count { condition }` and `forEach<T>(query) { action }`. A read sees
one snapshot. The query builder sets each part at most once:

```kotlin
Users.list<User> {
    where { ("age" gte 18) and not("name.last" eq "Smith") }
    orderBy { Users.byAge.desc }
    limit(20)
    offset(40)
}
```

Conditions compare text, `Int`, `Long` and `Double`: `eq`, `neq`, `gt`, `gte`, `lt`, `lte`, `isIn`, `contains` for an array
element, `like` for a pattern (`"title" like "%milk%"`, case-insensitive for ASCII), `isTrue()`, `isFalse()`,
`isNull()`, `isNotNull()`, combined with `and`, `or` and `not`. `isIn` takes `Long` values, so `"age" isIn listOf(1, 2)` compiles and a
`List<Int>` needs `map(Int::toLong)`. `Float` has no overload,
because `toDouble()` is not exact and `0.1f` would not match the stored real `0.1`; write a fraction as a `Double`
(`"ratio" eq 0.1`). `Short`, `Byte` and other number types are the same. Other values compare as they are stored,
so an enum as its serial name (`"role" eq "member"`). A `Double` that is `NaN` or infinite is rejected when the
condition is built.

A collection's table is keyed by the id, so documents are stored in id order, and SQLite ends every index entry with
the id. A query without `orderBy` reads documents in id order, oldest first with v7 ids, at no cost, and an index
lookup with every field returns its matches in id order. A condition SQLite answers from an index by range or by
several values, such as `Users.byAge gt 18` or `User::age isIn listOf(36, 41)`, can return them by that field, then
id. SQL itself promises no order without `ORDER BY`; use `orderBy { Users.byId.asc }` where the order must be
guaranteed, which the key makes free.

`orderBy` takes an index, `Users.byAge.desc`, or fields, `"age".desc` or `User::age.desc`. Nothing is added to the
sort. Prefer an index: SQLite reads it in order, so the sort costs nothing, and documents with equal keys come in id
order, newest first when it reads the index backwards for `desc`. A field sort reads an index only when one starts
with those fields; otherwise SQLite sorts in memory and keeps equal keys in the order it read them, id order.

`forEach` reads one row at a time from an open cursor, so a large result never sits in memory, and its action can
run other queries in that same transaction, one at a time.

### Property references

Fields can also be named by property references, which the compiler checks: `User::age gte 18`,
`User::name / Name::last` for a nested field, `User::age.asc` in `orderBy`, and `index(User::name / Name::last)` in a
collection. A property reference names the field by its property name, so a
property renamed with `@SerialName` is named by its string instead. The document id is the JSON key `id`, so a property
that serializes under another name is not the id.
A comparison accepts a value of the property's type when that type is an SQL scalar: `String`, `Int`, `Long`,
`Double` or `Boolean` (a boolean compares as 1 and 0). `User::age gte 18` compiles. `User::age gte "18"` does not.
Null is `isNull()` or `isNotNull()`, including on a nullable property; the other operators take a non-null value.
An enum, `Float`, `Char`, unsigned type, value class or other type has no overload, so name that field by its
string key. Sorting and declaring an index take any property. Each typed call builds the same condition, sort key
or index as its string form, so the two mix freely.

### Observing

`observe` runs in a `NoSqlite` context and returns a `Flow` that reads now and again after every committed write
that changed the collection. Writes that land while it reads are folded into one more read, and a result equal to
the last one isn't emitted. Changes are tracked per collection.

### Change tracking

`Collection("contacts", tracked = true)` records every insert, update and delete in `nsq__pending_changes`.
Each row has an `id`, the document before the change, and the document after it. `id` is 16 random bytes, read as a
`Uuid`, assigned when the change is recorded and never changed, so a retried upload writes the same remote document.
An insert has no before. A delete has no after. An update has both. `documentId` is the `id` of after, or of before when after is null, as a
`Uuid`. An update that does not change the stored document records nothing.

```kotlin
object Contacts : Collection("contacts", tracked = true)

val db = NoSqlite("app.db") { +Contacts }

val changes = db.read { pendingChanges() }
sync(changes)
db.write { removePendingChanges(changes) }

db.write(untracked = true) {
    upsert("contacts", incoming, merge = false)
    delete("contacts", removedId)
}
```

`pendingChanges(limit, offset)` returns the queue in the order the changes were recorded, all of it by default.
`observePendingChanges(limit, offset)` runs in a `NoSqlite` context and returns a `Flow` that reads it now and again
after every committed write that changed a tracked collection or removed pending changes, folding and skipping reads
as `observe` does. `removePendingChanges` deletes exactly the given rows, so a change written after they were read
stays.

```kotlin
with(db) {
    observePendingChanges(limit = 100).filter { it.isNotEmpty() }.collect { changes ->
        sync(changes)
        db.write { removePendingChanges(changes) }
    }
}
```

Collect it with `collect`, not `collectLatest`, so a new change doesn't cancel an upload halfway; changes recorded
during the upload are read once it returns. Each row is timestamped with SQLite's clock, in milliseconds.
`write(untracked = true)` records nothing and does not nest inside another write. `upsert(name, document, merge)` and
`delete(name, id)` are `upsert` and `delete` addressed by the collection's name, for code that has only the name,
such as a replicator, usually in an untracked write so they are not recorded. With `merge = false` the document
replaces the stored one whole, so fields it leaves out are removed and `null` fields are kept.
A name the schema does not have gets its table and id trigger, and the schema keeps that name once the
write commits. `delete` of a collection that has no table does nothing. A typed write or a read whose table does not
exist still fails with `CollectionNotDeclared`.
The queue is append-only. A collection that is not tracked records nothing. Rows queued before a collection stopped
being tracked stay readable until they are removed. The id trigger is created with the table, so the id stays immutable.

## Sample

[`samples/todo`](samples/todo) is a Compose Multiplatform todo app for Android, iOS and the desktop JVM. The
`shared` module holds the UI, in plain Compose state, and `data.TaskRepository`, the only code that touches the
database; `androidApp`, `iosApp` and `jvmApp` each open the database file in their platform's place and show the UI.

```sh
./kotlin run -m jvmApp
./kotlin run -m androidApp   # a connected device or running emulator
./kotlin run -m iosApp       # macOS with Xcode, on a simulator
```

Building, testing and releasing are described in [CONTRIBUTING.md](CONTRIBUTING.md).

## License

[Apache License 2.0](LICENSE)
