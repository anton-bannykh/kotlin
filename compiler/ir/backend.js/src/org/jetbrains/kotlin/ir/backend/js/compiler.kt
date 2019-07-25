/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.backend.js

import com.intellij.openapi.project.Project
import org.jetbrains.kotlin.backend.common.phaser.PhaseConfig
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.descriptors.PropertyDescriptor
import org.jetbrains.kotlin.ir.backend.js.transformers.irToJs.IrModuleToJsTransformer
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.NoopController
import org.jetbrains.kotlin.ir.declarations.stageController
import org.jetbrains.kotlin.ir.descriptors.IrBuiltIns
import org.jetbrains.kotlin.ir.symbols.IrSymbol
import org.jetbrains.kotlin.ir.util.ExternalDependenciesGenerator
import org.jetbrains.kotlin.ir.util.IrDeserializer
import org.jetbrains.kotlin.ir.util.SymbolTable
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

        stageController.deserializer = deserializer

        stageController.dependencyGenerator = ExternalDependenciesGenerator(
            moduleDescriptor,
            symbolTable,
            irBuiltIns,
            deserializer = DeserializerProxy
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

        // Prevent memory leaks through LazyIr -> StubGenerator -> SymbolTable
        ((symbolTable as CompositeSymbolTable).moduleMap as MutableMap<ModuleDescriptor, SymbolTable>).let { map ->
            map.entries.retainAll { (k, _) -> k.name.asString() == "<JS_IR_RUNTIME>" }
        }

        return jsProgram.toString()
    } finally {
        stageController = NoopController()
    }
}

// Prevent memory leaks through LazyIr -> StubGenerator -> IrDeserializer
object DeserializerProxy: IrDeserializer {
    val deserializer: IrDeserializer?
        get() = (stageController as? MutableController)?.deserializer


    override fun findDeserializedDeclaration(symbol: IrSymbol): IrDeclaration? {
        return deserializer?.findDeserializedDeclaration(symbol)
    }

    override fun findDeserializedDeclaration(propertyDescriptor: PropertyDescriptor): IrProperty? {
        return deserializer?.findDeserializedDeclaration(propertyDescriptor)
    }

    override fun declareForwardDeclarations() {
        deserializer?.declareForwardDeclarations()
    }
}