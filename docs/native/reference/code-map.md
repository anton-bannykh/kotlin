[Kotlin/Native guide](../README.md) › reference › code-map

# Code map: where things are

*Skip this page until you need to find where something lives. Reference material.*

For historical reasons the code sits in three places of the Kotlin repository, and the name **konan** (the old name of the
project) is still in most package and file names.

```
kotlin-native/     the backend that uses LLVM, the C++ runtime, the cinterop tool, platform libraries, the distribution
native/            the command-line entry point, tests, export tools, and Native code that must not depend on LLVM
compiler/          Native parts of the shared compiler: first stage, frontend checkers, klib reading and writing
```

The split has one rule behind it: everything needed to produce a klib must work without LLVM, so that the first stage can
run inside Gradle or the IDE on any machine. Only `kotlin-native/backend.native` and what it uses may touch LLVM.

| Directory | What it is |
|---|---|
| `native/cli-native` | `K2Native`, the entry point of `konanc`. Decides which stage to run |
| `compiler/cli/cli-native-klib` | The first stage: sources → klib |
| `compiler/fir/fir-native`, `compiler/fir/checkers/checkers.native` | Native-specific parts of the K2 frontend and its checks |
| `compiler/ir/backend.native` | Native IR code without LLVM: first-stage lowerings, checks, names shared with the runtime |
| `compiler/ir/serialization.native` | Reading and writing klib IR for Native; the IR linker → [klibs-and-linking.md](../compiler/klibs-and-linking.md) |
| `compiler/ir/ir.inline`, `compiler/ir/backend.common` | The inliner and the lowerings shared by all backends. Many "Native" lowerings live here |
| **`kotlin-native/backend.native/compiler/ir/backend.native`** | **The backend**: second stage. Layout below |
| **`kotlin-native/runtime`** | **The runtime** in C++, and the Native-only part of the stdlib in Kotlin. Layout below |
| `libraries/stdlib` | The rest of the stdlib; `native-wasm/` is shared by Native and Wasm |
| `kotlin-native/Interop` | The `cinterop` tool (`Indexer`, `StubGenerator`) and the `kotlinx.cinterop` library (`Runtime`) → [cinterop.md](../interop/cinterop.md) |
| `kotlin-native/platformLibs` | `.def` files from which the platform libraries (POSIX, Apple frameworks) are generated |
| `kotlin-native/llvmInterop`, `kotlin-native/libclangInterop` | Bindings that let the compiler, a JVM program, call the C APIs of LLVM and Clang |
| `kotlin-native/libllvmext`, `kotlin-native/libclangext` | C++ additions to those APIs, and Kotlin/Native's own LLVM passes |
| `kotlin-native/llvmDebugInfoC` | Helpers for emitting debug information; the LLDB script `konan_lldb.py` |
| `kotlin-native/konan/konan.properties` | For every target: toolchain, sysroot, Clang and linker flags → [build-and-tests/](../build-and-tests/README.md) |
| `kotlin-native/build-tools` | Gradle plugins that build Kotlin/Native itself: the runtime, the distribution, platform libraries |
| `kotlin-native/cmd` | The launcher scripts: `konanc`, `cinterop`, `klib`, `run_konan` |
| `native/utils` | `KonanTarget`, host detection, access to `konan.properties`, downloading of toolchains |
| `native/binary-options` | The `-Xbinary=name=value` options (`BinaryOptions.kt`) |
| `native/frontend` | Declarations in the old frontend's form (K1 descriptors), still used by the second stage |
| `native/cinterop.deserialization` | Builds IR for cinterop klibs, which store metadata only |
| `native/objcexport-header-generator` | Generates the Objective-C header of a framework → [export.md](../interop/export.md) |
| `native/swift` | Swift export, a separate tool → [docs/swift-export](../../swift-export/README.md) |
| `native/commonizer` | The commonizer: makes platform libraries usable from code shared between targets → [cinterop.md](../interop/cinterop.md) |
| `native/native.tests` | The compiler's integration tests → [tests.md](../build-and-tests/tests.md) |
| `libraries/tools/kotlin-gradle-plugin` | The Gradle plugin; its Native part runs the compiler and `cinterop` |

## Inside the backend

All under `kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/`:

| Directory | What is in it | Guide page |
|---|---|---|
| `driver/` | The pipeline: which phase runs when. Start reading at `driver/phases/TopLevelPhases.kt` | [compiler/](../compiler/README.md) |
| `lower/` | Native-specific lowerings, one class per file | [lowerings/](../compiler/lowerings/README.md) |
| `optimizations/` | Whole-program analyses: devirtualization, escape analysis, dead code elimination | [optimizations/](../compiler/optimizations/README.md) |
| `llvm/` | Code generation. `IrToBitcode.kt` translates function bodies, `RTTIGenerator.kt` emits class descriptions | [codegen.md](../compiler/codegen.md), [object-model.md](../runtime/object-model.md) |
| `objcexport/`, `llvm/objcexport/` | Building an Objective-C framework, and the bridge code generated for it | [export.md](../interop/export.md) |
| `cexport/` | Building a C library: the header and the adapter functions | [export.md](../interop/export.md) |
| `cgen/` | Generates the C bridges used for calls between Kotlin and C or Objective-C | [cinterop.md](../interop/cinterop.md) |
| `serialization/` | Backend-side helpers for loading klibs and for data stored in caches | [klibs-and-linking.md](../compiler/klibs-and-linking.md) |
| top-level files | Configuration (`NativeSecondStageCompilationConfig.kt`), caches (`Cache*.kt`), invoking the linker (`Linker.kt`) | [caches.md](../compiler/caches.md) |

## Inside the runtime

`kotlin-native/runtime/src/` has one directory per [runtime module](../runtime/README.md#how-it-is-shipped):

| Directory | What is in it |
|---|---|
| `main/cpp/` | The core, always linked: objects and type information, threads, exceptions, strings, startup |
| `main/kotlin/` | The Native-only part of the stdlib, including `kotlin.native.internal`, which the compiler calls into |
| `gc/`, `alloc/`, `gcScheduler/` | The garbage collectors, the allocators and the policies that decide when to collect → [gc-and-allocator.md](../runtime/gc-and-allocator.md) |
| `launcher/` | The C `main` of an executable |
| `objc/` | Objective-C support, for Apple targets |
| `compiler_interface/` | A module the compiler reads to learn the runtime's C++ struct layouts and function signatures |

## The built distribution

`./gradlew :kotlin-native:dist` puts a working compiler into `kotlin-native/dist`, with the same layout as a released
Kotlin/Native (→ [build-and-tests/](../build-and-tests/README.md)):

```
bin/                           konanc, cinterop, klib, run_konan
konan/lib/                     kotlin-native-compiler-embeddable.jar: the whole compiler
konan/nativelib/               native libraries the compiler loads to call LLVM and Clang
konan/targets/<target>/native/ the runtime as bitcode: runtime.bc, concurrent_ms_gc.bc, custom_alloc.bc, launcher.bc, ...
konan/konan.properties         toolchain settings per target
klib/common/stdlib/            the stdlib klib, unpacked
klib/platform/<target>/        platform libraries; built separately by :kotlin-native:distPlatformLibs
klib/cache/<target>-.../       caches: the stdlib (and platform libraries) already compiled to machine code
tools/konan_lldb.py            pretty-printers for LLDB
```

LLVM, Clang, linkers and sysroots are not in the distribution: they are downloaded into `~/.konan/dependencies/`.
