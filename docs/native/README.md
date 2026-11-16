# Kotlin/Native internals: a short guide

An introduction to how Kotlin/Native works inside, for people joining the team. It assumes you know Kotlin and compilers in
general. It does not assume you know LLVM.

Each page answers **what happens and why** in one part of Kotlin/Native, with a picture or an example. It deliberately does
not explain how the code does it: for that, every page ends with a few links into the source.

How to *use* Kotlin/Native is at [kotlinlang.org](https://kotlinlang.org/docs/native-overview.html). How to build, test and
debug the compiler is in [HACKING.md](../../kotlin-native/HACKING.md).

## Start here

1. [tour.md](tour.md): one small program followed from source code to a running binary. Read this first.
2. [llvm-primer.md](llvm-primer.md): the handful of LLVM concepts the rest of the guide relies on.
3. [code-map.md](code-map.md): where things are in the repository.

Then read the page for the part you are going to work on.

## The compiler

| Page | What it explains |
|---|---|
| [compilation-model.md](compilation-model.md) | The outside view: klibs, binaries, caches, interop, export |
| [pipeline.md](pipeline.md) | Every step from klibs to a binary, in order |
| [klibs-and-linking.md](klibs-and-linking.md) | What a klib holds and how several klibs become one program |
| [lowerings/](lowerings/README.md) | How language features are rewritten into simpler IR; a page per lowering |
| [optimizations.md](optimizations.md) | Whole-program optimizations: [devirtualization](optimizations/devirtualization.md), [escape analysis](optimizations/escape-analysis.md), [dead code elimination](optimizations/dce.md), [type checks](optimizations/type-checks.md) |
| [codegen.md](codegen.md) | How Kotlin IR becomes LLVM IR, and LLVM IR becomes a binary |
| [caches.md](caches.md) | Precompiled libraries and incremental compilation |

## Language features end to end

| Page | What it explains |
|---|---|
| [initialization.md](initialization.md) | When top-level properties, objects and enums are initialized |
| [coroutines.md](coroutines.md) | What a `suspend` function is compiled into |
| [exceptions.md](exceptions.md) | How `throw` reaches `catch`, including across the Objective-C boundary |

## The runtime

| Page | What it explains |
|---|---|
| [object-model.md](object-model.md) | What an object looks like in memory; type information; virtual calls |
| [runtime.md](runtime.md) | What the runtime library does; threads and safepoints |
| [gc-and-allocator.md](gc-and-allocator.md) | How memory is allocated and collected |

## Interop and tooling

| Page | What it explains |
|---|---|
| [cinterop.md](cinterop.md) | Calling C and Objective-C from Kotlin; platform libraries; the commonizer |
| [export.md](export.md) | Calling Kotlin from Objective-C, C and Swift |
| [build-and-tests.md](build-and-tests.md) | Targets, toolchains and how the tests work |
| [debugging.md](debugging.md) | Something is broken: where to look |
| [glossary.md](glossary.md) | Terms you will meet in code and in reviews |
