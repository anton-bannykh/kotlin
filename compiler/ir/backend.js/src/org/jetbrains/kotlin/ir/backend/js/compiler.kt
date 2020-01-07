/*
 * Copyright 2010-2018 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.backend.js

import com.intellij.openapi.project.Project
import org.jetbrains.kotlin.backend.common.phaser.PhaseConfig
import org.jetbrains.kotlin.backend.common.phaser.invokeToplevel
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.ir.backend.js.lower.generateTests
import org.jetbrains.kotlin.ir.backend.js.lower.moveBodilessDeclarationsToSeparatePlace
import org.jetbrains.kotlin.ir.backend.js.transformers.irToJs.IrModuleToJsTransformer
import org.jetbrains.kotlin.ir.backend.js.utils.JsMainFunctionDetector
import org.jetbrains.kotlin.ir.backend.js.utils.NameTables
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.StageController
import org.jetbrains.kotlin.ir.declarations.stageController
import org.jetbrains.kotlin.ir.util.ExternalDependenciesGenerator
import org.jetbrains.kotlin.ir.util.generateTypicalIrProviderList
import org.jetbrains.kotlin.ir.util.patchDeclarationParents
import org.jetbrains.kotlin.library.KotlinLibrary
import org.jetbrains.kotlin.library.resolver.KotlinLibraryResolveResult
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtFile

class CompilerResult(
    val jsCode: String?,
    val dceJsCode: String?,
    val tsDefinitions: String? = null
)

fun compile(
    project: Project,
    files: List<KtFile>,
    configuration: CompilerConfiguration,
    phaseConfig: PhaseConfig,
    allDependencies: KotlinLibraryResolveResult,
    friendDependencies: List<KotlinLibrary>,
    mainArguments: List<String>?,
    exportedDeclarations: Set<FqName> = emptySet(),
    generateFullJs: Boolean = true,
    generateDceJs: Boolean = false
): CompilerResult {

    stageController = object : StageController {}

    val (moduleFragment, dependencyModules, irBuiltIns, symbolTable, deserializer) =
        loadIr(project, files, configuration, allDependencies, friendDependencies)

    val moduleDescriptor = moduleFragment.descriptor

    val mainFunction = JsMainFunctionDetector.getMainFunctionOrNull(moduleFragment)

    val context = JsIrBackendContext(moduleDescriptor, irBuiltIns, symbolTable, moduleFragment, exportedDeclarations, configuration)

    // Load declarations referenced during `context` initialization
    dependencyModules.forEach {
        val irProviders = generateTypicalIrProviderList(it.descriptor, irBuiltIns, symbolTable, deserializer)
        ExternalDependenciesGenerator(symbolTable, irProviders).generateUnboundSymbolsAsDependencies()
    }

    val irFiles = dependencyModules.flatMap { it.files } + moduleFragment.files

    moduleFragment.files.clear()
    moduleFragment.files += irFiles

    val irProvidersWithoutDeserializer = generateTypicalIrProviderList(moduleDescriptor, irBuiltIns, symbolTable)
    // Create stubs
    ExternalDependenciesGenerator(symbolTable, irProvidersWithoutDeserializer).generateUnboundSymbolsAsDependencies()
    moduleFragment.patchDeclarationParents()

    deserializer.finalizeExpectActualLinker()

    moveBodilessDeclarationsToSeparatePlace(context, moduleFragment)
    // TODO we should only generate tests for the current module
    generateTests(context, moduleFragment)

//    val phaserState = PhaserState<IrModuleFragment>()
//    loweringList.forEachIndexed { index, lowering ->
//        controller.currentStage = index + 1
//        lowering.modulePhase.invoke(phaseConfig, phaserState, context, moduleFragment)
//    }

//    jsPhases.invokeToplevel(phaseConfig, context, moduleFragment)

    val controller = MutableController(context)
    stageController = controller

    controller.currentStage = loweringList.size + 1

    eliminateDeadDeclarations(moduleFragment, context, mainFunction)

    stageController = object : StageController {
        override val currentStage: Int = controller.currentStage
    }

    val transformer = IrModuleToJsTransformer(context, mainFunction, mainArguments)
    return transformer.generateModule(moduleFragment, generateFullJs, generateDceJs)
}

fun generateJsCode(
    context: JsIrBackendContext,
    moduleFragment: IrModuleFragment,
    nameTables: NameTables
): String {
    moveBodilessDeclarationsToSeparatePlace(context, moduleFragment)
    jsPhases.invokeToplevel(PhaseConfig(jsPhases), context, moduleFragment)

    val transformer = IrModuleToJsTransformer(context, null, null, true, nameTables)
    return transformer.generateModule(moduleFragment).jsCode!!
}
