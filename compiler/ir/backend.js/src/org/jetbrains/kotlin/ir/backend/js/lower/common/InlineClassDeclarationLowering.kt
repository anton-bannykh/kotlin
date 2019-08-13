/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.backend.js.lower.common

import org.jetbrains.kotlin.backend.common.BodyLoweringPass
import org.jetbrains.kotlin.backend.common.ClassLoweringPass
import org.jetbrains.kotlin.backend.common.descriptors.WrappedSimpleFunctionDescriptor
import org.jetbrains.kotlin.backend.common.ir.copyTo
import org.jetbrains.kotlin.backend.common.ir.copyTypeParametersFrom
import org.jetbrains.kotlin.backend.common.lower.createIrBuilder
import org.jetbrains.kotlin.backend.common.lower.irBlock
import org.jetbrains.kotlin.backend.common.lower.irBlockBody
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.backend.js.JsIrBackendContext
import org.jetbrains.kotlin.ir.backend.js.getOrPut
import org.jetbrains.kotlin.ir.backend.js.mapping
import org.jetbrains.kotlin.ir.builders.*
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.declarations.impl.IrFunctionImpl
import org.jetbrains.kotlin.ir.declarations.impl.MappingKey
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.expressions.impl.IrBlockBodyImpl
import org.jetbrains.kotlin.ir.symbols.IrFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.impl.IrSimpleFunctionSymbolImpl
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import org.jetbrains.kotlin.name.Name

private const val INLINE_CLASS_IMPL_SUFFIX = "-impl"

private var IrFunction.newFunction by mapping(object : MappingKey<IrFunction, IrSimpleFunction>{})

private var IrSimpleFunction.originalFunction by mapping(object : MappingKey<IrSimpleFunction, IrFunction>{})

private var IrFunction.transformedFunction by mapping(object : MappingKey<IrFunction, IrSimpleFunction>{})

// TODO: Support incremental compilation
class InlineClassLowering(val context: JsIrBackendContext) {
    val inlineClassDeclarationLowering = object : ClassLoweringPass {
        override fun lower(irClass: IrClass) {
            if (!irClass.isInline) return

            // TODO removes declarations, which are used later on
            irClass.transformDeclarationsFlat { declaration ->
                when (declaration) {
                    is IrConstructor -> transformConstructor(declaration)
                    is IrSimpleFunction -> transformMethodFlat(declaration)
                    is IrProperty -> listOf(declaration)  // Getters and setters should be flattened
                    is IrField -> listOf(declaration)
                    is IrClass -> listOf(declaration)
                    else -> error("Unexpected declaration: $declaration")
                }
            }
        }

        private fun transformConstructor(irConstructor: IrConstructor): List<IrDeclaration> {
            if (irConstructor.isPrimary) return listOf(irConstructor)

            // Secondary constructors are lowered into static function
            val result = getOrCreateStaticMethod(irConstructor).owner

            result.body = irConstructor.body
            irConstructor.body = IrBlockBodyImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET)

            irConstructor.newFunction = result
            result.originalFunction = irConstructor

            return listOf()
        }

        private fun transformMethodFlat(function: IrSimpleFunction): List<IrDeclaration> {
            // TODO: Support fake-overridden methods without boxing
            if (function.isStaticMethodOfClass || !function.isReal)
                return listOf(function)


            val staticMethod = getOrCreateStaticMethod(function).owner

            staticMethod.body = function.body
            function.body = IrBlockBodyImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET)

            function.newFunction = staticMethod
            staticMethod.originalFunction = function

            if (function.overriddenSymbols.isEmpty())  // Function is used only in unboxed context
                return listOf()

            return listOf(function)
        }
    }

    val inlineClassDeclarationBodyLowering = object : BodyLoweringPass {
        override fun lower(irBody: IrBody, container: IrDeclaration) {
            if (container !is IrSimpleFunction) return

            container.originalFunction?.let {
                if (it is IrConstructor) {
                    transformConstructorBody(it, container)
                } else {
                    transformMethodBodyFlat(it as IrSimpleFunction, container)
                }
            }

            container.newFunction?.let { staticMethod ->
                delegateToStaticMethod(container, staticMethod)
            }
        }

        private fun transformConstructorBody(irConstructor: IrConstructor, staticMethod: IrSimpleFunction) {
            if (irConstructor.isPrimary) return // TODO error() maybe?

            val irClass = irConstructor.parentAsClass

            val irBody = staticMethod.body as IrBlockBody
            val oldStatements = ArrayList(irBody.statements)

            irBody.statements.clear()

            // Copied and adapted from Kotlin/Native InlineClassTransformer
            irBody.statements += context.createIrBuilder(staticMethod.symbol).irBlockBody(staticMethod) {

                // Secondary ctors of inline class must delegate to some other constructors.
                // Use these delegating call later to initialize this variable.
                lateinit var thisVar: IrVariable
                val parameterMapping = staticMethod.valueParameters.associateBy {
                    irConstructor.valueParameters[it.index].symbol
                }

                oldStatements.forEach { statement ->
                    +statement.transform(object : IrElementTransformerVoid() {
                        override fun visitDelegatingConstructorCall(expression: IrDelegatingConstructorCall): IrExpression {
                            expression.transformChildrenVoid()
                            return irBlock(expression) {
                                thisVar = createTmpVariable(
                                    expression,
                                    irType = irClass.defaultType
                                )
                                thisVar.parent = staticMethod
                            }
                        }

                        override fun visitGetValue(expression: IrGetValue): IrExpression {
                            expression.transformChildrenVoid()
                            if (expression.symbol == irClass.thisReceiver?.symbol) {
                                return irGet(thisVar)
                            }

                            parameterMapping[expression.symbol]?.let { return irGet(it) }
                            return expression
                        }

                        override fun visitDeclaration(declaration: IrDeclaration): IrStatement {
                            declaration.transformChildrenVoid(this)
                            if (declaration.parent == irConstructor)
                                declaration.parent = staticMethod
                            return declaration
                        }

                        override fun visitReturn(expression: IrReturn): IrExpression {
                            expression.transformChildrenVoid()
                            if (expression.returnTargetSymbol == irConstructor.symbol) {
                                return irReturn(irBlock(expression.startOffset, expression.endOffset) {
                                    +expression.value
                                    +irGet(thisVar)
                                })
                            }

                            return expression
                        }

                    }, null)
                }
                +irReturn(irGet(thisVar))
            }.statements
        }

        private fun transformMethodBodyFlat(function: IrSimpleFunction, staticMethod: IrSimpleFunction) {
            // TODO: Support fake-overridden methods without boxing
            if (function.isStaticMethodOfClass || !function.isReal) return // TODO error()

            // Move function body to static method, transforming value parameters and nested declarations
            staticMethod.body!!.transformChildrenVoid(object : IrElementTransformerVoid() {
                override fun visitDeclaration(declaration: IrDeclaration): IrStatement {
                    declaration.transformChildrenVoid(this)
                    if (declaration.parent == function)
                        declaration.parent = staticMethod

                    return declaration
                }

                override fun visitGetValue(expression: IrGetValue): IrExpression {
                    val valueDeclaration = expression.symbol.owner as? IrValueParameter ?: return super.visitGetValue(expression)

                    return context.createIrBuilder(staticMethod.symbol).irGet(
                        when (valueDeclaration) {
                            function.dispatchReceiverParameter, function.parentAsClass.thisReceiver ->
                                staticMethod.valueParameters[0]

                            function.extensionReceiverParameter ->
                                staticMethod.extensionReceiverParameter!!

                            in function.valueParameters ->
                                staticMethod.valueParameters[valueDeclaration.index + 1]

                            else -> return expression
                        }
                    )
                }
            })
        }

        private fun delegateToStaticMethod(function: IrSimpleFunction, staticMethod: IrSimpleFunction) {
            // Delegate original function to static implementation
            (function.body as IrBlockBody).statements += context.createIrBuilder(function.symbol).irBlockBody {
                +irReturn(
                    irCall(staticMethod).apply {
                        val parameters =
                            listOf(function.dispatchReceiverParameter!!) + function.valueParameters

                        for ((index, valueParameter) in parameters.withIndex()) {
                            putValueArgument(index, irGet(valueParameter))
                        }

                        extensionReceiver = function.extensionReceiverParameter?.let { irGet(it) }
                    }
                )
            }.statements
        }
    }

    val inlineClassUsageLowering = object : BodyLoweringPass {
        override fun lower(irBody: IrBody, container: IrDeclaration) {
            irBody.transformChildrenVoid(object : IrElementTransformerVoid() {

                override fun visitCall(call: IrCall): IrExpression {
                    call.transformChildrenVoid(this)
                    val function = call.symbol.owner
                    if (function.parent !is IrClass ||
                        function.isStaticMethodOfClass ||
                        !function.parentAsClass.isInline ||
                        (function is IrSimpleFunction && !function.isReal) ||
                        (function is IrConstructor && function.isPrimary)
                    ) {
                        return call
                    }

                    return irCall(call, getOrCreateStaticMethod(function), dispatchReceiverAsFirstArgument = (function is IrSimpleFunction))
                }

                override fun visitDelegatingConstructorCall(call: IrDelegatingConstructorCall): IrExpression {
                    call.transformChildrenVoid(this)
                    val function = call.symbol.owner
                    val klass = function.parentAsClass
                    return when {
                        !klass.isInline -> call
                        function.isPrimary -> irCall(call, function)
                        else -> irCall(call, getOrCreateStaticMethod(function)).apply {
                            (0 until call.valueArgumentsCount).forEach {
                                putValueArgument(it, call.getValueArgument(it)!!)
                            }
                        }
                    }
                }
            })
        }
    }

    private fun getOrCreateStaticMethod(function: IrFunction): IrSimpleFunctionSymbol =
        function::transformedFunction.getOrPut {
            createStaticBodilessMethod(function).also {
                function.parentAsClass.declarations.add(it)
            }
        }.symbol

    private fun Name.toInlineClassImplementationName() = when {
        isSpecial -> Name.special(asString() + INLINE_CLASS_IMPL_SUFFIX)
        else -> Name.identifier(asString() + INLINE_CLASS_IMPL_SUFFIX)
    }

    private fun createStaticBodilessMethod(function: IrFunction): IrSimpleFunction {
        val descriptor = WrappedSimpleFunctionDescriptor()

        return IrFunctionImpl(
            function.startOffset,
            function.endOffset,
            function.origin,
            IrSimpleFunctionSymbolImpl(descriptor),
            function.name.toInlineClassImplementationName(),
            function.visibility,
            Modality.FINAL,
            function.returnType,
            function.isInline,
            function.isExternal,
            (function is IrSimpleFunction && function.isTailrec),
            (function is IrSimpleFunction && function.isSuspend)
        ).apply {
            descriptor.bind(this)
            copyTypeParametersFrom(function)
            annotations += function.annotations
            dispatchReceiverParameter = null
            extensionReceiverParameter = function.extensionReceiverParameter?.copyTo(this)
            if (function is IrSimpleFunction) {
                valueParameters.add(function.dispatchReceiverParameter!!.let { p -> p.copyTo(this, index = p.index + 1) })
                valueParameters += function.valueParameters.map { p -> p.copyTo(this, index = p.index + 1) }
            } else {
                valueParameters += function.valueParameters.map { p -> p.copyTo(this) }
            }
            parent = function.parent
            assert(isStaticMethodOfClass)
        }
    }
}
