/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE file.
 */
package org.jetbrains.kotlin.backend.konan

import kotlinx.cinterop.toCValues
import llvm.*
import org.jetbrains.kotlin.backend.konan.llvm.*
import org.jetbrains.kotlin.backend.konan.llvm.objc.patchObjCRuntimeModule
import org.jetbrains.kotlin.backend.konan.llvm.runtime.RuntimeModule
import org.jetbrains.kotlin.backend.konan.llvm.runtime.linkRuntimeModules
import org.jetbrains.kotlin.backend.konan.serialization.CacheDeserializationStrategy
import org.jetbrains.kotlin.config.nativeBinaryOptions.CCallMode
import org.jetbrains.kotlin.config.nativeBinaryOptions.CInterfaceGenerationMode
import org.jetbrains.kotlin.config.nativeBinaryOptions.GC
import org.jetbrains.kotlin.config.nativeBinaryOptions.GCSchedulerType
import org.jetbrains.kotlin.konan.file.isBitcode
import org.jetbrains.kotlin.konan.library.components.bitcode
import org.jetbrains.kotlin.konan.library.linkerOpts
import org.jetbrains.kotlin.konan.target.CompilerOutputKind
import org.jetbrains.kotlin.konan.target.Family
import org.jetbrains.kotlin.konan.target.supportsCoreSymbolication
import org.jetbrains.kotlin.konan.target.supportsLibBacktrace
import org.jetbrains.kotlin.library.isNativeStdlib
import org.jetbrains.kotlin.library.metadata.isCInteropLibrary
import org.jetbrains.kotlin.utils.addToStdlib.ifNotEmpty
import java.io.File
import kotlin.io.path.absolutePathString
import kotlin.io.path.pathString

/**
 * Supposed to be true for a single LLVM module within final binary.
 */
val NativeSecondStageCompilationConfig.isFinalBinary: Boolean get() = when (this.produce) {
    CompilerOutputKind.PROGRAM, CompilerOutputKind.DYNAMIC,
    CompilerOutputKind.STATIC -> true
    CompilerOutputKind.DYNAMIC_CACHE, CompilerOutputKind.STATIC_CACHE, CompilerOutputKind.HEADER_CACHE,
    CompilerOutputKind.LIBRARY, CompilerOutputKind.BITCODE -> false
    CompilerOutputKind.FRAMEWORK -> !omitFrameworkBinary
    CompilerOutputKind.TEST_BUNDLE -> true
}

val CompilerOutputKind.isNativeLibrary: Boolean
    get() = this == CompilerOutputKind.DYNAMIC || this == CompilerOutputKind.STATIC

/**
 * Return true if compiler has to generate a C API for dynamic/static library.
 */
val NativeSecondStageCompilationConfig.produceCInterface: Boolean
    get() = this.produce.isNativeLibrary && this.cInterfaceGenerationMode != CInterfaceGenerationMode.NONE

val CompilerOutputKind.involvesBitcodeGeneration: Boolean
    get() = this != CompilerOutputKind.LIBRARY

internal val CacheDeserializationStrategy?.containsKFunctionImpl: Boolean
    get() = this?.contains(KonanFqNames.internalPackageName, "KFunctionImpl.kt") != false

internal val NativeGenerationState.shouldDefineFunctionClasses: Boolean
    get() = producedLlvmModuleContainsStdlib && cacheDeserializationStrategy.containsKFunctionImpl

internal val NativeGenerationState.shouldDefineCachedBoxes: Boolean
    get() = producedLlvmModuleContainsStdlib &&
            cacheDeserializationStrategy?.contains(KonanFqNames.internalPackageName, "Boxing.kt") != false

internal val CacheDeserializationStrategy?.containsRuntime: Boolean
    get() = this?.contains(KonanFqNames.internalPackageName, "Runtime.kt") != false

internal val NativeGenerationState.shouldLinkRuntimeNativeLibraries: Boolean
    get() = producedLlvmModuleContainsStdlib && cacheDeserializationStrategy.containsRuntime

val CompilerOutputKind.isFullCache: Boolean
    get() = this == CompilerOutputKind.STATIC_CACHE || this == CompilerOutputKind.DYNAMIC_CACHE

val CompilerOutputKind.isHeaderCache: Boolean
    get() = this == CompilerOutputKind.HEADER_CACHE

val CompilerOutputKind.isCache: Boolean
    get() = this.isFullCache || this.isHeaderCache

internal fun produceCStubs(generationState: NativeGenerationState) {
    generationState.cStubsManager.compile(
            generationState.config.clang,
            generationState.diagnosticReporter,
            generationState.inVerbosePhase
    ).forEach {
        parseAndLinkBitcodeFile(generationState, generationState.llvm.module, it.absolutePathString())
    }
}

private data class LlvmModules(
        val runtimeModules: List<LLVMModuleRef>,
        val additionalModules: List<LLVMModuleRef>
)

/**
 * Deserialize, generate, patch all bitcode dependencies and classify them into two sets:
 * - Runtime modules. These may be used as an input for a separate LTO (e.g. for debug builds).
 * - Everything else.
 */
private fun collectLlvmModules(generationState: NativeGenerationState, generatedBitcodeFiles: List<String>): LlvmModules {
    val config = generationState.config
    val runtimeModulesConfig = generationState.runtimeModulesConfig

    val [bitcodePartOfStdlib, bitcodeLibraries] = generationState.dependenciesTracker.bitcodeToLink
            .filterNot { it.isCInteropLibrary() && config.cCallMode == CCallMode.Direct }
            .partition { it.isNativeStdlib && generationState.producedLlvmModuleContainsStdlib }
            .toList()
            .map { libraries ->
                libraries.flatMap { it.bitcode(config.target)?.bitcodeFilePaths.orEmpty() }.filter { it.isBitcode }
            }

    fun MutableList<String>.add(module: RuntimeModule) = add(runtimeModulesConfig.absolutePathFor(module))

    val additionalBitcodeFiles = buildList<String> {
        if (config.produce == CompilerOutputKind.PROGRAM) {
            add(RuntimeModule.LAUNCHER)
        }
        addAll(generatedBitcodeFiles)
        addAll(generationState.llvm.additionalProducedBitcodeFiles)
        addAll(bitcodeLibraries.map { it.pathString })
        if (config.produce == CompilerOutputKind.DYNAMIC_CACHE) {
            add(RuntimeModule.EXCEPTIONS_SUPPORT)
        }
        if (config.produce == CompilerOutputKind.TEST_BUNDLE) {
            add(RuntimeModule.XCTEST_LAUNCHER)
        }
    }

    val runtimeBitcodeFiles = buildList<String> {
        if (runtimeModulesConfig.containsDebuggingRuntime) add(RuntimeModule.DEBUG)
        add(RuntimeModule.RUNTIME)

        if (config.target.family == Family.OSX && config.minidumpLocation != null) {
            add(RuntimeModule.BREAKPAD)
            add(RuntimeModule.CRASH_HANDLER_IMPL)
        } else {
            add(RuntimeModule.CRASH_HANDLER_NOOP)
        }
        when (config.gcSchedulerType) {
            GCSchedulerType.MANUAL -> {
                add(RuntimeModule.GC_SCHEDULER_MANUAL)
            }
            GCSchedulerType.ADAPTIVE -> {
                add(RuntimeModule.GC_SCHEDULER_ADAPTIVE)
            }
            GCSchedulerType.AGGRESSIVE -> {
                add(RuntimeModule.GC_SCHEDULER_AGGRESSIVE)
            }
            GCSchedulerType.DISABLED, GCSchedulerType.WITH_TIMER, GCSchedulerType.ON_SAFE_POINTS -> {
                throw IllegalStateException("Deprecated options must have already been handled")
            }
        }
        when (config.gc) {
            GC.STOP_THE_WORLD_MARK_AND_SWEEP -> add(RuntimeModule.GC_STOP_THE_WORLD_MARK_AND_SWEEP)
            GC.NOOP -> add(RuntimeModule.GC_NOOP)
            GC.PARALLEL_MARK_CONCURRENT_SWEEP -> add(RuntimeModule.GC_PARALLEL_MARK_CONCURRENT_SWEEP)
            GC.CONCURRENT_MARK_AND_SWEEP -> add(RuntimeModule.GC_CONCURRENT_MARK_AND_SWEEP)
        }
        if (config.target.supportsCoreSymbolication()) {
            add(RuntimeModule.SOURCE_INFO_CORE_SYMBOLICATION)
        }
        if (config.target.supportsLibBacktrace()) {
            add(RuntimeModule.SOURCE_INFO_LIBBACKTRACE)
            add(RuntimeModule.LIBBACKTRACE)
        }
        when (config.allocationMode) {
            AllocationMode.STD -> {
                add(RuntimeModule.ALLOC_LEGACY)
            }
            AllocationMode.CUSTOM -> {
                add(RuntimeModule.ALLOC_CUSTOM)
            }
        }
        when (config.checkStateAtExternalCalls) {
            true -> add(RuntimeModule.EXTERNAL_CALLS_CHECKER_IMPL)
            false -> add(RuntimeModule.EXTERNAL_CALLS_CHECKER_NOOP)
        }
        // Bitcode parts of stdlib are considered part of the runtime
        addAll(bitcodePartOfStdlib.map { it.pathString })
    }

    fun parseBitcodeFiles(files: List<String>): List<LLVMModuleRef> = files.map { bitcodeFile ->
        val parsedModule = parseBitcodeFile(generationState, generationState.diagnosticReporter, generationState.llvmContext, bitcodeFile)
        if (!generationState.shouldUseDebugInfoFromNativeLibs()) {
            LLVMStripModuleDebugInfo(parsedModule)
        }
        parsedModule
    }

    val runtimeModules = parseBitcodeFiles(
            runtimeBitcodeFiles.takeIf { generationState.shouldLinkRuntimeNativeLibraries }.orEmpty()
    )
    val nativeLibraryModules = parseBitcodeFiles(config.nativeLibraries)
    if (config.cachedLibraries.hasStaticCaches || config.cachedLibraries.hasDynamicCaches) {
        // Native libraries are linked into the final module, which is then internalized as a closed world.
        // But their functions can be used by cinterop stubs from caches, which are outside of this module (KT-89938).
        nativeLibraryModules.forEach { addExternalDefinitionsToLlvmUsed(it) }
    }
    val additionalModules = nativeLibraryModules + parseBitcodeFiles(additionalBitcodeFiles)
    return LlvmModules(
            runtimeModules.ifNotEmpty { this + generationState.generateRuntimeConstantsModule() } ?: emptyList(),
            additionalModules + listOfNotNull(patchObjCRuntimeModule(generationState))
    )
}

/**
 * Adds the externally visible definitions of [module] to `llvm.used`, so that LLVM doesn't internalize and remove them.
 */
private fun addExternalDefinitionsToLlvmUsed(module: LLVMModuleRef) {
    val externalLinkages = setOf(LLVMLinkage.LLVMExternalLinkage, LLVMLinkage.LLVMWeakAnyLinkage, LLVMLinkage.LLVMWeakODRLinkage)
    val definitions = (getFunctions(module) + getGlobals(module) + getGlobalAliases(module))
            .filter { LLVMIsDeclaration(it) == 0 && LLVMGetLinkage(it) in externalLinkages }
            .toList()
    if (definitions.isEmpty()) return

    val oldLlvmUsed = LLVMGetNamedGlobal(module, "llvm.used")
    val oldElements = oldLlvmUsed?.let { getOperands(LLVMGetInitializer(it)!!) }.orEmpty()
    oldLlvmUsed?.let { LLVMDeleteGlobal(it) }

    val elements = (oldElements + definitions).distinct()
    val pointerType = LLVMPointerTypeInContext(LLVMGetModuleContext(module), 0)!!
    val arrayType = LLVMArrayType(pointerType, elements.size)!!
    val llvmUsed = LLVMAddGlobal(module, arrayType, "llvm.used")!!
    LLVMSetInitializer(llvmUsed, LLVMConstArray(pointerType, elements.toCValues(), elements.size))
    LLVMSetLinkage(llvmUsed, LLVMLinkage.LLVMAppendingLinkage)
    LLVMSetSection(llvmUsed, "llvm.metadata")
}

private fun linkAllDependencies(generationState: NativeGenerationState, generatedBitcodeFiles: List<String>) {
    val (runtimeModules, additionalModules) = collectLlvmModules(generationState, generatedBitcodeFiles)
    // TODO: Possibly slow, maybe to a separate phase?
    val optimizedRuntimeModules = linkRuntimeModules(generationState, runtimeModules)

    // When the main module `generationState.llvmModule` is very large it is much faster to
    // link all the auxiliary modules together first before linking with the main module.
    val linkedModules = (optimizedRuntimeModules + additionalModules).reduceOrNull { acc, module ->
        val failed = llvmLinkModules2(generationState, acc, module)
        if (failed != 0) {
            error("Failed to link ${module.getName()}")
        }
        return@reduceOrNull acc
    }
    linkedModules?.let {
        val failed = llvmLinkModules2(generationState, generationState.llvmModule, it)
        if (failed != 0) {
            error("Failed to link runtime and additional modules into main module")
        }
    }
}

internal fun insertAliasToEntryPoint(module: LLVMModuleRef, entryPointName: String) {
    val entryPoint = LLVMGetNamedFunction(module, entryPointName)
            ?: error("Module doesn't contain `$entryPointName`")
    val programAddressSpace = LLVMKotlinGetProgramAddressSpace(module)
    LLVMAddAlias2(module, getGlobalFunctionType(entryPoint), programAddressSpace, entryPoint, "main")
}

internal fun linkBitcodeDependencies(generationState: NativeGenerationState,
                                     generatedBitcodeFiles: List<File>) {
    val config = generationState.config
    val produce = config.produce

    val staticFramework = produce == CompilerOutputKind.FRAMEWORK && config.produceStaticFramework
    val swiftExport = config.swiftExport && produce == CompilerOutputKind.STATIC

    if (staticFramework || swiftExport) {
        embedAppleLinkerOptionsToBitcode(generationState.llvm, config)
    }
    linkAllDependencies(generationState, generatedBitcodeFiles.map { it.absoluteFile.normalize().path })

}

private fun parseAndLinkBitcodeFile(generationState: NativeGenerationState, llvmModule: LLVMModuleRef, path: String) {
    val parsedModule = parseBitcodeFile(generationState, generationState.diagnosticReporter, generationState.llvmContext, path)
    if (!generationState.shouldUseDebugInfoFromNativeLibs()) {
        LLVMStripModuleDebugInfo(parsedModule)
    }
    val failed = llvmLinkModules2(generationState, llvmModule, parsedModule)
    if (failed != 0) {
        throw Error("failed to link $path")
    }
}

private fun embedAppleLinkerOptionsToBitcode(llvm: CodegenLlvmHelpers, config: NativeSecondStageCompilationConfig) {
    fun findEmbeddableOptions(options: List<String>): List<List<String>> {
        val result = mutableListOf<List<String>>()
        val iterator = options.iterator()
        loop@while (iterator.hasNext()) {
            val option = iterator.next()
            result += when {
                option.startsWith("-l") -> listOf(option)
                option == "-framework" && iterator.hasNext() -> listOf(option, iterator.next())
                else -> break@loop // Ignore the rest.
            }
        }
        return result
    }

    val optionsToEmbed = findEmbeddableOptions(config.platform.configurables.linkerKonanFlags) +
            llvm.dependenciesTracker.allNativeDependencies.flatMap { findEmbeddableOptions(it.linkerOpts) }.toList()

    embedLlvmLinkOptions(llvm.llvmContext, llvm.module, optionsToEmbed)
}
