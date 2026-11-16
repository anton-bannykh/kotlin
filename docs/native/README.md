# Kotlin/Native internals: a short guide

An introduction to how Kotlin/Native works inside, for people joining the team. It assumes you know Kotlin and compilers in
general. It does not assume you know LLVM.

Each page answers **what happens and why** in one part of Kotlin/Native, with a picture or an example. It deliberately does
not explain how the code does it: for that, every page ends with a few links into the source.

How to *use* Kotlin/Native is at [kotlinlang.org](https://kotlinlang.org/docs/native-overview.html). How to build, test and
debug the compiler is in [HACKING.md](../../kotlin-native/HACKING.md).

## Reading paths

You do not need to read everything. Start with the first hour, then add the path that matches your issue. Everything else
is reference: open it when you need it. Each page says in its second line who can skip it.

**The first hour, for everyone**

| | Page | Time |
|---|---|---|
| 1 | [tour.md](tour.md): one small program followed from source code to a running binary | 15 min |
| 2 | [llvm-primer.md](llvm-primer.md): how to read LLVM IR (skip if you can) | 10 min |
| 3 | [compiler/](compiler/README.md): the two stages and every step of the pipeline | 15 min |
| 4 | [first-issue.md](first-issue.md): a real problem traced to the responsible line of the compiler | 12 min |
| 5 | [cheat-sheet.md](cheat-sheet.md): skim it, so you know which commands exist | 5 min |

**Then, by the kind of issue**

| Your issue | Read | Time |
|---|---|---|
| Wrong code for a language feature | The first two sections of [lowerings/](compiler/lowerings/README.md), then the theme page of the feature; for initialization, coroutines and exceptions the page in [features/](features/README.md) | 20–30 min |
| Shows only with `-opt` | [optimizations/](compiler/optimizations/README.md), then the page of the one analysis involved | 20 min |
| Crash or wrong result in generated code, LLVM errors | [codegen.md](compiler/codegen.md), [object-model.md](runtime/object-model.md) | 30 min |
| Memory, threads, GC, crashes inside the runtime | [runtime/](runtime/README.md), [object-model.md](runtime/object-model.md), [gc-and-allocator.md](runtime/gc-and-allocator.md) | 40 min |
| Calls to or from C, Objective-C, Swift | [interop/](interop/README.md) and the page for the direction; [lowerings/interop.md](compiler/lowerings/interop.md) | 30–40 min |
| Shows only with caches, or after an incremental build | [caches.md](compiler/caches.md) | 15 min |
| Unresolved symbols, klib or version errors | [klibs-and-linking.md](compiler/klibs-and-linking.md) | 10 min |
| A test fails, or you need to add one | [tests.md](build-and-tests/tests.md) | 12 min |

Not sure which row applies: [debugging.md](debugging.md) maps symptoms to pages.

## How the guide is organized

```
docs/native/
├── tour.md, llvm-primer.md                   start here
├── first-issue.md, cheat-sheet.md            how to investigate; every command in one place
├── compiler/            klibs → binary: the pipeline, klibs, code generation, caches
│   ├── lowerings/       how language features are rewritten into simpler IR; a page per theme
│   └── optimizations/   whole-program optimizations
├── features/            language features followed end to end
├── runtime/             the C++ runtime: objects, threads, memory
├── interop/             calling C and Objective-C, and being called from them
├── build-and-tests/     targets, toolchains, building, tests
└── debugging.md, glossary.md, code-map.md    look things up
```

Each directory's `README.md` is the overview of that topic.

## [compiler/](compiler/README.md)

| Page | What it explains |
|---|---|
| [compiler/](compiler/README.md) | The two stages from the outside, then every step from klibs to a binary, in order |
| [klibs-and-linking.md](compiler/klibs-and-linking.md) | What a klib holds and how several klibs become one program |
| [lowerings/](compiler/lowerings/README.md) | How language features are rewritten into simpler IR; an index of all lowerings and a page per theme |
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
| [export.md](interop/export.md) | Calling Kotlin from Objective-C, C and Swift (reference) |

## [build-and-tests/](build-and-tests/README.md)

| Page | What it explains |
|---|---|
| [build-and-tests/](build-and-tests/README.md) | Targets, toolchains and building the compiler |
| [tests.md](build-and-tests/tests.md) | The kinds of tests, how one test runs, how to add and debug one |

## Looking things up

| Page | What it explains |
|---|---|
| [debugging.md](debugging.md) | Something is broken: where to look |
| [cheat-sheet.md](cheat-sheet.md) | Every command and flag in one place |
| [code-map.md](code-map.md) | Where things are in the repository |
| [glossary.md](glossary.md) | Terms you will meet in code and in reviews |
