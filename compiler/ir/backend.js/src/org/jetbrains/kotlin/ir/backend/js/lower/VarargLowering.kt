/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.backend.js.lower

import org.jetbrains.kotlin.backend.common.FileLoweringPass
import org.jetbrains.kotlin.builtins.PrimitiveType
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.backend.js.JsIrBackendContext
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrSpreadElement
import org.jetbrains.kotlin.ir.expressions.IrVararg
import org.jetbrains.kotlin.ir.expressions.impl.IrCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrVarargImpl
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.impl.IrSimpleTypeImpl
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid

class VarargLowering(val context: JsIrBackendContext) : FileLoweringPass {
    override fun lower(irFile: IrFile) {
        irFile.transformChildrenVoid(VarargTransformer(context))
    }
}

private class VarargTransformer(
    val context: JsIrBackendContext
) : IrElementTransformerVoid() {

    private val irTypeToPrimitive = mapOf(
        context.irBuiltIns.booleanType to PrimitiveType.BOOLEAN,
        context.irBuiltIns.byteType to PrimitiveType.BYTE,
        context.irBuiltIns.shortType to PrimitiveType.SHORT,
        context.irBuiltIns.charType to PrimitiveType.CHAR,
        context.irBuiltIns.intType to PrimitiveType.INT,
        context.irBuiltIns.floatType to PrimitiveType.FLOAT,
        context.irBuiltIns.doubleType to PrimitiveType.DOUBLE,
        context.irBuiltIns.longType to PrimitiveType.LONG
    )

    private fun List<IrExpression>.toArrayLiteral(type: IrType, varargElementType: IrType): IrExpression {
        val intrinsic = irTypeToPrimitive[varargElementType]?.let { primitiveType ->
            context.intrinsics.primitiveToLiteralConstructor[primitiveType]
        } ?: context.intrinsics.arrayLiteral

        val startOffset = first().startOffset
        val endOffset = last().endOffset

        val vararg = IrVarargImpl(startOffset, endOffset, type, varargElementType, this)

        return IrCallImpl(startOffset, endOffset, type, intrinsic).apply {
            if (intrinsic.owner.typeParameters.isNotEmpty()) putTypeArgument(0, varargElementType)
            putValueArgument(0, vararg)
        }
    }

    override fun visitVararg(expression: IrVararg): IrExpression {
        expression.transformChildrenVoid(this)

        val currentList = mutableListOf<IrExpression>()
        val segments = mutableListOf<IrExpression>()

        for (e in expression.elements) {
            if (e is IrSpreadElement) {
                if (!currentList.isEmpty()) {
                    segments.add(currentList.toArrayLiteral(expression.type, expression.varargElementType))
                    currentList.clear()
                }
                segments.add(e.expression)
            } else {
                // IrVarargElement is either IrSpreadElement or IrExpression
                currentList.add(e as IrExpression)
            }
        }
        if (!currentList.isEmpty()) {
            segments.add(currentList.toArrayLiteral(expression.type, expression.varargElementType))
            currentList.clear()
        }

        // empty vararg => empty array literal
        if (segments.isEmpty()) {
            return listOf().toArrayLiteral(expression.type, expression.varargElementType)
        }

        // vararg with a single segment => no need to concatenate
        if (segments.size == 1) {
            return segments.first()
        }

        val arrayLiteral = segments.toArrayLiteral(IrSimpleTypeImpl(context.intrinsics.array, false, listOf(), listOf()), context.irBuiltIns.anyType)

        return IrCallImpl(
            UNDEFINED_OFFSET,
            UNDEFINED_OFFSET,
            expression.type,
            context.intrinsics.arrayConcat
        ).apply {
            putValueArgument(0, arrayLiteral)
        }
    }
}
