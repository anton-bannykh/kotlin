/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.backend.js.lower.common

import org.jetbrains.kotlin.backend.common.BackendContext
import org.jetbrains.kotlin.backend.common.BodyLoweringPass
import org.jetbrains.kotlin.backend.common.DeclarationTransformer
import org.jetbrains.kotlin.backend.common.ir.DeclarationBiMapKey
import org.jetbrains.kotlin.descriptors.MemberDescriptor
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.backend.js.mapping
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.declarations.impl.MappingKey
import org.jetbrains.kotlin.ir.expressions.IrBody
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.impl.IrGetValueImpl
import org.jetbrains.kotlin.ir.symbols.IrValueParameterSymbol
import org.jetbrains.kotlin.ir.symbols.IrValueSymbol
import org.jetbrains.kotlin.ir.util.original
import org.jetbrains.kotlin.ir.util.referenceFunction
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.IrElementVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import org.jetbrains.kotlin.resolve.checkers.ExpectedActualDeclarationChecker
import org.jetbrains.kotlin.resolve.descriptorUtil.module
import org.jetbrains.kotlin.resolve.multiplatform.ExpectedActualResolver

private var IrValueParameter.originalValueParameter by mapping(object : MappingKey<IrValueParameter, IrValueParameter>{})

/**
 * This pass removes all declarations with `isExpect == true`.
 */
class ExpectDeclarationsRemoving(val context: BackendContext) : DeclarationTransformer {

    override fun transformFlat(declaration: IrDeclaration): List<IrDeclaration>? {
        // All declarations with `isExpect == true` are nested into a top-level declaration with `isExpect == true`.
        val descriptor = declaration.descriptor
        if (descriptor is MemberDescriptor && descriptor.isExpect) {
            copyDefaultArgumentsFromExpectToActual(declaration)
            return emptyList()
        } else {
            return null
        }
    }

    private fun copyDefaultArgumentsFromExpectToActual(declaration: IrDeclaration) {
        declaration.acceptVoid(object : IrElementVisitorVoid {
            override fun visitElement(element: IrElement) {
                element.acceptChildrenVoid(this)
            }

            override fun visitBody(body: IrBody) {
                // Stop
            }

            override fun visitValueParameter(declaration: IrValueParameter) {
                super.visitValueParameter(declaration)

                val defaultValue = declaration.defaultValue ?: return
                val function = declaration.parent as IrFunction

                val index = declaration.index
                assert(function.valueParameters[index] == declaration)

                if (function is IrConstructor &&
                    ExpectedActualDeclarationChecker.isOptionalAnnotationClass(
                        function.descriptor.constructedClass
                    )
                ) {
                    return
                }

                val actualParameter = function.findActualForExpected().valueParameters[index]

                // Keep actual default value if present. They are generally not allowed but can be suppressed with
                // @Suppress("ACTUAL_FUNCTION_WITH_DEFAULT_ARGUMENTS")
                if (actualParameter.defaultValue != null)
                    return

                actualParameter.originalValueParameter = declaration

                actualParameter.defaultValue = defaultValue
            }
        })
    }

    private fun IrFunction.findActualForExpected(): IrFunction =
        context.ir.symbols.externalSymbolTable.referenceFunction(descriptor.findActualForExpect()).owner

    private fun IrClass.findActualForExpected(): IrClass =
        context.ir.symbols.externalSymbolTable.referenceClass(descriptor.findActualForExpect()).owner

    private inline fun <reified T : MemberDescriptor> T.findActualForExpect() = with(ExpectedActualResolver) {
        val descriptor = this@findActualForExpect

        if (!descriptor.isExpect) error(this)

        findCompatibleActualForExpected(descriptor.module).singleOrNull() ?: error(descriptor)
    } as T
}



class ExpectDeclarationDefaultValueRemapping(val context: BackendContext) : BodyLoweringPass {

    override fun lower(irBody: IrBody, container: IrDeclaration) {
        if (container is IrValueParameter && container.originalValueParameter != null) {
            container.defaultValue?.let {
                it.expression = it.expression.remapExpectValueSymbols()
            }
        }
    }

    private fun IrFunction.findActualForExpected(): IrFunction =
        context.ir.symbols.externalSymbolTable.referenceFunction(descriptor.findActualForExpect()).owner

    private fun IrClass.findActualForExpected(): IrClass =
        context.ir.symbols.externalSymbolTable.referenceClass(descriptor.findActualForExpect()).owner

    private inline fun <reified T : MemberDescriptor> T.findActualForExpect() = with(ExpectedActualResolver) {
        val descriptor = this@findActualForExpect

        if (!descriptor.isExpect) error(this)

        findCompatibleActualForExpected(descriptor.module).singleOrNull() ?: error(descriptor)
    } as T

    private fun IrExpression.remapExpectValueSymbols(): IrExpression {
        return this.transform(object : IrElementTransformerVoid() {

            override fun visitGetValue(expression: IrGetValue): IrExpression {
                expression.transformChildrenVoid()
                val newValue = remapExpectValue(expression.symbol)
                    ?: return expression

                return IrGetValueImpl(
                    expression.startOffset,
                    expression.endOffset,
                    newValue.type,
                    newValue.symbol,
                    expression.origin
                )
            }
        }, data = null)
    }

    private fun remapExpectValue(symbol: IrValueSymbol): IrValueParameter? {
        if (symbol !is IrValueParameterSymbol) {
            return null
        }

        val parameter = symbol.owner
        val parent = parameter.parent

        return when (parent) {
            is IrClass -> {
                assert(parameter == parent.thisReceiver)
                parent.findActualForExpected().thisReceiver!!
            }

            is IrFunction -> when (parameter) {
                parent.dispatchReceiverParameter ->
                    parent.findActualForExpected().dispatchReceiverParameter!!
                parent.extensionReceiverParameter ->
                    parent.findActualForExpected().extensionReceiverParameter!!
                else -> {
                    assert(parent.valueParameters[parameter.index] == parameter)
                    parent.findActualForExpected().valueParameters[parameter.index]
                }
            }

            else -> error(parent)
        }
    }
}
