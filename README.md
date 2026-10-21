# nosqlite

A Kotlin Multiplatform document store on SQLite JSONB with kotlinx.serialization.

Documents are `@Serializable` classes stored as JSONB, one STRICT table per collection. Fields are named by
property references, so indexes, filters and sort keys are checked by the compiler, and index paths are checked
against the serial names when the collection is declared.

- JVM, Android and iOS (arm64, simulator arm64); see [Platforms and CI](#platforms-and-ci)
- One writer and several `query_only` readers in WAL mode; read and write transactions with nested savepoints
- Unique, composite and partial indexes on JSON fields, synced when the database opens
- A typed query DSL: filters, array `contains`, ordering, projections, `count`
- Streaming with an open cursor (`forEach`) and keyset pagination (`pages`)
- `observe` flows that re-read after each committed write to the collection

## Example

```kotlin
@Serializable
data class Name(val first: String, val last: String)

@Serializable
data class User(val id: String, val email: String, val name: Name, val age: Int, val tags: List<String> = emptyList())

object Users : Collection("users") {
    val byId = unique(User::id)
    val byEmail = unique(User::email)
    val byAge = index(User::age)
    val byName = index(User::name / Name::last, User::name / Name::first)
}

val db = Database("app.db") { +Users }

// Writes run in a transaction and return what they changed.
val id: DocumentId = db.write {
    Users.insert(User("u1", "ada@example.com", Name("Ada", "Lovelace"), 36, listOf("kotlin")))
}
db.write {
    Users.byEmail.patch("ada@example.com", buildJsonObject { put("age", 37) }) // JSON merge patch
    Users.delete { User::age lt 18 }                                           // affected rows
}

// Reads see one snapshot.
db.read {
    val ada: User? = Users[id]
    val same: User? = Users.byEmail["ada@example.com"]

    val adults = Users.select<User> {
        where { (User::age gte 18) and (User::tags contains "kotlin") }
        orderBy(User::age.desc)
    }
    adults.list(limit = 20)
    adults.count()
}

// The type argument is only the decode target: a smaller class reads a projection.
@Serializable
data class Summary(val id: String, val age: Int)

Users.select<Summary> { orderBy(User::age) }.pages(db, size = 50).collect { page -> show(page.items) }
Users.select<Summary>().observe(db, limit = 20).collect { render(it) }
Users.byEmail.observe<User>(db, "ada@example.com").collect { println(it) }
```

## Guide

### Collections and ids

A collection is an object with a name and its indexes; a database declares the collections it serves with
`Database(path) { +Users }`. Each collection is a table `(rowid INTEGER PRIMARY KEY, data BLOB NOT NULL) STRICT`.
`insert` returns the row's `DocumentId`; it is never written into the JSON, so documents keep their own ids, which a
unique index can enforce.

### Paths

`User::age` names a field, `User::name / Name::last` a nested one. Keys are the Kotlin property names; a property
renamed with `@SerialName` fails when its index is declared, with a message pointing to `path<V>("serial.name")`.

### Writes

Inside `db.write { }`: `insert<T>(value)`, and `update` (replace), `patch` (JSON merge patch) and `delete`, each by
`DocumentId`, by filter (`Users.delete { User::age lt 18 }`) or by unique key (`Users.byEmail.delete(email)`).
They return the number of affected documents. A nested `write` is a savepoint that rolls back alone. A duplicate key
throws `NosqliteException.UniqueViolation` naming the index; caught inside the write, the transaction goes on.

### Queries

`Collection.select<T> { where { }; orderBy() }` builds a query; `first()`, `list(limit)`, `count()` and `forEach`
run it inside `read { }` or `write { }`. Conditions: `eq`, `neq`, `gt`, `gte`, `lt`, `lte`, `isIn`, `isNull()`,
`contains` (array element), `and`, `or`, `not`. Values are encoded with their serializers, so enums, value classes and
`@SerialName` constants compare as stored. Ties in the order are broken by `rowid`.

### Streaming and observing

`forEach` reads one row at a time from an open cursor. `pages(db, size)` emits pages, each in its own short read,
continuing after the last row's sort keys, so writes between pages neither repeat nor skip rows. `observe` re-reads
after each committed write to the collection; writes made while it reads are folded into the next read, and equal
results are not emitted twice.

## Sample

[`samples/notes`](samples/notes/src/main.kt) uses every part of the API:

```sh
./kotlin run -m notes
```

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
| JVM tests | Linux | `./kotlin test -p jvm`: the common tests on the JVM; `./kotlin run -m notes`: the sample runs |
| Android build | Linux | `./kotlin build -p android`: main and test sources compile for Android |
| iOS tests | macOS | `./kotlin test -p iosSimulatorArm64`: the common tests on the iOS simulator |

Android is build-only for now. Kotlin toolchain 0.13.0 runs Android tests on the host JVM, and the Android artifact
of `androidx.sqlite:sqlite-bundled` ships `libsqliteJni` only for Android ABIs, so it can't load there; the
toolchain has no device or instrumented test runner yet. The same common tests run on the JVM and the iOS simulator.

The jobs cache the toolchain and its dependencies, and restore the build output of the latest `main` run so pull
requests compile incrementally; only `main` saves it.

## License

[Apache License 2.0](LICENSE)
