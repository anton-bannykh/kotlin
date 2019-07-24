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
import org.jetbrains.kotlin.ir.declarations.NoopController
import org.jetbrains.kotlin.ir.declarations.stageController
import org.jetbrains.kotlin.ir.descriptors.IrBuiltIns
import org.jetbrains.kotlin.ir.util.ExternalDependenciesGenerator
import org.jetbrains.kotlin.psi.KtFile

val compilationCache = mutableMapOf<KlibModuleRef, CacheInfo>()

val dataCache = mutableMapOf<ModuleDescriptor, ContextData>()

val intrinsicsCache = mutableMapOf<KlibModuleRef, JsIntrinsics>()

fun compile(
    project: Project,
    files: List<KtFile>,
    configuration: CompilerConfiguration,
    phaseConfig: PhaseConfig,
    immediateDependencies: List<KlibModuleRef>,
    allDependencies: List<KlibModuleRef>
): String {
    try {
        val stageController = MutableController()

        stageController.bodiesEnabled = true

        val (moduleFragment, dependencyModules, irBuiltIns, symbolTable, deserializer) =
            loadIr(project, files, configuration, immediateDependencies, allDependencies, compilationCache)

        val moduleDescriptor = moduleFragment.descriptor

        val dataMap = mutableMapOf(moduleDescriptor to ContextData(moduleFragment))
        dependencyModules.forEach {
            dataMap[it.descriptor] = if (it.name.asString() == "<JS_IR_RUNTIME>") {
                dataCache.getOrPut(it.descriptor) { ContextData(it) }
            } else ContextData((it))
        }

        val jsIntrinsics = allDependencies.filter { it.moduleName == "JS_IR_RUNTIME" }.single().let { stdlib ->
            intrinsicsCache.getOrPut(stdlib) {
                JsIntrinsics(compilationCache[stdlib]!!.bultins)
            }
        }

        stageController.dataMap = dataMap

        stageController.dependencyGenerator = ExternalDependenciesGenerator(
            moduleDescriptor,
            symbolTable,
            irBuiltIns,
            deserializer = deserializer
        )

        val context =
            JsIrBackendContext(moduleDescriptor, irBuiltIns, jsIntrinsics, symbolTable, moduleFragment, configuration, stageController)

        stageController.bodiesEnabled = false

        stageController.invokeTopLevel(phaseConfig, moduleFragment, dependencyModules)

        stageController.bodiesEnabled = true

        // TODO traverse all IR
        generateTests(context, dataMap[moduleDescriptor]!!, dependencyModules + moduleFragment, phaseConfig)

        stageController.freeze()

        // TODO traverse all IR
        val jsProgram = IrModuleToJsTransformer(context, dataMap).generateModule(dependencyModules + moduleFragment)

        stageController.deinit()

        dependencyModules.forEach {
            if (it.name.asString() == "<JS_IR_RUNTIME>") {
                val klib = allDependencies.filter { it.moduleName == "JS_IR_RUNTIME" }.single()
                compilationCache[klib]!!.let { (descriptor, _, sc) ->
                    val symbols = sc.deserializedSymbols
                    val moduleDeserializer = deserializer.deserializersForModules[descriptor]!!

                    for ((k, s) in moduleDeserializer.localDeserializedSymbols) {
                        symbols[k] = s
                    }

                    sc.deserializedTopLevels += moduleDeserializer.localDeserializedTopLevels
                }
            }
        }

        return jsProgram.toString()
    } finally {
        stageController = NoopController()
    }
}