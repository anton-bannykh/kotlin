[Kotlin/Native guide](../README.md) › build-and-tests

# Targets, toolchains and building

*Skip this page if you have built `kotlin-native/dist` and know what a target and a sysroot are. Reference material.*

What a target is, what the compiler needs to produce a binary for it, and how to build the compiler itself. The full
reference for building, testing and debugging is [HACKING.md](../../../kotlin-native/HACKING.md).

## Targets

A [**target**][targets] is the platform a binary is built for: operating system plus CPU, such as `linux_x64`, `macos_arm64`,
`ios_arm64`, `ios_simulator_arm64`, `mingw_x64`, `android_arm64`. In code it is a `KonanTarget`. The **host** is the machine
the compiler runs on. They differ when cross-compiling: `konanc -target linux_arm64` on a `linux_x64` host.

| Host | Can build for |
|---|---|
| `linux_x64`, `mingw_x64` | Linux, Windows (MinGW) and Android targets |
| `macos_arm64` | all of the above, plus macOS, iOS, tvOS and watchOS |

A klib made from ordinary Kotlin code is the same for every target. Everything in the second stage is per target: the
runtime, the caches, the platform libraries, the LLVM settings.

## Toolchains

To turn LLVM IR into a binary the compiler needs, for each target: LLVM itself, a [**sysroot**][cross] (the target's system
headers and libraries) and a linker. Which ones, and with which flags, is written in one file,
[konan.properties](../../../kotlin-native/konan/konan.properties), as `key.<target> = value` lines:

```properties
llvmHome.linux_x64 = $llvm.linux_x64.dev
targetTriple.linux_x64 = x86_64-unknown-linux-gnu
targetSysRoot.linux_x64 = $gccToolchain.linux_x64/x86_64-unknown-linux-gnu/sysroot
linkerKonanFlags.linux_x64 = -Bstatic -lstdc++ -Bdynamic -ldl -lm -lpthread ...
```

These are not expected to be installed on the machine. On first use the compiler downloads them into
`~/.konan/dependencies/` (for a Linux host: an LLVM build, a GCC toolchain with an old glibc as the sysroot, [`lldb`][lldb],
`libffi`). The exception is Apple: the SDKs and the linker come from the locally installed [Xcode][xcode], so Apple targets
need a Mac with Xcode, both to produce binaries for them and to run their tests.

## Building

1. Put `kotlin.native.enabled=true` into `local.properties` in the repository root. Without it the `:kotlin-native:*` Gradle
   projects do not exist and the Native test tasks fail.
2. `./gradlew :kotlin-native:dist` builds a **distribution** for the host into `kotlin-native/dist`: the same layout a user
   downloads, described in [code-map.md](../reference/code-map.md#the-built-distribution). A distribution for another target is built by
   `:kotlin-native:<target>CrossDist`.

The [tests](tests.md) run the compiler from `kotlin-native/dist`, so they need this build.

The dist has [caches](../compiler/caches.md) only for some flavors. Running the compiler by hand with unusual options plus
caching flags makes it build a stdlib cache first, inside the dist.

## Go deeper

| Page | What it explains |
|---|---|
| [tests.md](tests.md) | The kinds of tests, how one test runs, how to add and investigate one |
| [cheat-sheet.md](../reference/cheat-sheet.md) | The build and test commands |

## Where the code is

- [konan.properties](../../../kotlin-native/konan/konan.properties) and
  [KonanTarget.kt](../../../native/utils/src/org/jetbrains/kotlin/konan/target/KonanTarget.kt): targets and their toolchains
- [kotlin-native/build.gradle](../../../kotlin-native/build.gradle): the `dist` tasks that assemble the distribution

[targets]: https://kotlinlang.org/docs/native-target-support.html
[cross]: https://clang.llvm.org/docs/CrossCompilation.html
[lldb]: https://lldb.llvm.org/
[xcode]: https://developer.apple.com/xcode/
