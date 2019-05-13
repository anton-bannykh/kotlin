/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.backend.js

import com.intellij.openapi.project.Project
import org.jetbrains.kotlin.backend.common.phaser.PhaseConfig
import org.jetbrains.kotlin.backend.common.phaser.invokeToplevel
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.ir.backend.js.transformers.irToJs.IrModuleToJsTransformer
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.impl.IrFileImpl
import org.jetbrains.kotlin.ir.declarations.StageController
import org.jetbrains.kotlin.ir.util.ExternalDependenciesGenerator
import org.jetbrains.kotlin.ir.util.patchDeclarationParents
import org.jetbrains.kotlin.psi.KtFile

class MutableController: StageController {
    override var currentStage: Int = 0

    override fun lowerUpTo(file: IrFile, stageNonInclusive: Int) {
        val loweredUpTo = (file as? IrFileImpl)?.loweredUpTo ?: 0
        for (i in loweredUpTo + 1 until stageNonInclusive) {
            context.withStage(i) {
                perFilePhaseList[i - 1].forEach { it(context).lower(file) }
            }
            (file as? IrFileImpl)?.loweredUpTo = i
        }
    }
}

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
        loadIr(project, files, configuration, immediateDependencies, allDependencies, stageController)

    val moduleDescriptor = moduleFragment.descriptor

    val context = JsIrBackendContext(moduleDescriptor, irBuiltIns, symbolTable, moduleFragment, configuration, stageController)

    // Load declarations referenced during `context` initialization
    dependencyModules.forEach {
        ExternalDependenciesGenerator(
            it.descriptor,
            symbolTable,
            irBuiltIns,
            deserializer = deserializer
        ).generateUnboundSymbolsAsDependencies()
    }

    // TODO: check the order
    val irFiles = dependencyModules.flatMap { it.files } + moduleFragment.files

    // Check all controllers are the correct ones
    irFiles.forEach { file ->
        (file as? IrFileImpl)?.let { fileImpl ->
            check(fileImpl.stageController === stageController)
        }
    }

    moduleFragment.files.clear()
    moduleFragment.files += irFiles

    // Create stubs
    ExternalDependenciesGenerator(
        moduleDescriptor = moduleDescriptor,
        symbolTable = symbolTable,
        irBuiltIns = irBuiltIns
    ).generateUnboundSymbolsAsDependencies()
    moduleFragment.patchDeclarationParents()

    jsPhases.invokeToplevel(phaseConfig, context, moduleFragment)

    generateTests(context, moduleFragment)

    stageController.currentStage = perFilePhaseList.size + 1
    val jsProgram = moduleFragment.accept(IrModuleToJsTransformer(context), null)
    return jsProgram.toString()
}