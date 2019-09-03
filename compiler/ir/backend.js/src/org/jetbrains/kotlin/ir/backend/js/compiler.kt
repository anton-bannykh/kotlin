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
import org.jetbrains.kotlin.ir.backend.js.utils.getJsNameOrKotlinName
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrSymbol
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.classifierOrFail
import org.jetbrains.kotlin.ir.types.classifierOrNull
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.ir.visitors.IrElementVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import org.jetbrains.kotlin.psi.KtFile
import java.util.*

val compilationCache = mutableMapOf<KlibModuleRef, CacheInfo>()

val dataCache = mutableMapOf<ModuleDescriptor, ContextData>()

val intrinsicsCache = mutableMapOf<KlibModuleRef, JsIntrinsics>()

val lazyLoad = false

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
            } else ContextData(it)
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
            JsIrBackendContext(
                moduleDescriptor,
                irBuiltIns,
                jsIntrinsics,
                symbolTable,
                moduleFragment,
                configuration,
                stageController,
                dataMap
            )

        if (!lazyLoad) {
            dependencyGenerator.generateUnboundSymbolsAsDependencies()
        }

        stageController.bodiesEnabled = false

        var start = System.currentTimeMillis()

        val usefulDeclarations = stageController.invokeTopLevel(phaseConfig, moduleFragment, dependencyModules)

        val afterLowerings = System.currentTimeMillis()
        totalTime += afterLowerings - start
        ++testCnt

        stageController.bodiesEnabled = true

        // TODO traverse all IR
//        generateTests(context, dataMap[moduleDescriptor]!!, dependencyModules + moduleFragment, phaseConfig)

        stageController.freeze()


//        val usefulDeclarations = usefulDeclarations(moduleFragment, context, stageController)

        stageController.deinit()

        // TODO traverse all IR
        val jsProgram = IrModuleToJsTransformer(context, dataMap, usefulDeclarations).generateModule(dependencyModules + moduleFragment)

        ir2JsTime += System.currentTimeMillis() - afterLowerings


        println("#$testCnt: ${totalTime / testCnt}ms")
        println("main: ${mainTime / testCnt}ms")
        println("dce: ${dceTime / testCnt}ms")
        println("finishing: ${finishingTime / testCnt}ms")
        println("ir2Js: ${ir2JsTime / testCnt}ms")
        println("LLC: ${lazyLowerCalls / testCnt}; LLI: ${lazyLowerIteration / testCnt}; ALI: ${actualLoweringInvocations / testCnt}")
        println("LLCP: ${lazyLowerCalls * 100 / actualLoweringInvocations / 100.0}; LLIP: ${lazyLowerIteration * 100 / actualLoweringInvocations / 100.0}")
        println()


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
var dceTime = 0L
var finishingTime = 0L
var ir2JsTime = 0L
var bodiesTouchedCnt = 0

var lazyLowerCalls = 0L
var lazyLowerIteration = 0L
var actualLoweringInvocations = 0L

fun usefulDeclarations(module: IrModuleFragment, context: JsIrBackendContext, controller: MutableController): Set<IrDeclaration> {
    val queue = ArrayDeque<IrDeclaration>()
    val result = Sets.newIdentityHashSet<IrDeclaration>()
    val constructedClasses = Sets.newIdentityHashSet<IrClass>()

    fun IrDeclaration.enqueue() {
        if (this !in result) {
            result.add(this)
            queue.addLast(this)
            if (this is IrConstructor) {
                constructedClass.enqueue()
                constructedClasses += constructedClass
            }
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

//    context.irBuiltIns.anyClass.owner.declarations.forEach { it.enqueue() }

    val toStringMethod = stageController.withInitialIr { context.irBuiltIns.anyClass.owner.declarations.single { it.name.asString() == "toString" } }
    val equalsMethod = stageController.withInitialIr { context.irBuiltIns.anyClass.owner.declarations.single { it.name.asString() == "equals" } }

    // TODO Why? Seems like native exception constructors read message field
    stageController.withInitialIr {
        context.throwableClass.owner.declarations.filterIsInstance<IrSimpleFunction>().filter { it.name.asString() == "<get-message>" }
            .forEach { it.enqueue() }
        context.throwableClass.owner.declarations.filterIsInstance<IrProperty>().filter { it.name.asString() == "message" }
            .forEach { it.getter?.enqueue() }
        context.throwableClass.owner.declarations.filterIsInstance<IrSimpleFunction>().filter { it.name.asString() == "<get-cause>" }
            .forEach { it.enqueue() }
        context.throwableClass.owner.declarations.filterIsInstance<IrProperty>().filter { it.name.asString() == "cause" }
            .forEach { it.getter?.enqueue() }
    }

    fun IrOverridableDeclaration<*>.overridesUsefulFunction(): Boolean {
        return this.overriddenSymbols.any {
            (it.owner as? IrOverridableDeclaration<*>)?.let {
                it in result || it.overridesUsefulFunction()
            } ?: false
        }
    }

    while (queue.isNotEmpty()) {
        while (queue.isNotEmpty()) {
            val declaration = queue.pollFirst()

            if (declaration is IrClass) {
                declaration.superTypes.forEach {
                    (it.classifierOrNull as? IrClassSymbol)?.owner?.enqueue()
                }
            }

            if (declaration is IrSimpleFunction) {
                if (declaration.origin == IrDeclarationOrigin.FAKE_OVERRIDE) {
                    declaration.overriddenSymbols.forEach {
                        it.owner.enqueue()
                    }
                }
            }

            val body = when (declaration) {
                is IrFunction -> declaration.body
                is IrField -> declaration.initializer
                is IrVariable -> declaration.initializer
                else -> null
            }

            if (body != null) {
                controller.withBodies {
                    body.acceptVoid(object : IrElementVisitorVoid {
                        override fun visitElement(element: IrElement) {
                            element.acceptChildrenVoid(this)
                        }

                        override fun visitFunctionAccess(expression: IrFunctionAccessExpression) {
                            super.visitFunctionAccess(expression)

                            expression.symbol.owner.enqueue()
                        }

                        override fun visitVariableAccess(expression: IrValueAccessExpression) {
                            super.visitVariableAccess(expression)

                            expression.symbol.owner.enqueue()
                        }

                        override fun visitFieldAccess(expression: IrFieldAccessExpression) {
                            super.visitFieldAccess(expression)

                            expression.symbol.owner.enqueue()
                        }

                        override fun visitCall(expression: IrCall) {
                            super.visitCall(expression)

                            expression.superQualifierSymbol?.owner?.enqueue()

                            when (expression.symbol) {
                                context.libraryIntrinsics.jsBoxIntrinsic -> {
                                    val inlineClass = expression.getTypeArgument(0)!!.getInlinedClass()!!
                                    val constructor = inlineClass.declarations.filterIsInstance<IrConstructor>().single { it.isPrimary }
                                    constructor.enqueue()
                                }
                                context.libraryIntrinsics.jsClass -> {
                                    (expression.getTypeArgument(0)?.classifierOrNull as? IrClassSymbol)?.owner?.enqueue()
                                }
                                context.intrinsics.jsObjectCreate.symbol -> {
                                    val classToCreate = expression.getTypeArgument(0)!!.classifierOrFail.owner as IrClass
                                    classToCreate.enqueue()
                                    constructedClasses += classToCreate
                                }
                                context.libraryIntrinsics.jsEquals -> {
                                    equalsMethod.enqueue()
                                }
                                context.intrinsics.jsPlus -> {
                                    if (expression.getValueArgument(0)?.type?.classOrNull == context.irBuiltIns.stringClass) {
                                        toStringMethod.enqueue()
                                    }
                                }
                            }
                        }

                        override fun visitGetObjectValue(expression: IrGetObjectValue) {
                            super.visitGetObjectValue(expression)

                            expression.symbol.owner.let {
                                constructedClasses += it
                                it.constructors.find { it.isPrimary }?.enqueue()
                            }
                        }

                        override fun visitStringConcatenation(expression: IrStringConcatenation) {
                            super.visitStringConcatenation(expression)

                            toStringMethod.enqueue()
                        }
                    })
                }
            }
        }

        for (klass in constructedClasses) {
            for (declaration in klass.declarations) {
                if (declaration in result) continue

                if (declaration is IrOverridableDeclaration<*> && declaration.overridesUsefulFunction()) {
                    declaration.enqueue()
                }
                if (declaration is IrSimpleFunction && declaration.getJsNameOrKotlinName().asString() == "valueOf") {
                    declaration.enqueue()
                }
            }
        }
    }

    return result
}