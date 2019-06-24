/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.backend.js

import com.intellij.openapi.project.Project
import org.jetbrains.kotlin.backend.common.phaser.PhaseConfig
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.ir.backend.js.transformers.irToJs.IrModuleToJsTransformer
import org.jetbrains.kotlin.ir.declarations.NoopController
import org.jetbrains.kotlin.ir.declarations.impl.IrFileImpl
import org.jetbrains.kotlin.ir.util.DeclarationStubGenerator
import org.jetbrains.kotlin.ir.util.ExternalDependenciesGenerator
import org.jetbrains.kotlin.ir.util.patchDeclarationParents
import org.jetbrains.kotlin.psi.KtFile

fun compile(
    project: Project,
    files: List<KtFile>,
    configuration: CompilerConfiguration,
    phaseConfig: PhaseConfig,
    immediateDependencies: List<KlibModuleRef>,
    allDependencies: List<KlibModuleRef>
): String {
    val stageController = MutableController()

    val (moduleFragment, dependencyModules, irBuiltIns, symbolTable, deserializer) =
        loadIr(project, files, configuration, immediateDependencies, allDependencies)

    val moduleDescriptor = moduleFragment.descriptor

    val context = JsIrBackendContext(moduleDescriptor, irBuiltIns, symbolTable, moduleFragment, configuration, stageController)

    stageController.dependencyGenerator = ExternalDependenciesGenerator(
        moduleDescriptor,
        symbolTable,
        irBuiltIns,
        deserializer = deserializer
    )

    // Load declarations referenced during `context` initialization
//    dependencyModules.forEach {
//        ExternalDependenciesGenerator(
//            it.descriptor,
//            symbolTable,
//            irBuiltIns,
//            deserializer = deserializer
//        ).generateUnboundSymbolsAsDependencies()
//    }
//
    // TODO: check the order
    val irFiles = dependencyModules.flatMap { it.files } + moduleFragment.files

    moduleFragment.files.clear()
    moduleFragment.files += irFiles

    moduleFragment.patchDeclarationParents()

    stageController.invokeTopLevel(phaseConfig, moduleFragment)

    generateTests(context, moduleFragment, phaseConfig)

    stageController.freeze()

    val jsProgram = moduleFragment.accept(IrModuleToJsTransformer(context), null)

    stageController.deinit()

    return jsProgram.toString()
}