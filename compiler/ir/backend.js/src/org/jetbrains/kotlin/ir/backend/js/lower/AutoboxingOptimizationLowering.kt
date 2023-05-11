/*
 * Copyright 2010-2023 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.backend.js.lower

import org.jetbrains.kotlin.backend.common.BodyLoweringPass
import org.jetbrains.kotlin.ir.backend.js.JsIrBackendContext
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrBody
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.impl.IrGetValueImpl
import org.jetbrains.kotlin.ir.symbols.IrValueSymbol
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid

class AutoboxingOptimizationLowering(private val context: JsIrBackendContext) : BodyLoweringPass {
    override fun lower(irBody: IrBody, container: IrDeclaration) {
        if (container !is IrFunction) return
        irBody.transformChildrenVoid(AutoboxingOptimizationTransformer(context))
    }

    private class AutoboxingOptimizationTransformer(private val context: JsIrBackendContext) : IrElementTransformerVoid() {
        override fun visitCall(expression: IrCall): IrExpression {
            val isBox = expression.symbol == context.intrinsics.jsBoxIntrinsic
            val isUnbox = expression.symbol == context.intrinsics.jsUnboxIntrinsic

            if (!isBox && !isUnbox) {
                return super.visitCall(expression)
            }

            val processedValue = expression.getValueArgument(0) as? IrGetValue
            val firstBoxedOrUnboxedValue = processedValue?.symbol?.findFirstBoxedOrUnboxedValue(isBox)?.let {
                IrGetValueImpl(expression.startOffset, expression.endOffset, it, expression.origin)
            }

            return firstBoxedOrUnboxedValue ?: super.visitCall(expression)
        }

        private fun IrValueSymbol.findFirstBoxedOrUnboxedValue(isAlreadyBoxed: Boolean): IrValueSymbol? {
            return if (isAlreadyBoxed) findFirstBoxedValue() else findFirstUnboxedValue()
        }

        private fun IrValueSymbol.findFirstBoxedValue(): IrValueSymbol? {
            val unboxedValueSymbol = (owner as? IrVariable)?.extractReferenceOnUnboxedValue() ?: return null
            val boxedValueSymbol = (unboxedValueSymbol.owner as? IrVariable)?.extractReferenceOnBoxedValue()
            return boxedValueSymbol?.findFirstBoxedValue() ?: unboxedValueSymbol
        }

        private fun IrValueSymbol.findFirstUnboxedValue(): IrValueSymbol? {
            val boxedValueSymbol = (owner as? IrVariable)?.extractReferenceOnBoxedValue() ?: return null
            val unboxedValueSymbol = (boxedValueSymbol.owner as? IrVariable)?.extractReferenceOnUnboxedValue()
            return unboxedValueSymbol?.findFirstUnboxedValue() ?: boxedValueSymbol
        }

        private fun IrVariable.extractReferenceOnUnboxedValue(): IrValueSymbol? {
            val initializer = (initializer as? IrCall)?.takeIf { it.symbol == context.intrinsics.jsUnboxIntrinsic }
            return (initializer?.getValueArgument(0) as? IrGetValue)?.symbol
        }

        private fun IrVariable.extractReferenceOnBoxedValue(): IrValueSymbol? {
            val initializer = (initializer as? IrCall)?.takeIf { it.symbol == context.intrinsics.jsBoxIntrinsic }
            return (initializer?.getValueArgument(0) as? IrGetValue)?.symbol
        }
    }
}