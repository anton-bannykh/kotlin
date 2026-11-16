[Kotlin/Native guide](../README.md) › reference › cheat-sheet

# Cheat sheet: commands and flags
*Skip this page if you can already dump IR from `konanc` and run a Native test in a chosen mode. Reference material.*

The one place for commands. Every `konanc`, `klib` and `llvm-dis` line was run as written on a Linux x64 host with the
compiler from `kotlin-native/dist`; the Gradle lines are copied from [HACKING.md](../../../kotlin-native/HACKING.md) and
[tests.md](../build-and-tests/tests.md). `main.kt` is any small program. How to use these on a real problem:
[first-issue.md](../first-issue.md); which area a symptom points to: [debugging.md](debugging.md).

## Build the compiler
```bash
# Needs kotlin.native.enabled=true in local.properties, otherwise the :kotlin-native:* projects do not exist.
./gradlew :kotlin-native:dist                                    # compiler + stdlib + runtime for the host. Slow.
./gradlew :kotlin-native:dist :kotlin-native:distPlatformLibs    # plus platform libraries
export PATH=$PWD/kotlin-native/dist/bin:$PATH                    # konanc (= kotlinc-native), klib, cinterop
```

## Compile something by hand
```bash
konanc main.kt -o main && ./main.kexe          # both stages, debug build
konanc main.kt -o main -opt                    # optimized build: whole-program optimizations, LLVM's O3
konanc main.kt -o main -g -Xbinary=sourceInfoType=libbacktrace   # debug info; file:line in stack traces on Linux
konanc main.kt -p library -o main              # first stage only: main.klib
konanc -Xinclude=main.klib -o main             # second stage only: klib → main.kexe
konanc -list-targets                           # targets this host can compile for
konanc main.kt -p library -target linux_arm64 -o main   # a klib for another target; a binary also needs that target's
                                               # runtime in the dist (dist/konan/targets/<target>/native)
```

## Look at Kotlin IR
```bash
# IR before and after a phase as Kotlin-like text: one file per source file and phase in ./ir, about 500 files per
# dump because the stdlib is lowered too. Name the phases, comma-separated: "all" writes about 1 GB.
konanc main.kt -o main -Dorg.jetbrains.kotlin.compiler.ir.dump.strategy=KotlinLike \
    -Xphases-to-dump-before=EnumClassLowering -Xphases-to-dump-after=EnumClassLowering -Xdump-directory=ir
grep -l 'FILE: main.kt' ir/*                   # your file: ir/6844_BEFORE.EnumClassLowering.kt.ir and ..._AFTER...

konanc main.kt -o main -Xphases-to-dump=EnumClassLowering -Xdump-directory=ir    # before and after; no -D: IR as a tree
konanc main.kt -o main -Dorg.jetbrains.kotlin.compiler.ir.dump.strategy=KotlinLike \
    -Xphases-to-dump-after=EnumClassLowering -Xdump-fqname=describe -Xdump-directory=one   # only the declaration `describe`
grep -l 'fun describe' one/*                   # the other files hold just a header line

konanc main.kt -o main -opt -Xdisable-phases=EscapeAnalysis     # skip a phase (several: comma-separated)
konanc main.kt -o main -opt -Xverbose-phases=EscapeAnalysis     # let a phase print what it decides
konanc main.kt -o main -Xverify-ir=error                        # check the IR between lowerings
konanc main.kt -o main -Xprofile-phases                         # every phase that ran, in order: the exact names
```

A phase name is the class name of a lowering, or a name from the pipeline in [compiler/](../compiler/README.md).

## Look at LLVM IR
```bash
mkdir ll                                       # the directory must exist, otherwise only a warning is printed
konanc main.kt -o main -Xsave-llvm-ir-after=Codegen -Xsave-llvm-ir-directory=ll   # ll/out.Codegen.ll, whatever -o says
konanc main.kt -o main -opt -Xsave-llvm-ir-after=Codegen,LTOBitcodeOptimization -Xsave-llvm-ir-directory=ll
konanc main.kt -o main -Xtemporary-files-dir=tmp        # keeps tmp/out.bc (the final bitcode) and tmp/main.kexe.o
~/.konan/dependencies/llvm-*/bin/llvm-dis tmp/out.bc    # bitcode → text: tmp/out.ll
```
How to read the result: [llvm-primer.md](../llvm-primer.md). More LLVM tricks: [HACKING.md](../../../kotlin-native/HACKING.md).

## Look at a klib
```bash
unzip -l main.klib                 # a klib is a zip archive
klib info main.klib                # manifest: versions, "Has IR"
klib dump-ir main.klib             # the IR as stored, before any lowering
klib dump-metadata main.klib       # the declarations as the frontend reads them
klib dump-signatures main.klib     # declared and imported signatures: what IR linking matches
klib dump-abi main.klib            # one line per public declaration
```

## Run tests (Gradle; not run for this page)
```bash
T=org.jetbrains.kotlin.konan.test.blackbox
# native/native.tests/testData/codegen; then one test of it; then compiler/testData/codegen/box
./gradlew :native:native.tests:test --tests "$T.FirNativeCodegenLocalTestGenerated*"
./gradlew :native:native.tests:test --tests "$T.FirNativeCodegenLocalTestGenerated\$FileCheck.testSmoke0"
./gradlew :native:native.tests:codegen-box:test --tests "$T.FirNativeCodegenBoxTestGenerated\$Box\$*"
# other tasks of :native:native.tests: cinteropTest gcTest standaloneTest klibTest cachesTest partialLinkageTest debuggerTest
./gradlew :native:native.tests:stdlibTest
./gradlew :kotlin-native:backend.native:check                              # compiler unit tests, no dist needed
./gradlew :kotlin-native:runtime:hostRuntimeTests -Pgtest_filter='*GC*'    # runtime C++ tests
./gradlew :nativeCompilerTest :nativeCompilerUnitTest --continue           # everything; very slow, CI runs it
./gradlew :native:native.tests:generateTests :native:native.tests:codegen-box:generateTests   # test classes for the IDE
```
Modes: add to any test command. `kn.` is short for `kotlin.internal.native.test.`. The kinds of tests and how to read a
failure: [tests.md](../build-and-tests/tests.md).
```bash
-Pkn.optimizationMode=OPT          # DEBUG (default), OPT, NO
-Pkn.cacheMode=NO                  # NO, STATIC_ONLY_DIST, STATIC_EVERYWHERE, ...
-Pkn.gcType=STWMS                  # CMS (default), PMCS, STWMS, NOOP
-Pkn.target=linux_arm64            # cross-compile; runs on a simulator or emulator
-Pkn.forceStandalone=true          # every test gets its own executable
-Pkn.nativeHome=<dist>             # test an existing dist instead of rebuilding it
-Pkn.binaryOptions=<key=value>,<key=value>     # -Xbinary options for all tests
-Pkotlin.native.tests.tags='!debugger'         # filter by tag
```

## Runtime options
```bash
konanc main.kt -o main -Xbinary=gc=stwms                        # another collector: cms (default), pmcs, stwms, noop
konanc main.kt -o main -Xruntime-logs=gc=debug                  # runtime log on stderr; levels error, warning, info, debug
konanc main.kt -o main -Xbinary=runtimeAssertionsMode=panic     # turn on the runtime's own assertions
konanc main.kt -o main -Xbinary=checkStateAtExternalCalls=true  # check the thread state at calls to external code
konanc main.kt -o main -opt -Xbinary=genericSafeCasts=true      # check implicit generic casts with -opt too
konanc main.kt -o main -Xpartial-linkage-loglevel=warning       # report partial-linkage mismatches at compile time
```
Log tags are in
[RuntimeLogging.kt](../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/RuntimeLogging.kt)
(`gc`, `mm`, `alloc`, `pause`, ...); all `-Xbinary=` options are in
[BinaryOptions.kt](../../../native/binary-options/src/main/kotlin/org/jetbrains/kotlin/config/nativeBinaryOptions/BinaryOptions.kt).
A runtime with debug information: [HACKING.md](../../../kotlin-native/HACKING.md#kotlinnative-runtime-specifics).
