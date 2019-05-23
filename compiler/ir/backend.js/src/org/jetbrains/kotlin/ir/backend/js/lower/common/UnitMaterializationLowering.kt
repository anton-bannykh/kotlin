/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.backend.js.lower.common

import org.jetbrains.kotlin.backend.common.BackendContext
import org.jetbrains.kotlin.backend.common.DeclarationTransformer
import org.jetbrains.kotlin.backend.common.FileLoweringPass
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.impl.IrCompositeImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrGetObjectValueImpl
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.types.classifierOrFail
import org.jetbrains.kotlin.ir.types.isUnit
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid

// Does not create new declarations
// Loads Unit value
class UnitMaterializationLowering(context: BackendContext) : DeclarationTransformer {

    private val unitType = context.irBuiltIns.unitType
    private val unitValue
        get() = IrGetObjectValueImpl(
            UNDEFINED_OFFSET,
            UNDEFINED_OFFSET,
            unitType,
            unitType.classifierOrFail as IrClassSymbol
        )

    override fun transformFlat(declaration: IrDeclaration): List<IrDeclaration>? {
        declaration.transformChildrenVoid(object : IrElementTransformerVoid() {
            override fun visitCall(expression: IrCall): IrExpression {
                expression.transformChildrenVoid(this)
                return if (expression.type.isUnit()) expression.let {
                    IrCompositeImpl(it.startOffset, it.endOffset, it.type, it.origin, listOf(it, unitValue))
                }
                else expression
            }
        })

        return null
    }
}
