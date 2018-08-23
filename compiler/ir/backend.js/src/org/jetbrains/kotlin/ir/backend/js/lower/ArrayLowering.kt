/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.backend.js.lower

import org.jetbrains.kotlin.backend.common.FileLoweringPass
import org.jetbrains.kotlin.builtins.PrimitiveType
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.backend.js.JsIrBackendContext
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.impl.IrCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrConstImpl
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid

class ArrayLowering(val context: JsIrBackendContext, val hack: Boolean) : FileLoweringPass {
    override fun lower(irFile: IrFile) {
        irFile.transformChildrenVoid(ArrayConstructorTransformer(context, hack))
    }
}

private val IrClassSymbol.inlineConstructor
    get() = owner.declarations.filterIsInstance<IrConstructor>().first { it.valueParameters.size == 2 }.symbol

private val IrClassSymbol.sizeConstructor
    get() = owner.declarations.filterIsInstance<IrConstructor>().first { it.valueParameters.size == 1 }.symbol

private val IrClassSymbol.sizeProperty
    get() = owner.declarations.filterIsInstance<IrProperty>().first { it.name.asString() == "size" }.getter!!.symbol

private val IrClassSymbol.getFunction
    get() = owner.declarations.filterIsInstance<IrFunction>().first { it.name.asString() == "get" }.symbol

private val IrClassSymbol.setFunction
    get() = owner.declarations.filterIsInstance<IrFunction>().first { it.name.asString() == "set" }.symbol

private val IrClassSymbol.iterator
    get() = owner.declarations.filterIsInstance<IrFunction>().first { it.name.asString() == "iterator" }.symbol

private class ArrayConstructorTransformer(
    val context: JsIrBackendContext,
    val hack: Boolean
) : IrElementTransformerVoid() {

    override fun visitCall(expression: IrCall): IrExpression {
        expression.transformChildrenVoid(this)

        if (!hack) {
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
        } else {
            when {
                expression.symbol == context.intrinsics.array.sizeProperty -> {
                    return irCall(expression, context.intrinsics.jsArrayLength, dispatchReceiverAsFirstArgument = true)
                }
                expression.symbol == context.intrinsics.array.getFunction -> {
                    return irCall(expression, context.intrinsics.jsArrayGet, dispatchReceiverAsFirstArgument = true)
                }
                expression.symbol == context.intrinsics.array.setFunction -> {
                    return irCall(expression, context.intrinsics.jsArraySet, dispatchReceiverAsFirstArgument = true)
                }
                expression.symbol == context.intrinsics.array.iterator -> {
                    return irCall(expression, context.intrinsics.jsArrayIteratorFunction, dispatchReceiverAsFirstArgument = true)
                }
                expression.symbol in context.intrinsics.primitiveArrays.keys.map { it.sizeProperty } -> {
                    return irCall(expression, context.intrinsics.jsArrayLength, dispatchReceiverAsFirstArgument = true)
                }
                expression.symbol in context.intrinsics.primitiveArrays.keys.map { it.getFunction } -> {
                    return irCall(expression, context.intrinsics.jsArrayGet, dispatchReceiverAsFirstArgument = true)
                }
                expression.symbol in context.intrinsics.primitiveArrays.keys.map { it.setFunction }-> {
                    return irCall(expression, context.intrinsics.jsArraySet, dispatchReceiverAsFirstArgument = true)
                }
            }
            context.intrinsics.primitiveArrays.entries.firstOrNull { (k, _) -> k.sizeConstructor == expression.symbol }?.let { (_, t) ->
                val default = when (t) {
                    PrimitiveType.BOOLEAN -> IrConstImpl.boolean(UNDEFINED_OFFSET, UNDEFINED_OFFSET, context.irBuiltIns.booleanType, false)
                    PrimitiveType.BYTE -> IrConstImpl.byte(UNDEFINED_OFFSET, UNDEFINED_OFFSET, context.irBuiltIns.byteType, 0)
                    PrimitiveType.SHORT -> IrConstImpl.short(UNDEFINED_OFFSET, UNDEFINED_OFFSET, context.irBuiltIns.shortType, 0)
                    PrimitiveType.CHAR -> IrConstImpl.char(UNDEFINED_OFFSET, UNDEFINED_OFFSET, context.irBuiltIns.byteType, '\u0000')
                    PrimitiveType.INT -> IrConstImpl.int(UNDEFINED_OFFSET, UNDEFINED_OFFSET, context.irBuiltIns.byteType, 0)
                    PrimitiveType.FLOAT -> IrConstImpl.float(UNDEFINED_OFFSET, UNDEFINED_OFFSET, context.irBuiltIns.byteType, 0f)
                    PrimitiveType.DOUBLE -> IrConstImpl.double(UNDEFINED_OFFSET, UNDEFINED_OFFSET, context.irBuiltIns.byteType, 0.0)
                    PrimitiveType.LONG -> IrConstImpl.long(UNDEFINED_OFFSET, UNDEFINED_OFFSET, context.irBuiltIns.byteType, 0L)
                }
                return IrCallImpl(expression.startOffset, expression.endOffset, expression.type, context.intrinsics.jsNewArray).apply {
                    putValueArgument(0, expression.getValueArgument(0))
                    putValueArgument(1, default)
                }
            }

            context.intrinsics.primitiveArrays.entries.firstOrNull { (k, _) -> k.iterator == expression.symbol }?.let { (_, t) ->
                return irCall(expression, context.intrinsics.jsPrimitiveArrayIteratorFunctions[t]!!, dispatchReceiverAsFirstArgument = true)
            }
        }

        return expression
    }


}