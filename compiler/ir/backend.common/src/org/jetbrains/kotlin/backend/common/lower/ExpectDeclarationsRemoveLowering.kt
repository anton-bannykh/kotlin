/*
 * Copyright 2010-2018 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.common.lower

import org.jetbrains.kotlin.backend.common.BackendContext
import org.jetbrains.kotlin.backend.common.DeclarationTransformer
import org.jetbrains.kotlin.backend.common.FileLoweringPass
import org.jetbrains.kotlin.descriptors.MemberDescriptor
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.impl.IrExpressionBodyImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrGetValueImpl
import org.jetbrains.kotlin.ir.symbols.IrValueParameterSymbol
import org.jetbrains.kotlin.ir.symbols.IrValueSymbol
import org.jetbrains.kotlin.ir.util.ExpectDeclarationRemover
import org.jetbrains.kotlin.ir.util.ReferenceSymbolTable
import org.jetbrains.kotlin.ir.util.patchDeclarationParents
import org.jetbrains.kotlin.ir.util.referenceFunction
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import org.jetbrains.kotlin.resolve.descriptorUtil.module
import org.jetbrains.kotlin.resolve.multiplatform.ExpectedActualResolver
import org.jetbrains.kotlin.resolve.multiplatform.ExpectedActualResolver.findCompatibleActualForExpected

/**
 * This pass removes all declarations with `isExpect == true`.
 */
class ExpectDeclarationsRemoveLowering(val context: BackendContext) : DeclarationTransformer {

    private val symbolTable: ReferenceSymbolTable = context.ir.symbols.externalSymbolTable

    override fun transformFlat(declaration: IrDeclaration): List<IrDeclaration>? {
        val descriptor = declaration.descriptor
        if (descriptor is MemberDescriptor && descriptor.isExpect) {
            return emptyList()
        }

        if (declaration is IrValueParameter) {
            // Keep actual default value if present. They are generally not allowed but can be suppressed with
            // @Suppress("ACTUAL_FUNCTION_WITH_DEFAULT_ARGUMENTS")
            if (declaration.defaultValue != null) {
                return null
            }

            val function = declaration.parent as IrFunction ?: return null

            if (!function.descriptor.isActual) return null

            val index = declaration.index
            assert(function.valueParameters[index] == declaration)

            val expectParameter = function.findExpectForActual().valueParameters[index]

            val defaultValue = expectParameter.defaultValue ?: return null

            defaultValue.expression.let { originalDefault ->
                declaration.defaultValue = IrExpressionBodyImpl(originalDefault.startOffset, originalDefault.endOffset) {
                    expression = originalDefault.remapExpectValueSymbols()
                }
            }
        }

        return null
    }

    private fun IrFunction.findActualForExpected(): IrFunction =
        symbolTable.referenceFunction(descriptor.findActualForExpect()).owner

    private fun IrFunction.findExpectForActual(): IrFunction =
        symbolTable.referenceFunction(descriptor.findActualForExpect()).owner

    private fun IrClass.findActualForExpected(): IrClass =
        symbolTable.referenceClass(descriptor.findActualForExpect()).owner

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
