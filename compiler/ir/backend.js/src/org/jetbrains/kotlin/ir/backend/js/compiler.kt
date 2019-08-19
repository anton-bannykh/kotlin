/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.backend.js

import com.google.common.collect.Sets
import com.intellij.openapi.project.Project
import org.jetbrains.kotlin.backend.common.phaser.PhaseConfig
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.descriptors.PropertyDescriptor
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.backend.js.transformers.irToJs.IrModuleToJsTransformer
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrSymbol
import org.jetbrains.kotlin.ir.types.classifierOrNull
import org.jetbrains.kotlin.ir.util.ExternalDependenciesGenerator
import org.jetbrains.kotlin.ir.util.IrDeserializer
import org.jetbrains.kotlin.ir.util.SymbolTable
import org.jetbrains.kotlin.ir.visitors.IrElementVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import org.jetbrains.kotlin.psi.KtFile
import java.util.*

val compilationCache = mutableMapOf<KlibModuleRef, CacheInfo>()

val dataCache = mutableMapOf<ModuleDescriptor, ContextData>()

val intrinsicsCache = mutableMapOf<KlibModuleRef, JsIntrinsics>()

val lazyLoad = true

val icOn = true

fun compile(
    project: Project,
    files: List<KtFile>,
    configuration: CompilerConfiguration,
    phaseConfig: PhaseConfig,
    immediateDependencies: List<KlibModuleRef>,
    allDependencies: List<KlibModuleRef>
): String {
    try {

        if (!icOn) {
            compilationCache.clear()
            dataCache.clear()
            intrinsicsCache.clear()
        }

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

        val dependencyGenerator = ExternalDependenciesGenerator(
            moduleDescriptor,
            symbolTable,
            irBuiltIns,
            deserializer = DeserializerProxy
        )

        stageController.dependencyGenerator = dependencyGenerator

        val context =
            JsIrBackendContext(moduleDescriptor, irBuiltIns, jsIntrinsics, symbolTable, moduleFragment, configuration, stageController)

        if (!lazyLoad) {
            dependencyGenerator.generateUnboundSymbolsAsDependencies()
        }

        stageController.bodiesEnabled = false

        var start = System.currentTimeMillis()

        stageController.invokeTopLevel(phaseConfig, moduleFragment, dependencyModules)

        totalTime += System.currentTimeMillis() - start
        ++testCnt

        println("#$testCnt: ${totalTime / testCnt}ms")
        println("main: ${mainTime / testCnt}ms")
        println("LLC: ${lazyLowerCalls / testCnt}; LLI: ${lazyLowerIteration / testCnt}; ALI: ${actualLoweringInvocations / testCnt}")
        println("LLCP: ${lazyLowerCalls * 100 / actualLoweringInvocations / 100.0}; LLIP: ${lazyLowerIteration * 100 / actualLoweringInvocations / 100.0}")
        println()

        stageController.bodiesEnabled = true

        // TODO traverse all IR
        generateTests(context, dataMap[moduleDescriptor]!!, dependencyModules + moduleFragment, phaseConfig)

        stageController.freeze()


        val usefulDeclarations = usefulDeclarations(moduleFragment)

        // TODO traverse all IR
        val jsProgram = IrModuleToJsTransformer(context, dataMap, usefulDeclarations).generateModule(dependencyModules + moduleFragment)

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
object DeserializerProxy : IrDeserializer {
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

var testCnt = 0
var totalTime = 0L
var mainTime = 0L

var lazyLowerCalls = 0L
var lazyLowerIteration = 0L
var actualLoweringInvocations = 0L

fun usefulDeclarations(module: IrModuleFragment): Set<IrDeclaration> {
    val queue = ArrayDeque<IrDeclaration>()
    val result = Sets.newIdentityHashSet<IrDeclaration>()

    fun IrDeclaration.enqueue() {
        if (this !in result) {
            result.add(this)
            queue.addLast(this)
        }
    }

    for (file in module.files) {
        for (declaration in file.declarations) {
            declaration.acceptVoid(object : IrElementVisitorVoid {
                override fun visitElement(element: IrElement) {
                    element.acceptChildrenVoid(this)
                }

                override fun visitBody(body: IrBody) {
                    // Skip
                }

                override fun visitDeclaration(declaration: IrDeclaration) {
                    declaration.enqueue()
                }
            })
        }
    }

    while (queue.isNotEmpty()) {
        val declaration = queue.pollFirst()

        if (declaration is IrClass) {
            declaration.superTypes.forEach {
                (it.classifierOrNull as? IrClassSymbol)?.owner?.enqueue()
            }
            declaration.declarations.filter { it is IrConstructor && it.isPrimary }.forEach { it.enqueue() }
        }

        // TODO overrides

        val body = when (declaration) {
            is IrFunction -> declaration.body
            is IrField -> declaration.initializer
            is IrVariable -> declaration.initializer
            else -> null
        }

        if (body != null) {
            body.acceptVoid(object : IrElementVisitorVoid {
                override fun visitElement(element: IrElement) {
                    element.acceptChildrenVoid(this)
                }

                override fun visitDelegatingConstructorCall(expression: IrDelegatingConstructorCall) {
                    expression.symbol.owner.enqueue()
                }

                override fun visitCall(expression: IrCall) {
                    expression.symbol.owner.enqueue()
                }

                override fun visitGetField(expression: IrGetField) {
                    expression.symbol.owner.enqueue()
                }

                override fun visitSetField(expression: IrSetField) {
                    expression.symbol.owner.enqueue()
                }

                override fun visitDeclaration(declaration: IrDeclaration) {
                    declaration.enqueue()
                }
            })
        }
    }

    return result
}