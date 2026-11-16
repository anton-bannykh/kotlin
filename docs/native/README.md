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

## How the guide is organized

```
docs/native/
├── tour.md, llvm-primer.md, code-map.md      start here
├── compiler/            klibs → binary: the pipeline, klibs, code generation, caches
│   ├── lowerings/       how language features are rewritten into simpler IR; a page per lowering
│   └── optimizations/   whole-program optimizations
├── features/            language features followed end to end
├── runtime/             the C++ runtime: objects, threads, memory
├── interop/             calling C and Objective-C, and being called from them
├── build-and-tests/     targets, toolchains, building, tests
└── debugging.md, glossary.md                 look things up
```

Each directory's `README.md` is the overview of that topic.

## [compiler/](compiler/README.md)

| Page | What it explains |
|---|---|
| [compiler/](compiler/README.md) | Every step from klibs to a binary, in order |
| [compilation-model.md](compiler/compilation-model.md) | The outside view: klibs, binaries, caches, interop, export |
| [klibs-and-linking.md](compiler/klibs-and-linking.md) | What a klib holds and how several klibs become one program |
| [lowerings/](compiler/lowerings/README.md) | How language features are rewritten into simpler IR; a page per lowering |
| [optimizations/](compiler/optimizations/README.md) | Whole-program optimizations: [devirtualization](compiler/optimizations/devirtualization.md), [escape analysis](compiler/optimizations/escape-analysis.md), [dead code elimination](compiler/optimizations/dce.md), [type checks](compiler/optimizations/type-checks.md) |
| [codegen.md](compiler/codegen.md) | How Kotlin IR becomes LLVM IR, and LLVM IR becomes a binary |
| [caches.md](compiler/caches.md) | Precompiled libraries and incremental compilation |

## [features/](features/README.md)

| Page | What it explains |
|---|---|
| [initialization.md](features/initialization.md) | When top-level properties, objects and enums are initialized |
| [coroutines.md](features/coroutines.md) | What a `suspend` function is compiled into |
| [exceptions.md](features/exceptions.md) | How `throw` reaches `catch`, including across the Objective-C boundary |

## [runtime/](runtime/README.md)

| Page | What it explains |
|---|---|
| [runtime/](runtime/README.md) | What the runtime library does; threads and safepoints |
| [object-model.md](runtime/object-model.md) | What an object looks like in memory; type information; virtual calls |
| [gc-and-allocator.md](runtime/gc-and-allocator.md) | How memory is allocated and collected |

## [interop/](interop/README.md)

| Page | What it explains |
|---|---|
| [cinterop.md](interop/cinterop.md) | Calling C and Objective-C from Kotlin; platform libraries; the commonizer |
| [export.md](interop/export.md) | Calling Kotlin from Objective-C, C and Swift |

## [build-and-tests/](build-and-tests/README.md)

| Page | What it explains |
|---|---|
| [build-and-tests/](build-and-tests/README.md) | Targets, toolchains and building the compiler |
| [tests.md](build-and-tests/tests.md) | The kinds of tests, how one test runs, how to add and debug one |

## Looking things up

| Page | What it explains |
|---|---|
| [debugging.md](debugging.md) | Something is broken: where to look |
| [glossary.md](glossary.md) | Terms you will meet in code and in reviews |
