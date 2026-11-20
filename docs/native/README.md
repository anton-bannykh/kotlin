# Kotlin/Native internals: a short guide

An introduction to how Kotlin/Native works inside, for people joining the team. It assumes you know Kotlin and compilers in
general. It does not assume you know [LLVM][llvm].

Each page answers **what happens and why** in one part of Kotlin/Native, with a picture or an example. It deliberately does
not explain how the code does it: for that, every page ends with a few links into the source.

How to *use* Kotlin/Native is at [kotlinlang.org](https://kotlinlang.org/docs/native-overview.html). How to build, test and
debug the compiler is in [HACKING.md](../../kotlin-native/HACKING.md).

The guide has three layers. Read the first, pick from the second, look things up in the third.

## 1. Overview: read these in order

| | Page | What you get |
|---|---|---|
| 1 | [tour.md](tour.md) | One small program followed from source code to a running binary. The whole picture by example |
| 2 | [llvm-primer.md](llvm-primer.md) | How to read LLVM IR. Skip it if you already can |
| 3 | [compiler/](compiler/README.md) | The two stages and every step of the pipeline, in order |
| 4 | [runtime/](runtime/README.md) | What the runtime library does while the program runs |
| 5 | [first-issue.md](first-issue.md) | A real problem traced from the report to the responsible line of the compiler |

After these you know what every part is for and how to investigate a problem.

## 2. Topics: go deeper where you need to

Each directory is one topic. Its `README.md` is the overview of the topic and ends with a "Go deeper" table of the pages
below it, so you can stop at the level of detail you need.

| Topic | Overview | Deeper pages |
|---|---|---|
| The compiler | [compiler/](compiler/README.md) | [klibs-and-linking.md](compiler/klibs-and-linking.md), [codegen.md](compiler/codegen.md), [caches.md](compiler/caches.md), [incremental-compilation.md](compiler/incremental-compilation.md) ([in depth](compiler/incremental-compilation-in-depth.md)) |
| Lowerings: language features rewritten into simpler IR | [compiler/lowerings/](compiler/lowerings/README.md) | A page per theme: [inlining](compiler/lowerings/inlining.md), [callable references](compiler/lowerings/callable-references.md), [classes and local declarations](compiler/lowerings/classes-and-local-declarations.md), [enums](compiler/lowerings/enums.md), [control flow](compiler/lowerings/control-flow.md), [functions](compiler/lowerings/functions.md), [casts and types](compiler/lowerings/casts-and-types.md), [boxing](compiler/lowerings/boxing.md), [interop](compiler/lowerings/interop.md), [tests and assertions](compiler/lowerings/tests-and-assertions.md) |
| Whole-program optimizations | [compiler/optimizations/](compiler/optimizations/README.md) | [devirtualization.md](compiler/optimizations/devirtualization.md), [escape-analysis.md](compiler/optimizations/escape-analysis.md), [dce.md](compiler/optimizations/dce.md), [type-checks.md](compiler/optimizations/type-checks.md) |
| Language features: an index of where each is explained | [features/](features/README.md) | Followed end to end: [initialization.md](features/initialization.md), [coroutines.md](features/coroutines.md), [exceptions.md](features/exceptions.md) |
| The runtime | [runtime/](runtime/README.md) | [object-model.md](runtime/object-model.md), [gc-and-allocator.md](runtime/gc-and-allocator.md) |
| Interop with C, Objective-C and Swift | [interop/](interop/README.md) | [cinterop.md](interop/cinterop.md), [export.md](interop/export.md) |
| Building and testing | [build-and-tests/](build-and-tests/README.md) | [tests.md](build-and-tests/tests.md) |

## 3. Reference: look things up

| Your question | Page |
|---|---|
| Something is broken. Which part is responsible? | [debugging.md](reference/debugging.md) |
| What is the command or flag for this? | [cheat-sheet.md](reference/cheat-sheet.md) |
| What does this term mean? | [glossary.md](reference/glossary.md) |
| Where is the code for this? | [code-map.md](reference/code-map.md) |
| Where is this language feature explained? | [features/](features/README.md) |
| What does this lowering do? | [all-lowerings.md](compiler/lowerings/all-lowerings.md) |
| I am about to change something. What else must change with it? | [before-you-change.md](reference/before-you-change.md) |

## How pages are built

- The first line is a trail back to this page; the line under the title says who can skip the page.
- A picture or an example comes first, then what happens and why, then a few things to know.
- A page does not explain how the code works. It ends with links to the source files that do.

[llvm]: https://llvm.org/
