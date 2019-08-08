/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.declarations.impl.carriers

import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationParent
import org.jetbrains.kotlin.ir.expressions.IrExpressionBody

interface EnumEntryCarrier : CarrierBase<EnumEntryCarrier> {
    var correspondingClassField: IrClass?
    var initializerExpressionField: IrExpressionBody?

    override fun eq(other: EnumEntryCarrier): Boolean {
        return parentField === other.parentField &&
                correspondingClassField === other.correspondingClassField &&
                initializerExpressionField === other.initializerExpressionField &&
                annotationsField.eq(other.annotationsField)
    }

    override fun clone(): EnumEntryCarrier {
        return EnumEntryCarrierImpl(
            parentField,
            annotationsField.clone(),
            correspondingClassField,
            initializerExpressionField
        )
    }
}

class EnumEntryCarrierImpl(
    override var parentField: IrDeclarationParent?,
    override val annotationsField: MutableList<IrExpressionBody>,
    override var correspondingClassField: IrClass?,
    override var initializerExpressionField: IrExpressionBody?
) : EnumEntryCarrier