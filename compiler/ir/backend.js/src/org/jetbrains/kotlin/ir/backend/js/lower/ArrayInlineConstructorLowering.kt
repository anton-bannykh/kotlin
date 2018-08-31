/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.backend.js.lower

import org.jetbrains.kotlin.backend.common.FileLoweringPass
import org.jetbrains.kotlin.builtins.PrimitiveType
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.backend.js.JsIrBackendContext
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.impl.IrCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrConstImpl
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid

// Replace array inline constructors with stdlib function invocations
// Should be performed before inliner
class ArrayInlineConstructorLowering(val context: JsIrBackendContext) : FileLoweringPass {
    override fun lower(irFile: IrFile) {
        irFile.transformChildrenVoid(ArrayConstructorTransformer(context))
    }
}

private class ArrayConstructorTransformer(
    val context: JsIrBackendContext
) : IrElementTransformerVoid() {

    override fun visitCall(expression: IrCall): IrExpression {
        expression.transformChildrenVoid(this)

        if (expression.symbol == context.intrinsics.array.inlineConstructor) {
            return irCall(expression, context.intrinsics.jsArray)
        } else {
            context.intrinsics.primitiveArrays.keys.firstOrNull { it.inlineConstructor == expression.symbol }?.let { classSymbol ->
                return IrCallImpl(
                    expression.startOffset,
                    expression.endOffset,
                    expression.type,
                    context.intrinsics.jsFillArray
                ).apply {
                    putValueArgument(0, IrCallImpl(
                        expression.startOffset,
                        expression.endOffset,
                        expression.type,
                        classSymbol.sizeConstructor
                    ).apply {
                        putValueArgument(0, expression.getValueArgument(0))
                    })
                    putValueArgument(1, expression.getValueArgument(1))
                }
            }
        }

        return expression
    }
}

private val IrClassSymbol.inlineConstructor
// TODO it.isInline
    get() = owner.declarations.filterIsInstance<IrConstructor>().first { it.valueParameters.size == 2 }.symbol

private val IrClassSymbol.sizeConstructor
    get() = owner.declarations.filterIsInstance<IrConstructor>().first { it.valueParameters.size == 1 }.symbol
