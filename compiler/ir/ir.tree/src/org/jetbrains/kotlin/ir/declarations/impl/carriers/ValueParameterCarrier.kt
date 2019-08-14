/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.declarations.impl.carriers

import org.jetbrains.kotlin.ir.declarations.IrDeclarationParent
import org.jetbrains.kotlin.ir.expressions.IrExpressionBody

interface ValueParameterCarrier : CarrierWithBody<IrExpressionBody, ValueParameterCarrier> {

    override fun eq(other: ValueParameterCarrier): Boolean {
        return parentField === other.parentField && bodyField === other.bodyField
    }

    override fun clone(): ValueParameterCarrier {
        return ValueParameterCarrierImpl(parentField, bodyField)
    }
}

class ValueParameterCarrierImpl(
    override var parentField: IrDeclarationParent?,
    override var bodyField: IrExpressionBody?
) : ValueParameterCarrier