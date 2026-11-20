[Kotlin/Native guide](../README.md) › reference › debugging

# Something is broken: where to look
*Skip this page if you can already tell from a symptom which part of Kotlin/Native to suspect. Reference material.*

A routing table from what you see to the part of Kotlin/Native that most likely causes it, with the first thing to try.
**New here? Read [first-issue.md](../first-issue.md) first**: it walks one real report through the whole procedure. The exact
command for every flag named below is in the [cheat sheet](cheat-sheet.md). Attaching a debugger to the compiler and
building the runtime with debug information are in [HACKING.md](../../../kotlin-native/HACKING.md).

**First, reproduce it outside Gradle**, with a plain `konanc` command on the smallest program that still fails. Then check
two things: does it happen in debug, with `-opt`, or both? Does it go away when one phase is disabled (`-Xdisable-phases`)?
The answers usually name the area.

| You see | Look at | First thing to try |
|---|---|---|
| **The compiler fails** | | |
| Exception with a lowering's class name in the stack trace | That lowering, found in [all-lowerings.md](../compiler/lowerings/all-lowerings.md) | Dump IR before and after it; `-Xverify-ir=error` finds the first phase that breaks the IR |
| "Unbound symbol", an IR linker error, a klib is rejected | [klibs-and-linking.md](../compiler/klibs-and-linking.md) | `klib dump-signatures` on both libraries; check the versions in the manifests |
| Crash in `optimizations/`, or only with `-opt` | [optimizations/](../compiler/optimizations/README.md) | `-Xdisable-phases=Devirtualization`, `DCEPhase`, `EscapeAnalysis`, one at a time |
| LLVM verifier error, crash in `IrToBitcode` or `CodeGenerator` | [codegen.md](../compiler/codegen.md) | `-Xsave-llvm-ir-after=Codegen`, read the function named in the error |
| Linker error: undefined symbol | [codegen.md](../compiler/codegen.md); with libraries: [caches.md](../compiler/caches.md), [cinterop.md](../interop/cinterop.md) | Search the saved LLVM IR for the symbol. Is it only declared? Does it fail without caches? |
| Fails only with caches, or only after an incremental build | [caches.md](../compiler/caches.md), [incremental-compilation.md](../compiler/incremental-compilation.md) | Compare with a build that uses no caches; after an incremental build, with a clean one (delete the IC cache directory) |
| Wrong generated header, or an unresolved C or Objective-C name | [export.md](../interop/export.md), [cinterop.md](../interop/cinterop.md) | Run `cinterop` by hand on the [`.def` file](https://kotlinlang.org/docs/native-definition-file.html); read the generated header |
| "Target is not available", toolchain or [Xcode](https://developer.apple.com/xcode/) errors | [build-and-tests/](../build-and-tests/README.md) | Check the target's entries in `konan.properties` and what is in `~/.konan/dependencies` |
| **The program misbehaves** | | |
| Wrong result in debug and with `-opt` | [lowerings/](../compiler/lowerings/README.md), [codegen.md](../compiler/codegen.md) | Minimize; dump IR after the suspected lowering; read the LLVM IR |
| Wrong result or crash only with `-opt` | [optimizations/](../compiler/optimizations/README.md), then LLVM passes ([codegen.md](../compiler/codegen.md)) | Disable phases one at a time, as above, then `LTOBitcodeOptimization` and `ModuleBitcodeOptimization` |
| `ClassCastException` in debug, garbage with `-opt` | Unchecked generic cast: by design → [first-issue.md](../first-issue.md) | `-opt -Xbinary=genericSafeCasts=true` brings the check back |
| Segfault on object access; wrong `is` or cast result | [object-model.md](../runtime/object-model.md) | `-Xbinary=runtimeAssertionsMode=panic` turns on the runtime's own checks |
| Runtime assertion, "wrong thread state" | [runtime/](../runtime/README.md) | `-Xbinary=checkStateAtExternalCalls=true` |
| Crash or hang in the GC, heap corruption | [gc-and-allocator.md](../runtime/gc-and-allocator.md) | Try another collector: `-Xbinary=gc=stwms`, then `pmcs`, `noop`. Logs: `-Xruntime-logs=gc=debug` |
| `IrLinkageError` at run time | Partial linkage → [klibs-and-linking.md](../compiler/klibs-and-linking.md) | `-Xpartial-linkage-loglevel=warning` shows the mismatch at compile time |
| A top-level property or object is not initialized, or too early | [initialization.md](../features/initialization.md) | Dump IR after `StaticInitializersLowering` |
| Wrong value right after a `suspend` function resumes | [coroutines.md](../features/coroutines.md) | Dump IR after `NativeSuspendFunctionsLowering` |
| An exception is not caught, or the program terminates instead | [exceptions.md](../features/exceptions.md) | Look at the [`invoke`](https://llvm.org/docs/LangRef.html#invoke-instruction) and [`landingpad`](https://llvm.org/docs/LangRef.html#landingpad-instruction) instructions around the call in LLVM IR |
| Crash when calling C or Objective-C | [cinterop.md](../interop/cinterop.md) | `-Xphases-to-dump-after=InteropLowering` |
| Crash when Swift or Objective-C calls Kotlin | [export.md](../interop/export.md) | Check [`@Throws`](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-throws/) on the Kotlin declaration; read the generated header |
| Stack trace without file names and line numbers | [exceptions.md](../features/exceptions.md) | Compile with [`-g`](https://kotlinlang.org/docs/native-debugging.html); on Linux add `-Xbinary=sourceInfoType=libbacktrace` |
| Crash inside the C++ runtime with no symbols | [runtime/](../runtime/README.md) | Build the runtime with debug information: [HACKING.md](../../../kotlin-native/HACKING.md#kotlinnative-runtime-specifics) |
| **Tests** | | |
| A test fails, or passes when it should not | [tests.md](../build-and-tests/tests.md) | [HACKING.md, Testing](../../../kotlin-native/HACKING.md#testing) |

## Where the code is

- [TopLevelPhases.kt](../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/driver/phases/TopLevelPhases.kt):
  the order of phases, to see what ran before the failure.
- [BinaryOptions.kt](../../../native/binary-options/src/main/kotlin/org/jetbrains/kotlin/config/nativeBinaryOptions/BinaryOptions.kt):
  all `-Xbinary=` options.
- [code-map.md](code-map.md): where lowerings, optimizations, code generation and the runtime live.
