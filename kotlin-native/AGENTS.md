# Kotlin/Native

Kotlin/Native (K/N) is the LLVM-based backend, the C++ runtime, and the native tooling (cinterop, ObjC/C/Swift export, klib
commonizer). The code lives in `kotlin-native/`, `native/` and a few `compiler/` modules.

**Read [docs/native/tour.md](../docs/native/tour.md) first.** It follows one program from source to binary and links to
the page for every step. [docs/native/code-map.md](../docs/native/code-map.md) says where the code lives. The rest of the
[docs/native](../docs/native/README.md) guide explains what each part does and why, in short pages with examples; for how, read
the source files each page links to. This file only adds what an agent needs on top of that.

## Where to read next

| When you are working on…                                           | Read                                                                                      |
|--------------------------------------------------------------------|-------------------------------------------------------------------------------------------|
| A bug report or a failing test, and you do not know where to start | [docs/native/debugging.md](../docs/native/debugging.md)                                   |
| Running or adding compiler tests, test directives                  | [docs/native/build-and-tests/tests.md](../docs/native/build-and-tests/tests.md), [HACKING.md#testing](HACKING.md#testing) |
| Klibs vs binaries, the two stages                                  | [docs/native/compiler/compilation-model.md](../docs/native/compiler/compilation-model.md), [klibs-and-linking.md](../docs/native/compiler/klibs-and-linking.md) |
| What runs when; dump and disable flags                             | [docs/native/compiler/](../docs/native/compiler/README.md)                                     |
| A lowering, or wrong code for a language feature                   | [docs/native/compiler/lowerings/](../docs/native/compiler/lowerings/README.md) (index, one page per lowering) |
| Devirtualization, DCE, escape analysis, `-opt`-only bugs           | [docs/native/compiler/optimizations/](../docs/native/compiler/optimizations/README.md) and the pages it links    |
| IR → LLVM IR, runtime bitcode, LLVM passes, linker, debug info     | [docs/native/compiler/codegen.md](../docs/native/compiler/codegen.md), [llvm-primer.md](../docs/native/llvm-primer.md) |
| Object layout, type info, anything touching both `llvm/` and `runtime/` | [docs/native/runtime/object-model.md](../docs/native/runtime/object-model.md)                        |
| Runtime C++ (thread states, safepoints, GC, allocator)             | [docs/native/runtime/](../docs/native/runtime/README.md), [gc-and-allocator.md](../docs/native/runtime/gc-and-allocator.md), [runtime/README.md](runtime/README.md), [runtime/codestyle/cpp/README.md](runtime/codestyle/cpp/README.md) |
| Global, object and enum initialization                             | [docs/native/features/initialization.md](../docs/native/features/initialization.md)                         |
| Suspend functions, coroutine state machines, spilling              | [docs/native/features/coroutines.md](../docs/native/features/coroutines.md)                                 |
| Exceptions, unwinding, `@Throws`/`NSError`                         | [docs/native/features/exceptions.md](../docs/native/features/exceptions.md)                                 |
| cinterop, platform libraries, the commonizer                       | [docs/native/interop/cinterop.md](../docs/native/interop/cinterop.md)                                     |
| ObjC/C export                                                      | [docs/native/interop/export.md](../docs/native/interop/export.md)                                         |
| Caches, incremental compilation                                    | [docs/native/compiler/caches.md](../docs/native/compiler/caches.md)                                         |
| Targets, toolchains, `konan.properties`, the distribution          | [docs/native/build-and-tests/](../docs/native/build-and-tests/README.md)                       |
| Cross-module inlining in klibs                                     | [docs/inlining/klib-inining.md](../docs/inlining/klib-inining.md)                         |
| LLVM IR dumps, a debugger on the compiler                          | [HACKING.md](HACKING.md) (LLVM and Debugging sections)                                    |
| Swift export                                                       | [docs/swift-export/README.md](../docs/swift-export/README.md)                             |
| ObjC header generation                                             | [native/objcexport-header-generator/ReadMe.md](../native/objcexport-header-generator/ReadMe.md) |
| Binary options (`-Xbinary=`)                                       | `native/binary-options/src/main/kotlin/org/jetbrains/kotlin/config/nativeBinaryOptions/BinaryOptions.kt` |

## Build and test commands

Prerequisite: `kotlin.native.enabled=true` in `local.properties`. Without it, the `:kotlin-native:*` projects are not included and
the Native test tasks fail.

```bash
# Distribution for the host (compiler + stdlib + runtime) into kotlin-native/dist. Slow.
./gradlew :kotlin-native:dist
./gradlew :kotlin-native:dist :kotlin-native:distPlatformLibs      # plus platform libraries

# Compiler integration tests (native/native.tests). Generated test classes are in package org.jetbrains.kotlin.konan.test.blackbox.
./gradlew :native:native.tests:test --tests "org.jetbrains.kotlin.konan.test.blackbox.FirNativeCodegenLocalTestGenerated*"   # native/native.tests/testData/codegen
./gradlew :native:native.tests:codegen-box:test --tests "org.jetbrains.kotlin.konan.test.blackbox.FirNativeCodegenBoxTestGenerated\$Box\$*"   # compiler/testData/codegen/box
./gradlew :native:native.tests:stdlibTest            # also: cinteropTest, gcTest, standaloneTest, klibTest, cachesTest, partialLinkageTest, debuggerTest
./gradlew :native:native.tests:test --tests "..." -Pkn.optimizationMode=OPT -Pkn.gcType=CMS -Pkn.cacheMode=NO   # test modes

# Compiler unit tests, runtime gtest
./gradlew :kotlin-native:backend.native:check
./gradlew :kotlin-native:runtime:hostRuntimeTests -Pgtest_filter='*GC*'

# Everything (very slow; CI runs it)
./gradlew :nativeCompilerTest :nativeCompilerUnitTest --continue
```

## Pitfalls

- **Tests run the compiler from `kotlin-native/dist`**, loaded in a separate classloader, not from the IDE or test classpath. A test
  run rebuilds the dist when the compiler changes, so expect several minutes per change.
  - To skip the rebuild for tests that touch no compiler code, pass `-Pkn.nativeHome=<existing dist>`.
  - Changing runtime C++ or the stdlib also rebuilds their bitcode or klib.
- **Native test classes are generated into `<project>/build/tests-gen/`.** They are not checked in, and the test task generates them
  automatically. The aggregate `./gradlew generateTests` does *not* include them. After adding test data, either just run the test
  task, or run `./gradlew :native:native.tests:generateTests` (each test subproject has its own `generateTests`) to refresh the IDE.
- **Keep the first stage free of LLVM:** `compiler/ir/backend.native` and `compiler/cli/cli-native-klib` must not depend on
  `:kotlin-native:backend.native`.
- **The compiler and the runtime form one contract.** Changing a runtime function signature, the object layout, or type info needs
  matching changes in `llvm/` (for example `Runtime.kt`, `RTTIGenerator.kt`, `RuntimeNames.kt`), and the reverse is also true. The
  list of runtime bitcode modules is duplicated in `llvm/runtime/RuntimeModule.kt` and `runtime/build.gradle.kts`.
- **The second stage still uses K1 descriptors** (`native/frontend`, `runK1Frontend`) to load klibs, even though the frontend is K2.
- **Lowerings run per file**, not per module. A lowering must not assume that other files are already lowered to the same point.
  Use the synchronization points in `TopLevelPhases.kt` if you need that.
- **Ignoring tests:** in shared `compiler/testData`, use `// IGNORE_BACKEND: NATIVE`, or `// IGNORE_NATIVE: <prop>=<value>` for a
  specific test mode. `// IGNORE_NATIVE` means the test is expected to fail; `// DISABLE_NATIVE` means it is not run at all. See
  `native/native.tests/testFixtures/org/jetbrains/kotlin/konan/test/blackbox/support/TestDirectives.kt`.
- **Apple-only tests** (ObjC export, Swift export, XCTest, simulators) need a macOS host and Xcode.
- **Runtime C++ follows its own style** ([runtime/codestyle/cpp/README.md](runtime/codestyle/cpp/README.md)) and has a
  `.clang-format` file.

## Commits

Use the `[K/N]` subject tag (or a more specific one: `[ObjCExport]`, `[Swift Export]`, `[Klib]`, `[PL]`); see
[commit-guidelines.md](../.ai/commit-guidelines.md).
