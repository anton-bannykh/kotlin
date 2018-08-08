/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.backend.js.lower

import org.jetbrains.kotlin.backend.common.FileLoweringPass
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.backend.js.JsIrBackendContext
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.impl.IrCallImpl
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid

class ArrayLowering(val context: JsIrBackendContext, val hack: Boolean) : FileLoweringPass {
    override fun lower(irFile: IrFile) {
        irFile.transformChildrenVoid(ArrayConstructorTransformer(context, hack))
    }
}

private class ArrayConstructorTransformer(
    val context: JsIrBackendContext,
    val hack: Boolean
) : IrElementTransformerVoid() {

    override fun visitCall(expression: IrCall): IrExpression {
        when {
            expression.symbol == context.intrinsics.arrayConstructor && !hack  -> {
                return irCall(expression, context.intrinsics.jsArray)
            }
            expression.symbol == context.intrinsics.arraySize && hack -> {
                return irCall(expression, context.intrinsics.jsArrayLength, dispatchReceiverAsFirstArgument = true)
            }
        }

//        (expression.symbol.owner as? IrConstructor)?.let { declaration ->
//            (declaration.parent as? IrClass)?.let { irClass ->
//                when (irClass.symbol) {
//
//                }
//                null
//            }
//            if (declaration.parentAsClass.symbol == context.intrinsics.array) {
//                println("Hi!")
//            }
//        }

        return super.visitCall(expression)
    }



}