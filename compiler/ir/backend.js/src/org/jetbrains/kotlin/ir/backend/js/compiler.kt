/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.backend.js

import com.intellij.openapi.project.Project
import org.jetbrains.kotlin.backend.common.phaser.PhaseConfig
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.ir.backend.js.transformers.irToJs.IrModuleToJsTransformer
import org.jetbrains.kotlin.ir.util.ExternalDependenciesGenerator
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

    stageController.bodiesEnabled = true

    val (moduleFragment, dependencyModules, irBuiltIns, symbolTable, deserializer) =
        loadIr(project, files, configuration, immediateDependencies, allDependencies)

    val moduleDescriptor = moduleFragment.descriptor

    val dataMap = mutableMapOf(moduleDescriptor to ContextData(moduleFragment))
    dependencyModules.forEach {
        dataMap[it.descriptor] = ContextData(it)
    }

    stageController.dataMap = dataMap

    stageController.dependencyGenerator = ExternalDependenciesGenerator(
        moduleDescriptor,
        symbolTable,
        irBuiltIns,
        deserializer = deserializer
    )

    val context = JsIrBackendContext(moduleDescriptor, irBuiltIns, JsIntrinsics(irBuiltIns), symbolTable, moduleFragment, configuration, stageController)

    stageController.bodiesEnabled = false

    stageController.invokeTopLevel(phaseConfig, moduleFragment, dependencyModules)

    stageController.bodiesEnabled = true

    // TODO traverse all IR
    generateTests(context, dataMap[moduleDescriptor]!!, dependencyModules + moduleFragment, phaseConfig)

    stageController.freeze()

    // TODO traverse all IR
    val jsProgram = IrModuleToJsTransformer(context, dataMap).generateModule(dependencyModules + moduleFragment)

    stageController.deinit()

    return jsProgram.toString()
}