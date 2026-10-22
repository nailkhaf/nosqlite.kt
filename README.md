# nosqlite

Kotlin Multiplatform document store on SQLite JSONB with kotlinx.serialization.

> Work in progress: the API is being built up in small steps.

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
