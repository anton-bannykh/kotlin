/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.declarations.impl.carriers

import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrDeclarationParent
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrExpressionBody

interface EnumEntryCarrier : DeclarationCarrier<EnumEntryCarrier> {
    var correspondingClassField: IrClass?
    var initializerExpressionField: IrExpression?

    override fun eq(other: EnumEntryCarrier): Boolean {
        return parentField === other.parentField &&
                originField === other.originField &&
                correspondingClassField === other.correspondingClassField &&
                initializerExpressionField === other.initializerExpressionField
    }

    override fun clone(): EnumEntryCarrier {
        return EnumEntryCarrierImpl(
            parentField,
            originField,
            correspondingClassField,
            initializerExpressionField
        )
    }
}

class EnumEntryCarrierImpl(
    override var parentField: IrDeclarationParent?,
    override var originField: IrDeclarationOrigin,
    override var correspondingClassField: IrClass?,
    override var initializerExpressionField: IrExpression?
) : EnumEntryCarrier