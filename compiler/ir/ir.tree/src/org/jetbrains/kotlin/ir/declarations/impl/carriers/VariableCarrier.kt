/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.declarations.impl.carriers

import org.jetbrains.kotlin.ir.declarations.IrDeclarationParent
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrExpressionBody

interface VariableCarrier : CarrierBase<VariableCarrier> {
    var initializerField: IrExpression?

    override fun eq(other: VariableCarrier): Boolean {
        return parentField === other.parentField &&
                initializerField === other.initializerField &&
                annotationsField.eq(other.annotationsField)
    }

    override fun clone(): VariableCarrier {
        return VariableCarrierImpl(
            parentField,
            annotationsField.clone(),
            initializerField
        )
    }
}

class VariableCarrierImpl(
    override var parentField: IrDeclarationParent?,
    override val annotationsField: MutableList<IrExpressionBody>,
    override var initializerField: IrExpression?
) : VariableCarrier