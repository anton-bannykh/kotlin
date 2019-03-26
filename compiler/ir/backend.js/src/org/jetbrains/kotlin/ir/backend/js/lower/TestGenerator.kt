/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.backend.js.lower

import org.jetbrains.kotlin.backend.common.FileLoweringPass
import org.jetbrains.kotlin.backend.common.ir.isExpect
import org.jetbrains.kotlin.backend.common.serialization.fqNameSafe
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.ir.backend.js.JsIrBackendContext
import org.jetbrains.kotlin.ir.backend.js.ir.JsIrBuilder
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.types.impl.IrSimpleTypeImpl
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.getPackageFragment
import org.jetbrains.kotlin.ir.util.isEffectivelyExternal
import org.jetbrains.kotlin.name.FqName

class TestGenerator(val context: JsIrBackendContext) : FileLoweringPass {

    override fun lower(irFile: IrFile) {
        irFile.declarations.forEach {
            if (it is IrClass) {
                generateTestCalls(it) { suiteForPackage(it.getPackageFragment()?.fqName ?: FqName.ROOT) }
            }

            // TODO top-level functions
        }
    }

    private val packageSuites = mutableMapOf<FqName, IrBlockBody>()

    private fun suiteForPackage(fqName: FqName): IrBlockBody = packageSuites.getOrPut(fqName) {
        context.suiteFun!!.createInvocation(fqName.asString(), context.testContainer.body)
    }

    private fun IrSimpleFunctionSymbol.createInvocation(
        name: String,
        parentBody: IrBlockBody,
        ignored: Boolean = false,
        initHook: (IrSimpleFunction, IrBlockBody) -> Unit = { _, _ -> }
    ): IrBlockBody {
        val body = JsIrBuilder.buildBlockBody(emptyList())

        val function = JsIrBuilder.buildFunction(
            "$name test fun",
            context.irBuiltIns.unitType,
            context.implicitDeclarationFile
        ).also {
            it.body = body
            context.implicitDeclarationFile.declarations += it
        }

        initHook(function, body)

        parentBody.statements += JsIrBuilder.buildCall(this).apply {
            putValueArgument(0, JsIrBuilder.buildString(context.irBuiltIns.stringType, name))
            putValueArgument(1, JsIrBuilder.buildBoolean(context.irBuiltIns.booleanType, ignored))

            val refType = IrSimpleTypeImpl(context.functionN(0), false, emptyList(), emptyList())
            putValueArgument(2, JsIrBuilder.buildFunctionReference(refType, function.symbol))
        }

        return body
    }

    private fun generateTestCalls(irClass: IrClass, parentBody: () -> IrBlockBody) {
        if (irClass.modality == Modality.ABSTRACT || irClass.isEffectivelyExternal() || irClass.isExpect) return

        val suiteFunBody by lazy { context.suiteFun!!.createInvocation(irClass.name.asString(), parentBody(), irClass.isIgnored) }

        val beforeFunctions = irClass.declarations.filterIsInstance<IrSimpleFunction>().filter { it.isBefore }
        val afterFunctions = irClass.declarations.filterIsInstance<IrSimpleFunction>().filter { it.isAfter }

        irClass.declarations.forEach {
            when {
                it is IrClass ->
                    generateTestCalls(it) { suiteFunBody }

                it is IrSimpleFunction && it.isTest ->
                    generateCodeForTestMethod(it, beforeFunctions, afterFunctions, irClass) { suiteFunBody }

                else -> emptyList()
            }
        }
    }

    private fun generateCodeForTestMethod(
        testFun: IrSimpleFunction,
        beforeFuns: List<IrSimpleFunction>,
        afterFuns: List<IrSimpleFunction>,
        irClass: IrClass,
        parentBody: () -> IrBlockBody
    ) {
        context.testFun!!.createInvocation(testFun.name.asString(), parentBody(), testFun.isIgnored) { fn, body ->
            val classVal = JsIrBuilder.buildVar(irClass.defaultType, fn, initializer = irClass.instance())

            body.statements += classVal

            body.statements += beforeFuns.map {
                JsIrBuilder.buildCall(it.symbol).apply {
                    dispatchReceiver = JsIrBuilder.buildGetValue(classVal.symbol)
                }
            }

            val returnStatement = JsIrBuilder.buildReturn(
                fn.symbol,
                JsIrBuilder.buildCall(testFun.symbol).apply {
                    dispatchReceiver = JsIrBuilder.buildGetValue(classVal.symbol)
                },
                context.irBuiltIns.unitType
            )

            if (afterFuns.isEmpty()) {
                body.statements += returnStatement
            } else {
                body.statements += JsIrBuilder.buildTry(context.irBuiltIns.unitType).apply {
                    tryResult = returnStatement
                    finallyExpression = JsIrBuilder.buildComposite(context.irBuiltIns.unitType).apply {
                        statements += afterFuns.map {
                            JsIrBuilder.buildCall(it.symbol).apply {
                                dispatchReceiver = JsIrBuilder.buildGetValue(classVal.symbol)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun IrClass.instance(): IrExpression {
        return if (kind == ClassKind.OBJECT) {
            JsIrBuilder.buildGetObjectValue(defaultType, symbol)
        } else {
            declarations.asSequence().filterIsInstance<IrConstructor>().single { it.isPrimary }.let { constructor ->
                JsIrBuilder.buildCall(constructor.symbol).also {
                    if (isInner) {
                        it.dispatchReceiver = (parent as IrClass).instance()
                    }
                }
            }
        }
    }

    private val IrAnnotationContainer.isTest
        get() = annotationFinder("Test", "kotlin.test")

    private val IrAnnotationContainer.isIgnored
        get() = annotationFinder("Ignore", "kotlin.test")

    private val IrAnnotationContainer.isBefore
        get() = annotationFinder("BeforeTest", "kotlin.test")

    private val IrAnnotationContainer.isAfter
        get() = annotationFinder("AfterTest", "kotlin.test")

    private fun IrAnnotationContainer.annotationFinder(shortName: String, vararg packages: String) = packages.any { packageName ->
        annotations.any { it.symbol.owner.parent.fqNameSafe == FqName("$packageName.$shortName") }
    }
}
