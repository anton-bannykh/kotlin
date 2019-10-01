/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.declarations.impl.carriers

import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrDeclarationParent
import org.jetbrains.kotlin.ir.expressions.IrExpression

interface VariableCarrier : DeclarationCarrier<VariableCarrier> {
    var initializerField: IrExpression?

    override fun eq(other: VariableCarrier): Boolean {
        return parentField === other.parentField &&
                originField === other.originField &&
                initializerField === other.initializerField
    }

    override fun clone(): VariableCarrier {
        return VariableCarrierImpl(
            parentField,
            originField,
            initializerField
        )
    }
}

class VariableCarrierImpl(
    override var parentField: IrDeclarationParent?,
    override var originField: IrDeclarationOrigin,
    override var initializerField: IrExpression?
) : VariableCarrier