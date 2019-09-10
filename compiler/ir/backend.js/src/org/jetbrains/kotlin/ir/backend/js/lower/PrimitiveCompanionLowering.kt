/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.backend.js.lower

import org.jetbrains.kotlin.backend.common.BodyLoweringPass
import org.jetbrains.kotlin.backend.common.DeclarationTransformer
import org.jetbrains.kotlin.backend.common.FileLoweringPass
import org.jetbrains.kotlin.ir.backend.js.JsIrBackendContext
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.expressions.impl.IrCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrGetObjectValueImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrGetterCallImpl
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.types.isPrimitiveType
import org.jetbrains.kotlin.ir.types.isString
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.name
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid

class PrimitiveCompanionLowering(val context: JsIrBackendContext) : BodyLoweringPass {
    override fun lower(irBody: IrBody, container: IrDeclaration) {
        irBody.transformChildrenVoid(object : IrElementTransformerVoid() {
            override fun visitGetObjectValue(expression: IrGetObjectValue): IrExpression {
                val symbol = expression.symbol
                if (!symbol.isBound) return expression

                val actualCompanion = symbol.owner.getActualCompanion() ?: return expression
                return expression.run { IrGetObjectValueImpl(startOffset, endOffset, actualCompanion.owner.defaultType, actualCompanion) }
            }

            private fun IrClass.getActualCompanion(): IrClassSymbol? {
                if (!isCompanion) return null
                val parent = parent as? IrClass ?: return null
                if (!parent.defaultType.isPrimitiveType() && !parent.defaultType.isString()) return null
                val actualCompanion = context.primitiveCompanionObjects[parent.name] ?: return null
                return actualCompanion
            }

            override fun visitCall(expression: IrCall): IrExpression {
                expression.transformChildrenVoid(this)

                val member = expression.symbol.owner

                val actualCompanion = (member.parent as? IrClass)?.getActualCompanion() ?: return expression

                val actualMember = stageController.withInitialIr {
                    actualCompanion.owner.declarations
                        .filterIsInstance<IrProperty>()
                        .single { it.getter?.name == member.name }
                        .getter!!
                }

                return IrCallImpl(expression.startOffset, expression.endOffset, expression.type, actualMember.symbol).apply {
                    dispatchReceiver = expression.dispatchReceiver
                }
            }
        })
    }
}