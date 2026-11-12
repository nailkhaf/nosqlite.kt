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
- Optional change tracking: a tracked collection appends each insert, update and delete to a queue

## Installation

nosqlite 0.0.1 is available on Maven Central as one Kotlin Multiplatform library. Change tracking is part of
that artifact. With the Kotlin toolchain, in `module.yaml`:

```yaml
dependencies:
  - io.github.nailkhaf:nosqlite:0.0.1
```

With Gradle:

```kotlin
// build.gradle.kts
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("io.github.nailkhaf:nosqlite:0.0.1")
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

val adaId = Uuid.random()
val alanId = Uuid.random()

db.write {
    Users.insert(User(adaId, "ada@example.com", Name("Ada", "Lovelace"), 36, listOf("kotlin")))
    Users.upsert(User(alanId, "alan@example.com", Name("Alan", "Turing"), 41))
    Users.id(alanId).update(User(alanId, "alan@example.com", Name("Alan", "Turing"), 42))
    Users.delete { User::age lt 18 }
}

db.read {
    val ada: User? = Users.id(adaId).get<User>()
    val raw: JsonObject? = Users.id(adaId).get<JsonObject>()
    val lovelaces: List<User> = Users.byName("Lovelace").list<User>()
    val adults: List<User> = Users.list<User> {
        where { (User::age gte 18) and (User::tags contains "kotlin") }
        orderBy {
            User::age.desc
            (User::name / Name::last).asc
        }
        limit(20)
    }
    val count: Long = Users.count { User::age gte 18 }
    Users.forEach<User> { user -> println(user) }
}

with(db) {
    Users.observe<User> { where { User::age gte 18 } }.collect { adults -> render(adults) }
    Users.id(adaId).observe<User>().collect { ada -> render(ada) }
}
```

## Guide

### Collections

A collection is a name and its indexes. Create one directly, `Collection("notes")`, or extend `Collection` to
declare indexes with `index(...)`. A database lists the collections it serves,
`NoSqlite("app.db") { +Users }`, and creates their tables and indexes on the first `read` or `write`.
A collection's table is `nsq_` plus its name. An index or trigger is `nsq_<collection>:` plus its suffix, so its
name cannot be a collection table's: tables and indexes share one namespace, and a collection name cannot contain `:`.
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
`index("nickname", where = { "active".isTrue() })`. Each index is named `nsq_<collection>:` plus a hash of its
definition, so a changed index is dropped and created again when the database opens, and a removed one dropped.

Every collection also has an index on `id`, named `nsq_<collection>:id`, which is not declared.
Calling an index looks documents up by its values.
It takes its leading fields, `null` matching a null or missing field, and finds any number: `list<T> { }`, `count()`,
`update(value)`, `replace(value)` and `delete()` return the documents or how many there were.

### Ids

Every document needs an `id`: a `kotlin.uuid.Uuid` string, stored as `Uuid.toString()`. A `Uuid` property serializes
to that string. SQLite checks it on every write. `id(value)` takes that `Uuid` and addresses the one document:
`get<T>()` returns it or `null`, and
`update(value)`, `replace(value)` and `delete()` report whether there was one. `observe` on it emits that document,
or `null` while there is none. `upsert(value)` inserts the document, or updates the one with its id as `update` does.
An id never changes: a write that would change it throws `NoSqliteException.ImmutableId`. A missing or non-UUID id
throws `NoSqliteException.InvalidId`. A second document with the same id throws `NoSqliteException.DuplicateId`.

### Writes

Inside `db.write { }`:

- `insert(value)` adds a document. It must encode to a JSON object whose `id` is `Uuid.toString()`.
- `update(value) { condition }` applies the value to every match as a JSON merge patch: its fields replace theirs,
  a `null` removes one, and fields it doesn't have are kept.
- `replace(value) { condition }` stores the value as the whole document of every match. The value must carry each
  match's id: an id is immutable.
- `delete { condition }` deletes every match.
- `upsert(value)` inserts the document, or merge-patches the one with its id.

`insert` returns nothing. The others return how many documents they touched, and the by-id forms of `update`,
`replace` and `delete` return whether there was one. A write commits when its block returns and rolls back when it
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
db.write { Users.id(ada.id).update(ada.copy(age = 37)) } // "plan" is still there
val upload: JsonObject? = db.read { Users.id(ada.id).get<JsonObject>() }
```

A merge patch replaces lists and merges maps like any object, so it never removes a field the value leaves out.
To drop a field removed from the class, a map key or a sealed subtype's old fields, `replace` the document.

### Reads and queries

Inside `db.read { }` or a write: `list<T> { }`, `count { condition }` and `forEach<T>(query) { action }`. A read sees
one snapshot. The query builder sets each part at most once:

```kotlin
Users.list<User> {
    where { ("age" gte 18) and not("name.last" eq "Smith") }
    orderBy {
        "age".desc
        "name.last".asc
    }
    limit(20)
    offset(40)
}
```

Conditions compare text, `Long` and `Double`: `eq`, `neq`, `gt`, `gte`, `lt`, `lte`, `isIn`, `contains` for an array
element, `like` for a pattern (`"title" like "%milk%"`, case-insensitive for ASCII), `isTrue()`, `isFalse()`,
`isNull()`, `isNotNull()`, combined with `and`, `or` and `not`. An integer literal adapts to `Long`, so
`"age" eq 18` and `"age" isIn listOf(1, 2)` compile. An `Int` variable needs `.toLong()`. `Float` has no overload,
because `toDouble()` is not exact and `0.1f` would not match the stored real `0.1`; write a fraction as a `Double`
(`"ratio" eq 0.1`). `Short`, `Byte` and other number types are the same. Other values compare as they are stored,
so an enum as its serial name (`"role" eq "member"`). A `Double` that is `NaN` or infinite is rejected when the
condition is built. The order of documents with equal sort keys is unspecified, so add `id` as the last
sort key when a stable order or offset pagination matters.

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

`Collection("contacts", tracked = true)` records every insert, update and delete in `nsq__pending_changes`, with
the document before and after the change. The document's id is its `id` field.

```kotlin
object Contacts : Collection("contacts", tracked = true)

val db = NoSqlite("app.db") { +Contacts }

val changes = db.read { pendingChanges() }
sync(changes)
db.write { removePendingChanges(changes) }

db.write(untracked = true) { import(contacts) }
```

`pendingChanges()` returns the queue ordered by id. Read it whenever sync
runs, for example after `observe` on a tracked collection emits. `removePendingChanges` deletes exactly the given
rows, so a change written after they were read stays. Each row is timestamped with SQLite's clock, in milliseconds.
`write(untracked = true)` records nothing and does not nest inside another write.
The queue is append-only. A collection that is not tracked records nothing. Rows queued before a collection stopped
being tracked stay readable until they are removed. Every collection, tracked or not, keeps its id immutable.

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
