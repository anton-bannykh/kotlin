/*
 * Copyright 2010-2018 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.common.lower

import org.jetbrains.kotlin.backend.common.BackendContext
import org.jetbrains.kotlin.backend.common.DeclarationTransformer
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.MemberDescriptor
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.impl.IrExpressionBodyImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrGetValueImpl
import org.jetbrains.kotlin.ir.symbols.IrValueParameterSymbol
import org.jetbrains.kotlin.ir.symbols.IrValueSymbol
import org.jetbrains.kotlin.ir.util.ReferenceSymbolTable
import org.jetbrains.kotlin.ir.util.deepCopyWithSymbols
import org.jetbrains.kotlin.ir.util.isTopLevelDeclaration
import org.jetbrains.kotlin.ir.util.referenceFunction
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.resolve.checkers.ExpectedActualDeclarationChecker
import org.jetbrains.kotlin.resolve.descriptorUtil.module
import org.jetbrains.kotlin.resolve.multiplatform.ExpectedActualResolver

/**
 * This pass removes all declarations with `isExpect == true`.
 */
class ExpectDeclarationsRemoveLowering(val context: BackendContext, val keepOptionalAnnotations: Boolean = false) : DeclarationTransformer {

    private val symbolTable: ReferenceSymbolTable = context.ir.symbols.externalSymbolTable

    override fun transformFlat(declaration: IrDeclaration): List<IrDeclaration>? {

        val descriptor = declaration.descriptor
        if (declaration.isTopLevelDeclaration && descriptor is MemberDescriptor && descriptor.isExpect &&
            (!keepOptionalAnnotations || descriptor !is ClassDescriptor || !ExpectedActualDeclarationChecker.shouldGenerateExpectClass(descriptor))
        ) {
            return emptyList()
        }

        if (declaration is IrValueParameter) {
            // Keep actual default value if present. They are generally not allowed but can be suppressed with
            // @Suppress("ACTUAL_FUNCTION_WITH_DEFAULT_ARGUMENTS")
            if (declaration.defaultValue != null) {
                return null
            }

            val function = declaration.parent as? IrFunction ?: return null

            if (function is IrConstructor &&
                ExpectedActualDeclarationChecker.isOptionalAnnotationClass(function.descriptor.constructedClass)
            ) {
                return null
            }

            if (!function.descriptor.isActual) return null

            val index = declaration.index

            if (index < 0) return null

            assert(function.valueParameters[index] == declaration)

            // If the containing declaration is an `expect class` that matches an `actual typealias`,
            // the `actual fun` or `actual constructor` for this may be in a different module.
            // Nothing we can do with those.
            // TODO they may not actually have the defaults though -- may be a frontend bug.
            val expectParameter = function.findExpectForActual()?.valueParameters?.get(index) ?: return null

            val defaultValue = expectParameter.defaultValue ?: return null

            defaultValue.let { originalDefault ->
                declaration.defaultValue = IrExpressionBodyImpl(originalDefault.startOffset, originalDefault.endOffset) {
                    expression = originalDefault.expression.deepCopyWithSymbols(function).remapExpectValueSymbols()
                }
            }
        }

        return null
    }

    private fun IrFunction.findActualForExpected(): IrFunction? =
        descriptor.findActualForExpect()?.let { symbolTable.referenceFunction(it).owner }

    private fun IrFunction.findExpectForActual(): IrFunction? =
        descriptor.findExpectForActual()?.let { symbolTable.referenceFunction(it).owner }

    private fun IrClass.findActualForExpected(): IrClass? =
        descriptor.findActualForExpect()?.let { symbolTable.referenceClass(it).owner }

    private inline fun <reified T : MemberDescriptor> T.findActualForExpect() = with(ExpectedActualResolver) {
        val descriptor = this@findActualForExpect

        if (!descriptor.isExpect) error(this)

        findCompatibleActualForExpected(descriptor.module).singleOrNull()
    } as T?

    private inline fun <reified T : MemberDescriptor> T.findExpectForActual() = with(ExpectedActualResolver) {
        val descriptor = this@findExpectForActual

        if (!descriptor.isActual) error(this) else {
            findCompatibleExpectedForActual(descriptor.module).singleOrNull()
        }
    } as T?

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
                parent.findActualForExpected()!!.thisReceiver!!
            }

            is IrFunction -> when (parameter) {
                parent.dispatchReceiverParameter ->
                    parent.findActualForExpected()!!.dispatchReceiverParameter!!
                parent.extensionReceiverParameter ->
                    parent.findActualForExpected()!!.extensionReceiverParameter!!
                else -> {
                    assert(parent.valueParameters[parameter.index] == parameter)
                    parent.findActualForExpected()!!.valueParameters[parameter.index]
                }
            }

            else -> error(parent)
        }
    }
}
