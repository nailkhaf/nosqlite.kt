# AGENTS.md

nosqlite is a Kotlin Multiplatform document store on SQLite JSONB with kotlinx.serialization, published as
`io.github.nailkhaf:nosqlite`. It is an early 0.0.x library: the API and on-disk format may change. Every SQLite object it creates is named
with the prefix `nsq_`. This file is the
entry point for coding agents. README.md is the user guide, CONTRIBUTING.md covers build, CI and releasing.

## Layout

- `nosqlite/`: the library, the only published module. `src/` is common code (package `io.github.nailkhaf.nosqlite`),
  `test/` its common tests, which run on the JVM and the iOS simulator. `module.yaml` sets the platforms (jvm, android,
  iosArm64, iosSimulatorArm64), dependencies and `-Xexplicit-api=strict`.
- `samples/todo/`: a Compose Multiplatform sample app (`shared`, `androidApp`, `iosApp`, `jvmApp`). `shared` has
  `TaskRepositoryTest`.
- `project.yaml` lists the modules, `libs.versions.toml` the dependency versions, `publishing.module-template.yaml` the
  Maven Central coordinates and version.
- `.github/workflows/ci.yml` (PR and main checks), `.github/workflows/release.yml` (publishing on a `v*` tag).

## Build and test

The build tool is the Kotlin toolchain through the `./kotlin` wrapper (pinned 0.13.0). It downloads itself on first
use; there is no Gradle build file. Run from the repo root.

| You changed | Run | Where |
| --- | --- | --- |
| Anything in `nosqlite/` or `samples/` | `./kotlin test -p jvm` and `./kotlin build -p android` | Linux or macOS |
| While iterating on one area | `./kotlin test -p jvm -m nosqlite --include-classes '<fully.qualified.TestClass>'` | Linux or macOS |
| iOS-specific code | `./kotlin test -p iosSimulatorArm64` on macOS, otherwise rely on the CI "iOS tests" job | macOS with Xcode |
| Only docs (`*.md`) | nothing | |

`./kotlin test -p jvm` runs the library tests and the sample's `TaskRepositoryTest` (about 25 s once the toolchain is
cached). `./kotlin build -p android` compiles main and test sources for Android; Android tests are not run anywhere,
because the bundled SQLite native library can't load on the host JVM and the toolchain has no device runner yet.

There is no lint or formatter step. Style follows `.editorconfig` (4 spaces, 120 columns, trailing commas allowed), and
explicit API mode makes the compiler reject public declarations without a visibility modifier.

## CI and protections

- CI runs three jobs on every non-draft PR and on pushes to `main`: "JVM tests", "Android build", "iOS tests"
  (macOS). Draft PRs skip every job; marking the PR ready for review runs them.
- `main` requires a pull request, all three checks green, and signed commits; force-push and deletion are blocked.
- `v*` tags require the three checks and signed commits, and can't be moved or deleted.

## Working rules

1. Before anything bigger than a small fix, post a short plan and wait for the maintainer's OK: files touched, approach, how
   the work splits into PRs. Stay inside the approved plan; if it has to change, say so before going further.
2. You may open PRs. Nothing merges without the maintainer's review. Never merge, approve or post reviews yourself.
3. Keep PRs small. Update a branch from `main` only when GitHub reports a conflict or the PR is about to merge, and do
   it by merging `main` in, never by rebasing or force-pushing.
4. Every commit is signed, merge commits included.
5. Every PR shows passing tests: the commands above that you ran and their result, and green CI on all three jobs.
6. Every PR description ends with a self-review note: what changed against the approved plan, deviations,
   anything risky, and anything skipped or left for later.
7. Keep the library lean:
   - no test-only code in `nosqlite/src/`; test helpers live in `nosqlite/test/` (see `TestFixtures.kt`);
   - no extra work on every write (per-write queries, allocations or checks) unless the plan says so;
   - no leftovers: dead code, unused parameters, commented-out code, debug output, stale docs;
   - keep new declarations `internal` unless they are meant to be public API.
8. When behavior changes, update README.md (and CONTRIBUTING.md for build or CI changes) in the same PR.
9. Don't touch releasing or publishing (`release.yml`, `publishing.module-template.yaml`, versions, tags, Maven
   Central) unless the maintainer asks.

## Definition of done

- The approved behavior is implemented, with tests in `nosqlite/test/` that fail without the change.
- The commands for what you changed pass locally, and all three CI jobs are green on the PR.
- README.md and CONTRIBUTING.md match the new behavior.
- The PR has the test evidence and the self-review note, and contains nothing unrelated.
