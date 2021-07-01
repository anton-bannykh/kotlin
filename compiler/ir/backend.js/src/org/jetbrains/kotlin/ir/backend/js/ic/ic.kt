/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.backend.js.ic

import com.intellij.openapi.project.Project
import org.jetbrains.kotlin.analyzer.AbstractAnalyzerWithCompilerReport
import org.jetbrains.kotlin.backend.common.lower
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.ir.backend.js.*
import org.jetbrains.kotlin.ir.backend.js.lower.generateTests
import org.jetbrains.kotlin.ir.backend.js.lower.moveBodilessDeclarationsToSeparatePlace
import org.jetbrains.kotlin.ir.backend.js.lower.serialization.ir.JsIrLinker
import org.jetbrains.kotlin.ir.backend.js.transformers.irToJs.IrModuleToJsTransformer
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.declarations.persistent.PersistentIrFactory
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.js.config.DceRuntimeDiagnostic
import org.jetbrains.kotlin.library.KotlinLibrary
import org.jetbrains.kotlin.library.resolver.KotlinLibraryResolveResult
import org.jetbrains.kotlin.library.resolver.KotlinResolvedLibrary
import org.jetbrains.kotlin.library.resolver.TopologicalLibraryOrder
import org.jetbrains.kotlin.name.FqName
import java.io.PrintWriter

// TODO test purpose only
// klib path -> ic data
val icCache = mutableMapOf<String, SerializedIcData>()

// TODO change API to support not only stdlib
fun prepareIcCaches(
    project: Project,
    analyzer: AbstractAnalyzerWithCompilerReport,
    configuration: CompilerConfiguration,
    allDependencies: KotlinLibraryResolveResult,
) {
    val sortedDependencies = allDependencies.getFullResolvedList(TopologicalLibraryOrder)

    // TODO: foreach
    sortedDependencies.forEach { resolvedLibrary ->
        val dependencies = resolvedLibrary.allDependencies().toSet()
        val resolveResult = allDependencies.filterRoots { it in dependencies || it == resolvedLibrary }

        prepareSingleLibraryIcCache(project, analyzer, configuration, resolvedLibrary.library, resolveResult)
    }
}

private fun prepareSingleLibraryIcCache(
    project: Project,
    analyzer: AbstractAnalyzerWithCompilerReport,
    configuration: CompilerConfiguration,
    library: KotlinLibrary,
    dependencies: KotlinLibraryResolveResult,
) {
    if (dependencies.getFullList().size > 1) {
        return // TODO
    }

    icCache.computeIfAbsent(library.libraryFile.absolutePath) {
        val irFactory = PersistentIrFactory()
        val controller = WholeWorldStageController()
        irFactory.stageController = controller

        val (context, deserializer, allModules) = prepareIr(
            project,
            MainModule.Klib(library),
            analyzer,
            configuration,
            dependencies,
            emptyList(),
            emptySet(),
            null,
            false,
            false,
            irFactory,
            useGlobalSignatures = true,
            useStdlibCache = false,
        )

        val moduleFragment = allModules.last()

        moveBodilessDeclarationsToSeparatePlace(context, moduleFragment)

        generateTests(context, moduleFragment)

        lowerPreservingIcData(moduleFragment, context, controller)

        IcSerializer(
            context.irBuiltIns,
            context.mapping,
            irFactory,
            deserializer,
            moduleFragment
        ).serializeDeclarations(irFactory.allDeclarations)
    }
}

private fun KotlinResolvedLibrary.allDependencies(): List<KotlinResolvedLibrary> {
    val visited = mutableSetOf<KotlinResolvedLibrary>()

    val result = mutableListOf<KotlinResolvedLibrary>()

    fun KotlinResolvedLibrary.dfs() {
        visited += this

        resolvedDependencies.forEach {
            if (it !in visited) {
                it.dfs()
                result += it
            }
        }
    }

    dfs()

    return result
}

private fun dumpIr(module: IrModuleFragment, fileName: String) {
    val dumpOptions = KotlinLikeDumpOptions(printElseAsTrue = true)

    var actual = ""

    for (file in module.files) {
        actual += file.path + "\n"
        actual += run {
            var r = ""

            file.declarations.map { it.dumpKotlinLike(dumpOptions) }.sorted().forEach { r += it }

            r
        }
        actual += "\n"
    }
    PrintWriter("/home/ab/vcs/kotlin/$fileName.txt").use {
        it.print(actual)
    }
}

fun icCompile(
    project: Project,
    mainModule: MainModule,
    analyzer: AbstractAnalyzerWithCompilerReport,
    configuration: CompilerConfiguration,
    allDependencies: KotlinLibraryResolveResult,
    friendDependencies: List<KotlinLibrary>,
    mainArguments: List<String>?,
    exportedDeclarations: Set<FqName> = emptySet(),
    generateFullJs: Boolean = true,
    generateDceJs: Boolean = false,
    dceRuntimeDiagnostic: DceRuntimeDiagnostic? = null,
    es6mode: Boolean = false,
    multiModule: Boolean = false,
    relativeRequirePath: Boolean = false,
    propertyLazyInitialization: Boolean,
    useStdlibCache: Boolean,
): CompilerResult {

    if (useStdlibCache) {
        // Lower and save stdlib IC data if needed
        prepareIcCaches(project, analyzer, configuration, allDependencies)
    }

    val irFactory = PersistentIrFactory()
    val controller = WholeWorldStageController()
    irFactory.stageController = controller

    val (context, _, allModules, moduleToName, loweredIrLoaded) = prepareIr(
        project,
        mainModule,
        analyzer,
        configuration,
        allDependencies,
        friendDependencies,
        exportedDeclarations,
        dceRuntimeDiagnostic,
        es6mode,
        propertyLazyInitialization,
        irFactory,
        useStdlibCache,
        useStdlibCache,
    )

//    if (useStdlibCache) {
//        loadIrForIc(deserializer, allModules.first(), context)
//    }

    val modulesToLower = allModules.filter { it !in loweredIrLoaded }

    // This won't work incrementally
    modulesToLower.forEach { module ->
        moveBodilessDeclarationsToSeparatePlace(context, module)
    }

    // TODO should be done incrementally
    generateTests(context, allModules.last())

    modulesToLower.forEach {
        lowerPreservingIcData(it, context, controller)
    }

//    dumpIr(allModules.first(), "simple-dump${if (useStdlibCache) "-actual" else ""}")

    val transformer = IrModuleToJsTransformer(
        context,
        mainArguments,
        fullJs = generateFullJs,
        dceJs = generateDceJs,
        multiModule = multiModule,
        relativeRequirePath = relativeRequirePath,
        moduleToName = moduleToName,
    )

    irFactory.stageController = object : StageController(999) {}

    return transformer.generateModule(allModules)
}

fun lowerPreservingIcData(module: IrModuleFragment, context: JsIrBackendContext, controller: WholeWorldStageController) {
    // Lower all the things
    controller.currentStage = 0

    pirLowerings.forEachIndexed { i, lowering ->
        controller.currentStage = i + 1
        when (lowering) {
            is DeclarationLowering ->
                lowering.declarationTransformer(context).lower(module)
            is BodyLowering ->
                lowering.bodyLowering(context).lower(module)
            // else -> TODO what about other lowerings?
        }
    }

    controller.currentStage = pirLowerings.size + 1
}

private fun prepareIr(
    project: Project,
    mainModule: MainModule,
    analyzer: AbstractAnalyzerWithCompilerReport,
    configuration: CompilerConfiguration,
    allDependencies: KotlinLibraryResolveResult,
    friendDependencies: List<KotlinLibrary>,
    exportedDeclarations: Set<FqName> = emptySet(),
    dceRuntimeDiagnostic: DceRuntimeDiagnostic? = null,
    es6mode: Boolean = false,
    propertyLazyInitialization: Boolean,
    irFactory: PersistentIrFactory,
    useGlobalSignatures: Boolean,
    useStdlibCache: Boolean,
): PreparedIr {
    val cacheProvider: LoweringsCacheProvider? = when {
        useStdlibCache -> object : LoweringsCacheProvider {
            override fun cacheByPath(path: String): SerializedIcData? {
                return icCache[path]
            }
        }
        useGlobalSignatures -> EmptyLoweringsCacheProvider
        else -> null
    }

    val (moduleFragment: IrModuleFragment, dependencyModules, irBuiltIns, symbolTable, deserializer, moduleToName, loweredIrLoaded) =
        loadIr(project, mainModule, analyzer, configuration, allDependencies, friendDependencies, irFactory, cacheProvider)

    val moduleDescriptor = moduleFragment.descriptor

    val allModules = when (mainModule) {
        is MainModule.SourceFiles -> dependencyModules + listOf(moduleFragment)
        is MainModule.Klib -> dependencyModules
    }

    val context = JsIrBackendContext(
        moduleDescriptor,
        irBuiltIns,
        symbolTable,
        allModules.first(),
        exportedDeclarations,
        configuration,
        es6mode = es6mode,
        dceRuntimeDiagnostic = dceRuntimeDiagnostic,
        propertyLazyInitialization = propertyLazyInitialization,
        mapping = deserializer.mapping,
    )

    // Load declarations referenced during `context` initialization
    val irProviders = listOf(deserializer)
    ExternalDependenciesGenerator(symbolTable, irProviders).generateUnboundSymbolsAsDependencies()

    deserializer.postProcess()
    symbolTable.noUnboundLeft("Unbound symbols at the end of linker")

    deserializer.loadIcIr { moveBodilessDeclarationsToSeparatePlace(context, it) }

    return PreparedIr(context, deserializer, allModules, moduleToName, loweredIrLoaded)
}

data class PreparedIr(
    val context: JsIrBackendContext,
    val linker: JsIrLinker,
    val allModules: List<IrModuleFragment>,
    val moduleToName: Map<IrModuleFragment, String>,
    val loweredIrLoaded: Set<IrModuleFragment>,
)