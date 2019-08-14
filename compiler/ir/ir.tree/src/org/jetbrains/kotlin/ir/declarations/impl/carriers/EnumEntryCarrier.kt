/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.declarations.impl.carriers

import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationParent
import org.jetbrains.kotlin.ir.expressions.IrExpressionBody

interface EnumEntryCarrier : CarrierWithBody<IrExpressionBody, EnumEntryCarrier> {
    var correspondingClassField: IrClass?

    override fun eq(other: EnumEntryCarrier): Boolean {
        return parentField === other.parentField &&
                correspondingClassField === other.correspondingClassField &&
                bodyField === other.bodyField
    }

    override fun clone(): EnumEntryCarrier {
        return EnumEntryCarrierImpl(
            parentField,
            correspondingClassField,
            bodyField
        )
    }
}

class EnumEntryCarrierImpl(
    override var parentField: IrDeclarationParent?,
    override var correspondingClassField: IrClass?,
    override var bodyField: IrExpressionBody?
) : EnumEntryCarrier