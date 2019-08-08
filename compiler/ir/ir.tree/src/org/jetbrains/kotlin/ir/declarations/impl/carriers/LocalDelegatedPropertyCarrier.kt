/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.declarations.impl.carriers

import org.jetbrains.kotlin.ir.declarations.IrDeclarationParent
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrExpressionBody

interface LocalDelegatedPropertyCarrier : CarrierBase<LocalDelegatedPropertyCarrier> {
    var delegateField: IrVariable?
    var getterField: IrFunction?
    var setterField: IrFunction?

    override fun eq(other: LocalDelegatedPropertyCarrier): Boolean {
        return parentField === other.parentField &&
                delegateField === other.delegateField &&
                getterField === other.getterField &&
                setterField === other.setterField &&
                annotationsField.eq(other.annotationsField)
    }

    override fun clone(): LocalDelegatedPropertyCarrier {
        return LocalDelegatedPropertyCarrierImpl(
            parentField,
            annotationsField.clone(),
            delegateField,
            getterField,
            setterField
        )
    }
}

class LocalDelegatedPropertyCarrierImpl(
    override var parentField: IrDeclarationParent?,
    override val annotationsField: MutableList<IrExpressionBody>,
    override var delegateField: IrVariable?,
    override var getterField: IrFunction?,
    override var setterField: IrFunction?
) : LocalDelegatedPropertyCarrier