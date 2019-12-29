/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.declarations.impl.carriers

import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrDeclarationParent

interface TypeParameterCarrier : DeclarationCarrier<TypeParameterCarrier> {

    override fun eq(other: TypeParameterCarrier): Boolean {
        return parentField === other.parentField &&
                originField === other.originField
    }

    override fun clone(): TypeParameterCarrier {
        return TypeParameterCarrierImpl(lastModified, parentField, originField)
    }
}

class TypeParameterCarrierImpl(
    override val lastModified: Int,
    override var parentField: IrDeclarationParent?,
    override var originField: IrDeclarationOrigin
) : TypeParameterCarrier