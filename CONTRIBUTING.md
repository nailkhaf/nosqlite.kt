# Contributing

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

## Releasing

Pushing a tag `v*` runs [`release.yml`](.github/workflows/release.yml). The tag must be `v` plus
`settings.publishing.version` in [`publishing.module-template.yaml`](publishing.module-template.yaml), so this
release is `v0.0.1`. The job tests the library on the JVM and the iOS simulator, then runs
`./kotlin publish -m nosqlite mavenCentral`. It runs in the `release` environment, whose secrets hold the Central
Portal token and the signing key, and it uploads the signed bundle without releasing it: check it in the
[Central Portal](https://central.sonatype.com/publishing/deployments), then publish it there.
