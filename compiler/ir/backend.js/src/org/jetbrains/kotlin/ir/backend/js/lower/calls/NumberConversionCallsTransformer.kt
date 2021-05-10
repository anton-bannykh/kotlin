/*
 * Copyright 2010-2018 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.backend.js.lower.calls

import org.jetbrains.kotlin.ir.backend.js.JsIrBackendContext
import org.jetbrains.kotlin.ir.backend.js.ir.JsIrBuilder
import org.jetbrains.kotlin.ir.backend.js.utils.ConversionNames
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionAccessExpression

class NumberConversionCallsTransformer(context: JsIrBackendContext) : CallsTransformer {
    private val intrinsics = context.intrinsics
    private val jsIrBuiltIns = context.jsIrBuiltIns
    private val irBuiltIns = context.irBuiltIns

    private val memberToTransformer = MemberToTransformer().apply {
        // Conversion rules are ported from NumberAndCharConversionFIF
        // TODO: Add Char and Number conversions

        irBuiltIns.byteType.let {
            add(it, ConversionNames.TO_BYTE, ::useDispatchReceiver)
            add(it, ConversionNames.TO_DOUBLE, ::useDispatchReceiver)
            add(it, ConversionNames.TO_FLOAT, ::useDispatchReceiver)
            add(it, ConversionNames.TO_INT, ::useDispatchReceiver)
            add(it, ConversionNames.TO_SHORT, ::useDispatchReceiver)
            add(it, ConversionNames.TO_LONG, jsIrBuiltIns.jsToLong)
        }

        for (type in listOf(irBuiltIns.floatType, irBuiltIns.doubleType)) {
            add(type, ConversionNames.TO_BYTE, jsIrBuiltIns.jsNumberToByte)
            add(type, ConversionNames.TO_DOUBLE, ::useDispatchReceiver)
            add(type, ConversionNames.TO_FLOAT, ::useDispatchReceiver)
            add(type, ConversionNames.TO_INT, jsIrBuiltIns.jsNumberToInt)
            add(type, ConversionNames.TO_SHORT, jsIrBuiltIns.jsNumberToShort)
            add(type, ConversionNames.TO_LONG, jsIrBuiltIns.jsNumberToLong)
        }

        irBuiltIns.intType.let {
            add(it, ConversionNames.TO_BYTE, jsIrBuiltIns.jsToByte)
            add(it, ConversionNames.TO_DOUBLE, ::useDispatchReceiver)
            add(it, ConversionNames.TO_FLOAT, ::useDispatchReceiver)
            add(it, ConversionNames.TO_INT, ::useDispatchReceiver)
            add(it, ConversionNames.TO_SHORT, jsIrBuiltIns.jsToShort)
            add(it, ConversionNames.TO_LONG, jsIrBuiltIns.jsToLong)
        }

        irBuiltIns.shortType.let {
            add(it, ConversionNames.TO_BYTE, jsIrBuiltIns.jsToByte)
            add(it, ConversionNames.TO_DOUBLE, ::useDispatchReceiver)
            add(it, ConversionNames.TO_FLOAT, ::useDispatchReceiver)
            add(it, ConversionNames.TO_INT, ::useDispatchReceiver)
            add(it, ConversionNames.TO_SHORT, ::useDispatchReceiver)
            add(it, ConversionNames.TO_LONG, jsIrBuiltIns.jsToLong)
        }


        irBuiltIns.numberType.let {
            add(it, ConversionNames.TO_BYTE, jsIrBuiltIns.jsNumberToByte)
            add(it, ConversionNames.TO_DOUBLE, jsIrBuiltIns.jsNumberToDouble)
            add(it, ConversionNames.TO_FLOAT, jsIrBuiltIns.jsNumberToDouble)
            add(it, ConversionNames.TO_INT, jsIrBuiltIns.jsNumberToInt)
            add(it, ConversionNames.TO_SHORT, jsIrBuiltIns.jsNumberToShort)
            add(it, ConversionNames.TO_LONG, jsIrBuiltIns.jsNumberToLong)
        }

        for (type in arrayOf(irBuiltIns.byteType, irBuiltIns.shortType, irBuiltIns.intType)) {
            add(type, ConversionNames.TO_CHAR, jsIrBuiltIns.jsNumberToChar)
        }

        for (type in arrayOf(irBuiltIns.floatType, irBuiltIns.doubleType, irBuiltIns.numberType)) {
            add(type, ConversionNames.TO_CHAR, jsIrBuiltIns.jsNumberToChar)
        }
    }

    override fun transformFunctionAccess(call: IrFunctionAccessExpression, doNotIntrinsify: Boolean): IrExpression {
        val function = call.symbol.owner
        function.dispatchReceiverParameter?.also {
            val key = SimpleMemberKey(it.type, function.name)
            memberToTransformer[key]?.also {
                return it(call)
            }
        }
        return call
    }

    private fun useDispatchReceiver(call: IrFunctionAccessExpression): IrExpression {
        return JsIrBuilder.buildReinterpretCast(call.dispatchReceiver!!, call.type)
    }
}
