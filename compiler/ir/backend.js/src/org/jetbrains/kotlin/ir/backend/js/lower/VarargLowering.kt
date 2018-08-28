/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.backend.js.lower

import org.jetbrains.kotlin.backend.common.FileLoweringPass
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.backend.js.JsIrBackendContext
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrSpreadElement
import org.jetbrains.kotlin.ir.expressions.IrVararg
import org.jetbrains.kotlin.ir.expressions.IrVarargElement
import org.jetbrains.kotlin.ir.expressions.impl.IrCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrSpreadElementImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrVarargImpl
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import kotlin.math.exp

class VarargLowering(val context: JsIrBackendContext) : FileLoweringPass {
    override fun lower(irFile: IrFile) {
        irFile.transformChildrenVoid(VarargTransformer(context))
    }
}

private class VarargTransformer(
    val context: JsIrBackendContext
) : IrElementTransformerVoid() {

    override fun visitVararg(expression: IrVararg): IrExpression {
        expression.transformChildrenVoid(this)

        val currentList = mutableListOf<IrExpression>()
        val segments = mutableListOf<IrExpression>()

        for (e in expression.elements) {
            if (e is IrSpreadElement) {
                segments.add(IrCallImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, expression.type, context.intrinsics.arrayLiteral).apply {
                    putTypeArgument(0, expression.varargElementType)
                    currentList.forEachIndexed { i, e ->
                        putValueArgument(i, e)
                    }
                })
                currentList.clear()
                segments.add(e.expression)
            } else {
                currentList.add(e as IrExpression) // TODO why isn't it IrExpression?
            }
        }

        return IrVarargImpl(expression.startOffset, expression.endOffset, expression.type, expression.varargElementType).apply {
            addElement(
                IrSpreadElementImpl(
                    expression.startOffset,
                    expression.endOffset,
                    IrCallImpl(
                        UNDEFINED_OFFSET,
                        UNDEFINED_OFFSET,
                        expression.type,
                        context.intrinsics.arrayConcat
                    ).apply {
                        putValueArgument(
                            0,
                            IrCallImpl(
                                UNDEFINED_OFFSET,
                                UNDEFINED_OFFSET,
                                expression.type,
                                context.intrinsics.arrayLiteral
                            ).apply {
                                putTypeArgument(0, expression.type)
                                segments.forEachIndexed { i, e ->
                                    putValueArgument(i, e)
                                }
                            })
                    })
            )
        }

    }
}
