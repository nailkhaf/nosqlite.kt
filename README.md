# nosqlite

A Kotlin Multiplatform document store on SQLite JSONB with kotlinx.serialization.

Documents are `@Serializable` classes stored as JSONB, one STRICT table per collection. Fields are named by their
JSON keys (`"age"`, `"name.last"`), and documents are addressed by their unique indexes.

- JVM, Android and iOS (arm64, simulator arm64)
- One writer and several `query_only` readers in WAL mode; read and write transactions with nested savepoints
- Unique, composite and partial indexes on JSON fields, synced when the database opens
- Queries with filters, array `contains`, ordering, `limit` and `offset`; lookups and upserts by index
- Streaming with an open cursor (`forEach`)
- `observe` flows that read again after each committed write to the collection

## Example

```kotlin
import io.github.nailkhaf.nosqlite.*
import io.github.nailkhaf.nosqlite.Collection // kotlin.collections.Collection wins over a star import

@Serializable
data class Name(val first: String, val last: String)

@Serializable
data class User(val id: String, val email: String, val name: Name, val age: Int, val tags: List<String> = emptyList())

object Users : Collection<User>("users", User.serializer()) {
    val byId = unique("id")
    val byEmail = unique("email")
    val byAge = index("age")
    val byName = index("name.last", "name.first")
}

val db = Database("app.db") { +Users }

db.write {
    Users.insert(User("ada", "ada@example.com", Name("Ada", "Lovelace"), 36, listOf("kotlin")))
    Users.byId.upsert(User("alan", "alan@example.com", Name("Alan", "Turing"), 41))
    Users.byEmail("alan@example.com").update(User("alan", "alan@example.com", Name("Alan", "Turing"), 42))
    Users.delete { "age" lt 18 }
}

db.read {
    val ada: User? = Users.byId("ada").get()
    val lovelaces: List<User> = Users.byName("Lovelace").list()
    val adults: List<User> = Users.list {
        where { ("age" gte 18) and ("tags" contains "kotlin") }
        orderBy(desc("age"), asc("name.last"))
        limit(20)
    }
    val count: Long = Users.count { "age" gte 18 }
    Users.forEach { user -> println(user) }
}

with(db) {
    Users.observe { where { "age" gte 18 } }.collect { adults -> render(adults) }
    Users.byId("ada").observe().collect { ada -> render(ada) }
}
```

## Guide

### Collections

A collection is a name, a serializer and its indexes. Create one directly, `Collection("notes", Note.serializer())`,
or extend `Collection` to declare indexes with `unique(...)` and `index(...)`. A database lists the collections it
serves, `Database("app.db") { +Users }`, and creates their tables and indexes on the first `read` or `write`.
Using a collection it doesn't list fails with `NosqliteException.CollectionNotDeclared`.

`Database()` without a path is in memory. `Database(location, driver, dispatcher) { }` takes a `Location.File` with
its number of readers, an `SQLiteDriver` and the dispatcher connections run on, `Dispatchers.IO` by default.

### Fields

A field is its JSON key, as stored: `"age"`, `"name.last"` for a nested object, and the serial name of a property
renamed with `@SerialName`. Keys are identifiers.

### Indexes

`unique("email")` and `index("age")` take one field or several, in order, and an optional `where` that makes the index
partial: `unique("nickname", where = { "active".isTrue() })`. Each index is named by a hash of its definition, so a
changed index is dropped and created again when the database opens, and a removed one dropped.

Calling an index looks documents up by its values. A unique index takes all its fields, none `null`, and finds at most
one document: `get()`, `update(value)` and `delete()` return the document or whether there was one. A plain index
takes its leading fields, `null` matching a null or missing field, and finds any number: `list { }`, `count()`,
`update(value)` and `delete()` return the documents or how many there were. `upsert(value)` on a unique index inserts
the document, or replaces the one with its values.

### Writes

Inside `db.write { }`: `insert(value)`, `update(value) { condition }` to replace every match, `delete { condition }`,
and on a `Collection<JsonObject>`, `patch(patch) { condition }` for a JSON merge patch. They return how many documents
they touched. A write commits when its block returns and rolls back when it throws; a nested `write` is a savepoint
that rolls back alone. A duplicate key throws `NosqliteException.UniqueViolation` naming the index; caught inside
the write, the transaction goes on.

### Reads and queries

Inside `db.read { }` or a write: `list { }`, `count { condition }` and `forEach(query) { action }`. A read sees one
snapshot. The query builder sets each part at most once:

```kotlin
Users.list {
    where { ("age" gte 18) and not("name.last" eq "Smith") }
    orderBy(desc("age"), asc("name.last"))
    limit(20)
    offset(40)
}
```

Conditions compare text and numbers: `eq`, `neq`, `gt`, `gte`, `lt`, `lte`, `isIn`, `contains` for an array element,
`isTrue()`, `isFalse()`, `isNull()`, `isNotNull()`, combined with `and`, `or` and `not`. Other values compare as
they are stored, so an enum as its serial name. Documents with equal sort keys keep insertion order.

`forEach` reads one row at a time from an open cursor, so a large result never sits in memory, and its action can
run other queries.

### Property references

Fields can also be named by property references, which the compiler checks: `User::age gte 18`,
`User::name / Name::last` for a nested field, `asc(User::age)` in `orderBy`, and `unique(User::email)` or
`index(User::name / Name::last)` in a collection. A property reference names the field by its property name, so an
index on a property renamed with `@SerialName` fails when the collection is created; name that field by its string.
Values must be of the property's type: `User::age gte 18` compiles, `User::age gte "18"` doesn't.

```kotlin
object Users : Collection<User>("users", User.serializer()) {
    val byEmail = unique(User::email)
    val byName = index(User::name / Name::last, User::name / Name::first)
}

Users.list {
    where { (User::age gte 18) and (User::tags contains "kotlin") }
    orderBy(desc(User::age), asc(User::name / Name::last))
}
```

### Observing

`observe` runs in a `Database` context and returns a `Flow` that reads now and again after every committed write
that changed the collection. Writes that land while it reads are folded into one more read, and a result equal to
the last one isn't emitted. Changes are tracked per collection.

## Build

The project uses the [Kotlin toolchain](https://kotlin-toolchain.org/). The `kotlin` (macOS, Linux) and `kotlin.bat`
(Windows) scripts in the root are self-bootstrapping wrappers: they download the pinned toolchain on first use, so there
is nothing to install.

```sh
./kotlin test -p jvm
./kotlin test -p iosSimulatorArm64
```

## Platforms and CI

nosqlite targets the JVM, Android and iOS (arm64 and simulator arm64). Every pull request and push to `main` runs
three jobs:

| Job | Runner | What it checks |
| --- | --- | --- |
| JVM tests | Linux | `./kotlin test -p jvm`: the common tests on the JVM |
| Android build | Linux | `./kotlin build -p android`: main and test sources compile for Android |
| iOS tests | macOS | `./kotlin test -p iosSimulatorArm64`: the common tests on the iOS simulator |

Android is build-only for now. Kotlin toolchain 0.13.0 runs Android tests on the host JVM, and the Android artifact
of `androidx.sqlite:sqlite-bundled` ships `libsqliteJni` only for Android ABIs, so it can't load there; the
toolchain has no device or instrumented test runner yet. The same common tests run on the JVM and the iOS simulator.

The jobs cache the toolchain and its dependencies, and restore the build output of the latest `main` run so pull
requests compile incrementally; only `main` saves it.

## License

[Apache License 2.0](LICENSE)
