/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.declarations.impl.carriers

import org.jetbrains.kotlin.ir.declarations.IrDeclarationParent
import org.jetbrains.kotlin.ir.expressions.IrExpressionBody
import org.jetbrains.kotlin.ir.types.IrType

interface TypeParameterCarrier : CarrierBase<TypeParameterCarrier> {

    val superTypesField: MutableList<IrType>

    override fun eq(other: TypeParameterCarrier): Boolean {
        return parentField === other.parentField &&
                annotationsField.eq(other.annotationsField) &&
                superTypesField.eq(other.superTypesField)
    }

    override fun clone(): TypeParameterCarrier {
        return TypeParameterCarrierImpl(
            parentField,
            annotationsField.clone(),
            superTypesField.clone()
        )
    }
}

class TypeParameterCarrierImpl(
    override var parentField: IrDeclarationParent?,
    override val annotationsField: MutableList<IrExpressionBody>,
    override val superTypesField: MutableList<IrType>
) : TypeParameterCarrier