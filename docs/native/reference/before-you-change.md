[Kotlin/Native guide](../README.md) › reference › before-you-change

# Before you change something: what must change together

*Skip this page until you are about to change compiler, runtime or tooling code. Reference material.*

Kotlin/Native has places where one fact is written down twice, or where code far away depends on the exact shape of what
you produce. Few of them are checked by a test, and when one breaks, the failure usually appears far from the change: in
another stage, in a cache built earlier, or in a tool that lives in another directory. Find the row for what you are
changing; the last column says what you will see if you miss the second half.

## The compiler

| If you change… | …you must also | How it fails if you don't |
|---|---|---|
| The lowerings that run before inlining: the list `getLoweringsUpToAndIncludingSyntheticAccessors` in [NativeLoweringPhases.kt][phases] | Make the same change in `lower()` of [NativeInlineFunctionResolver.kt][resolver]. It applies the same lowerings by hand to an inline function that is loaded on demand → [inlining.md](../compiler/lowerings/inlining.md) | Silently: such a function is inlined after a different set of lowerings than a function from the current file |
| A lowering so that it needs another one to have run first | Declare it with [`@PhasePrerequisites`][prereq] on the lowering class → [lowerings](../compiler/lowerings/README.md) | With the annotation, [NativeLoweringPrerequisitesTest][prereqtest] fails with "The following phases have unsatisfied prerequisites". Without it, nothing checks the order |
| Code that runs after `BuildDFG` in an `-opt` build ([TopLevelPhases.kt][toplevel], `runCodegen`) | Not introduce a class the graph's symbol table has not seen. If you rewrite a function body, rebuild its graph with `FunctionDFGBuilder`, as [devirtualization][devirt] and [the pre-codegen inliner][precodegen] do → [data-flow graph](../compiler/optimizations/README.md#the-data-flow-graph) | A new class: `"The symbol table has been sealed. irClass = …"` ([DataFlowIR.kt][dfir]). A stale graph: DCE and escape analysis silently work on the old body |
| The origin of a generated declaration, or you copy a declaration without its origin | Check who compares origins. Example: [IrToBitcode.kt][irtobitcode] recognises calls to file initializers by `StaticInitializersOrigins.*` → [initialization.md](../features/initialization.md) | Silently: the call is generated as an ordinary call |
| What is placed at the start of a function body | Keep the file-initializer calls as the first statements. [StaticInitializersOptimization.kt][staticinit] looks only at the first two (`take(2)`) | Silently: redundant initializer calls are no longer removed in `-opt` builds |
| A lowering so that it writes a fact about another file or library into the current file | Record it: `dependenciesTracker.add(...)`, as [EnumClassLowering.kt][enumlow], [CachesAbiLowering.kt][cachesabi] and [InteropBridgesNameInventor.kt][bridgesname] do → [caches.md](../compiler/caches.md) | The dependency is missing from what [DependenciesTracker.kt][deptracker] records for the cache |
| An option so that it changes generated code | Add it to the cache flavor strings in [NativeSecondStageCompilationConfig.kt][config]; pass it to the cache build in `setupCommonOptionsForCaches` ([SetupConfiguration.kt][setupconf]); mirror the flavor in the tests (`CacheMode.computeCacheDirName`, [TestProcessSettings.kt][testsettings]) and, for distribution caches, in `cacheFlavor` ([Utils.kt][buildutils]) | Builds with different values share one cache directory, or the cache is built with the default value. Tests look for the cache in a directory that does not exist |
| An intrinsic kind | Spell it identically in the stdlib's [IntrinsicType.kt][intrstd] (string constants used in `@TypedIntrinsic`) and the compiler's [IntrinsicType.kt][intrcomp] (enum) | `IntrinsicType.valueOf` throws `IllegalArgumentException` when a call is compiled ([Intrinsics.kt][intrinsics]) |
| The name or signature of an internal stdlib declaration marked [`@UsedFromCompilerGeneratedCode`][usedfrom] | Update the place where the compiler finds it by name, mostly [Ir.kt][irkt] and [RuntimeNames.kt][rtnames]. A new such declaration needs the annotation, and `@PublishedApi` as well if the first stage generates calls to it | "Class … is not found" or "No function … found" ([SymbolFinder.kt][symfinder]) when the symbol is first used. A missing annotation: [NativeSymbolsTest][symtest] |
| The form of symbol names (`kfun:`, `kclass:` in [BinaryInterface.kt][bininterface]) → [codegen.md](../compiler/codegen.md#a-function-and-its-name) | Update the code that matches the names as text: [konan_lldb.py][lldbpy] (`kclass:kotlin.String`), [EscapeAnalysis.kt][ea] (`kfun:kotlin.` prefixes), the XCTest runner ([NativeTestRunner.kt][xctest]), the debugger tests ([LLDBSessionSpec.kt][lldbspec]) | Silently: a string comparison stops matching |

- **After the lowerings, fragments are compiled in parallel.** All lowerings of all fragments run first, on one thread;
  the rest runs on a thread pool, one task per fragment, when more than one thread is allowed
  ([TopLevelPhases.kt][toplevel]). A phase after the lowerings must not write to IR or state another fragment uses
  → [fragments](../compiler/README.md#fragments-and-output-kinds).

## Compiler and runtime

Generated code and the C++ runtime are compiled separately and meet only at link time
→ [runtime](../runtime/README.md#calls-in-both-directions).

| If you change… | …you must also | How it fails if you don't |
|---|---|---|
| You add a runtime function that generated code calls | Add `touchFunction(Name)` in [CompilerCInterface.cpp][cinterface] and `importRtFunction("Name", returnsObjectType)` in [ContextUtils.kt][ctxutils]; `returnsObjectType` is `true` for an `OBJ_GETTER` function | `Error("function Name not found")` from `importFunction` |
| A runtime function is called from generated code without an exception handler | Mark the C++ function `RUNTIME_NOTHROW` ([Common.h][commonh]), and make sure it really cannot throw | `"No exception handler specified when calling function … without nounwind attr"` ([CodeGenerator.kt][codegenkt]) |
| You add a Kotlin function that the runtime calls (`@ExportForCppRuntime`) | Declare it in a runtime header and add a stub to [CompilerGenerated.cpp][compgen], which stands in for compiled Kotlin code in the runtime's own tests | |
| A field of `TypeInfo` or the object header ([TypeInfo.h][typeinfoh]) | Change [RTTIGenerator.kt][rtti], which fills the fields by position → [object-model.md](../runtime/object-model.md#things-to-know) | |
| An enum or constant with a "Must match" comment | Change the other side: [Logging.hpp][logginghpp] ↔ [RuntimeLogging.kt][rtlogging]; [CompilerConstants.hpp][compconst] ↔ [RuntimeAssertsMode.kt][assertsmode], `AppStateTracking.kt`, `GCSchedulerType.kt`; `OBJECT_TAG_PERMANENT` in [Memory.h][memoryh] ↔ [KotlinStaticData.kt][staticdata]; [Alignment.hpp][alignment] ↔ [Runtime.kt][runtimekt] | Only the comment connects the two sides |
| The list of runtime bitcode modules | Change both [RuntimeModule.kt][rtmodule] and [runtime/build.gradle.kts][rtbuild]; each has a comment pointing at the other | |
| Runtime C++ so that a C++ exception that is not a Kotlin exception can leave it | Catch it before it reaches a Kotlin frame. A Kotlin landing pad catches everything (`catch null`) and reads the exception as a Kotlin one: `Kotlin_getExceptionObject` does a `static_cast` without a check → [exceptions.md](../features/exceptions.md) | Silently wrong memory access in the `catch` |

Thread states → [runtime](../runtime/README.md#threads-and-their-two-states):

- C++ called through `@GCUnsafeCall` runs in the *runnable* state. The annotation's documentation
  ([Annotations.kt][nativeannot]) makes the callee responsible for safepoints and for switching the state around blocking
  calls: use `ThreadStateGuard` ([Memory.h][memoryh]).
- C++ entered from a thread in the native state, or not yet known to the runtime, uses `CalledFromNativeGuard`: it
  switches to runnable and initializes the runtime if needed.
- `AssertThreadState` does nothing unless runtime assertions are enabled (`-Xbinary=runtimeAssertionsMode=panic`), so a
  wrong state is silent in an ordinary build.

## Klibs and compatibility

A klib is read by compilers newer than the one that wrote it, so its content is a published format
→ [klibs-and-linking.md](../compiler/klibs-and-linking.md).

| If you change… | …you must also | How it fails if you don't |
|---|---|---|
| How IR, signatures or metadata are written to a klib | Keep reading every klib from `KotlinAbiVersion.FIRST_SUPPORTED` (1.6.0) to `CURRENT` ([KotlinAbiVersion.kt][abiversion], [bump history][abihistory]). [KonanDriver.kt][driver] refuses klibs outside this window; inside it they must work | Old libraries stop linking. The suites in [klib-compatibility][klibcompat] run one stage with another compiler version |
| A lowering that runs in the first stage, before the klib is written | Put the new behaviour behind a `LanguageFeature`, as [NativeFirstPhaseLoweringPhases.kt][firstphase] does, and keep the second stage working on klibs written without it | A klib from an older compiler has the old shape of IR |
| Dependencies of `compiler/ir/backend.native` or `compiler/cli/cli-native-klib` | Not add `:kotlin-native:backend.native` → [code-map.md](code-map.md) | The first stage no longer works without LLVM |
| The annotations cinterop puts on generated declarations | Change both spellings: the tool writes them as strings ([StubIr.kt][stubir]), the compiler looks them up in [RuntimeNames.kt][rtnames] and [InteropUtils.kt][interoputils] → [cinterop.md](../interop/cinterop.md) | |

- A declaration of a platform library gets the package of the `.def` file whose headers declare it
  ([platformLibs/src/platform/][platformdefs], `package = platform.posix`). Moving a header to another `.def` file changes
  the package that user code imports.

## Tools around the compiler

The other side of each row is in the Gradle plugin (`libraries/tools/kotlin-gradle-plugin`), not in the Native
directories, so a search in `kotlin-native/` and `native/` does not find it.

| If you change… | …you must also | How it fails if you don't |
|---|---|---|
| The path `konan/lib/kotlin-native-compiler-embeddable.jar`, the main class `org.jetbrains.kotlin.cli.utilities.MainKt`, or `daemonMain` / `daemonMainWithXcodeRenderer` ([main.kt][climain]) | Change [nativeRunnerHelpers.kt][runnerhelpers] in the plugin | `"Couldn't find daemon entry point 'daemonMain'"` ([KotlinNativeToolRunner.kt][toolrunner]) |
| Static state, environment or process-wide settings in the compiler | Remember that Gradle calls `daemonMain` inside its own daemon, through a class loader that is cached between builds ([KotlinNativeToolRunner.kt][toolrunner], `runInProcess`) | State survives into the next compilation; a process-wide action hits the Gradle daemon |
| A `--ktest_*` flag of the test runner ([TestRunner.kt][testrunner]) | Change [KotlinNativeTest.kt][kntest], which passes `--ktest_no_exit_code`, `--ktest_logger=`, `--ktest_gradle_filter=`, `--ktest_negative_gradle_filter=` | |
| The text of a printed stack trace or of "Uncaught Kotlin exception: " ([RuntimeUtils.kt][rtutils]) | Change [KotlinNativeStackTraceParser.kt][stparser], which turns the text of a failed test into a stack trace for Gradle | Its [test][stparsertest] uses pasted samples, so it keeps passing |
| A flag the plugin writes as text: `-Xbinary=`, `-Xauto-cache-from=`, `-Xic-cache-dir=` | Change [KotlinNativeCompilerArgBuilder.kt][argbuilder] and [KotlinNativeLink.kt][knlink] | |
| The keys `cacheableTargets`, `optInCacheableTargets`, `additionalCacheFlags` in [konan.properties][konanprops], the file `tools/env_blacklist`, or a target name | Change [KonanPropertiesBuildService.kt][propsservice], which reads them by name and resolves targets with `KonanTarget.predefinedTargets.getValue` | |
| The layout of klibs in the distribution | Check the commonizer, which loads the platform libraries from it ([KonanDistributionRepository.kt][commonizerrepo]) | |
| You remove a compiler argument | Do not delete it: move it to the `removed` directory and set `removedVersion` ([code-rules.md][argrules]) | |
| You rename or remove a `-Xbinary=` option ([BinaryOptions.kt][binopts]) | Treat the name as user interface. An unknown name is only a warning, `"Unknown binary option '…'"` ([parsing.kt][binparsing]) | Silently: the user's setting no longer has any effect |
| How a declaration is exported to Objective-C → [export.md](../interop/export.md) | Change both header generators: [impl/k1][objck1] (used by the compiler) and [impl/analysis-api][objcaa]. One test suite runs against both ([ReadMe.md][objcreadme]) | |
| How declarations are collected for C export | Change both [CAdapterGenerator.kt][cadapter] and [CAdapterIrGenerator.kt][cadapterir]; `-Xbinary=cExportUseIrDiscovery=true` selects the second ([NativeCompilerDriver.kt][ncdriver]) | |

[phases]: ../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/driver/phases/NativeLoweringPhases.kt
[toplevel]: ../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/driver/phases/TopLevelPhases.kt
[resolver]: ../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/lower/NativeInlineFunctionResolver.kt
[prereq]: ../../../compiler/ir/backend.common/src/org/jetbrains/kotlin/backend/common/phaser/PhasePrerequisites.kt
[prereqtest]: ../../../kotlin-native/backend.native/compiler/ir/backend.native/test/org/jetbrains/kotlin/backend/konan/lowering/NativeLoweringPrerequisitesTest.kt
[devirt]: ../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/optimizations/DevirtualizationAnalysis.kt
[precodegen]: ../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/optimizations/PreCodegenInliner.kt
[dfir]: ../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/optimizations/DataFlowIR.kt
[ea]: ../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/optimizations/EscapeAnalysis.kt
[staticinit]: ../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/optimizations/StaticInitializersOptimization.kt
[irtobitcode]: ../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/llvm/IrToBitcode.kt
[enumlow]: ../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/lower/EnumClassLowering.kt
[cachesabi]: ../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/lower/CachesAbiLowering.kt
[bridgesname]: ../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/lower/InteropBridgesNameInventor.kt
[deptracker]: ../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/DependenciesTracker.kt
[config]: ../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/NativeSecondStageCompilationConfig.kt
[setupconf]: ../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/SetupConfiguration.kt
[testsettings]: ../../../native/native.tests/testFixtures/org/jetbrains/kotlin/konan/test/blackbox/support/settings/TestProcessSettings.kt
[buildutils]: ../../../kotlin-native/build-tools/src/main/kotlin/org/jetbrains/kotlin/Utils.kt
[intrstd]: ../../../kotlin-native/runtime/src/main/kotlin/kotlin/native/internal/IntrinsicType.kt
[intrcomp]: ../../../native/base/src/main/kotlin/org/jetbrains/kotlin/backend/konan/IntrinsicType.kt
[intrinsics]: ../../../compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/ir/Intrinsics.kt
[usedfrom]: ../../../libraries/stdlib/src/kotlin/internal/Annotations.kt
[irkt]: ../../../compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/ir/Ir.kt
[rtnames]: ../../../compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/RuntimeNames.kt
[symfinder]: ../../../compiler/ir/ir.tree/src/org/jetbrains/kotlin/ir/SymbolFinder.kt
[symtest]: ../../../native/native.tests/klib-ir-inliner/tests/org/jetbrains/kotlin/konan/test/klib/NativeSymbolsTest.kt
[bininterface]: ../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/llvm/BinaryInterface.kt
[lldbpy]: ../../../kotlin-native/llvmDebugInfoC/src/scripts/konan_lldb.py
[xctest]: ../../../native/kotlin-test-native-xctest/src/nativeMain/kotlin/NativeTestRunner.kt
[lldbspec]: ../../../native/native.tests/testFixtures/org/jetbrains/kotlin/konan/test/blackbox/support/util/LLDBSessionSpec.kt
[cinterface]: ../../../kotlin-native/runtime/src/compiler_interface/cpp/CompilerCInterface.cpp
[ctxutils]: ../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/llvm/ContextUtils.kt
[commonh]: ../../../kotlin-native/runtime/src/main/cpp/Common.h
[codegenkt]: ../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/llvm/CodeGenerator.kt
[compgen]: ../../../kotlin-native/runtime/src/test_support/cpp/CompilerGenerated.cpp
[typeinfoh]: ../../../kotlin-native/runtime/src/main/cpp/TypeInfo.h
[rtti]: ../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/llvm/RTTIGenerator.kt
[logginghpp]: ../../../kotlin-native/runtime/src/main/cpp/Logging.hpp
[rtlogging]: ../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/RuntimeLogging.kt
[compconst]: ../../../kotlin-native/runtime/src/main/cpp/CompilerConstants.hpp
[assertsmode]: ../../../native/binary-options/src/main/kotlin/org/jetbrains/kotlin/config/nativeBinaryOptions/RuntimeAssertsMode.kt
[memoryh]: ../../../kotlin-native/runtime/src/main/cpp/Memory.h
[staticdata]: ../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/llvm/KotlinStaticData.kt
[alignment]: ../../../kotlin-native/runtime/src/main/cpp/Alignment.hpp
[runtimekt]: ../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/llvm/Runtime.kt
[rtmodule]: ../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/llvm/runtime/RuntimeModule.kt
[rtbuild]: ../../../kotlin-native/runtime/build.gradle.kts
[nativeannot]: ../../../kotlin-native/runtime/src/main/kotlin/kotlin/native/internal/Annotations.kt
[abiversion]: ../../../compiler/util-klib/src/org/jetbrains/kotlin/library/KotlinAbiVersion.kt
[abihistory]: ../../../compiler/util-klib/KotlinAbiVersionBumpHistory.md
[driver]: ../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/KonanDriver.kt
[klibcompat]: ../../../native/native.tests/klib-compatibility
[firstphase]: ../../../compiler/ir/ir.inline/src/org/jetbrains/kotlin/ir/inline/konan/NativeFirstPhaseLoweringPhases.kt
[stubir]: ../../../kotlin-native/Interop/StubGenerator/src/org/jetbrains/kotlin/native/interop/gen/StubIr.kt
[interoputils]: ../../../native/base/src/main/kotlin/org/jetbrains/kotlin/backend/konan/InteropUtils.kt
[platformdefs]: ../../../kotlin-native/platformLibs/src/platform
[climain]: ../../../kotlin-native/utilities/cli-runner/src/org/jetbrains/kotlin/cli/utilities/main.kt
[runnerhelpers]: ../../../libraries/tools/kotlin-gradle-plugin/src/common/kotlin/org/jetbrains/kotlin/internal/compilerRunner/native/nativeRunnerHelpers.kt
[toolrunner]: ../../../libraries/tools/kotlin-gradle-plugin/src/common/kotlin/org/jetbrains/kotlin/internal/compilerRunner/native/KotlinNativeToolRunner.kt
[testrunner]: ../../../kotlin-native/runtime/src/main/kotlin/kotlin/native/internal/test/TestRunner.kt
[kntest]: ../../../libraries/tools/kotlin-gradle-plugin/src/common/kotlin/org/jetbrains/kotlin/gradle/targets/native/tasks/KotlinNativeTest.kt
[rtutils]: ../../../kotlin-native/runtime/src/main/kotlin/kotlin/native/internal/RuntimeUtils.kt
[stparser]: ../../../libraries/tools/kotlin-gradle-plugin/src/common/kotlin/org/jetbrains/kotlin/gradle/targets/native/internal/KotlinNativeStackTraceParser.kt
[stparsertest]: ../../../libraries/tools/kotlin-gradle-plugin/src/test/kotlin/org/jetbrains/kotlin/gradle/targets/native/internal/KotlinNativeStackTraceParserKtTest.kt
[argbuilder]: ../../../libraries/tools/kotlin-gradle-plugin/src/common/kotlin/org/jetbrains/kotlin/gradle/targets/native/tasks/KotlinNativeCompilerArgBuilder.kt
[knlink]: ../../../libraries/tools/kotlin-gradle-plugin/src/common/kotlin/org/jetbrains/kotlin/gradle/targets/native/tasks/KotlinNativeLink.kt
[konanprops]: ../../../kotlin-native/konan/konan.properties
[propsservice]: ../../../libraries/tools/kotlin-gradle-plugin/src/common/kotlin/org/jetbrains/kotlin/gradle/targets/native/KonanPropertiesBuildService.kt
[commonizerrepo]: ../../../native/commonizer/src/org/jetbrains/kotlin/commonizer/repository/KonanDistributionRepository.kt
[argrules]: ../../../compiler/arguments/code-rules.md
[binopts]: ../../../native/binary-options/src/main/kotlin/org/jetbrains/kotlin/config/nativeBinaryOptions/BinaryOptions.kt
[binparsing]: ../../../native/binary-options/src/main/kotlin/org/jetbrains/kotlin/config/nativeBinaryOptions/parsing.kt
[objck1]: ../../../native/objcexport-header-generator/impl/k1
[objcaa]: ../../../native/objcexport-header-generator/impl/analysis-api
[objcreadme]: ../../../native/objcexport-header-generator/ReadMe.md
[cadapter]: ../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/cexport/CAdapterGenerator.kt
[cadapterir]: ../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/cexport/CAdapterIrGenerator.kt
[ncdriver]: ../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/driver/NativeCompilerDriver.kt
