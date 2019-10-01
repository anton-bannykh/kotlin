/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.declarations.impl.carriers

import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrDeclarationParent
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.declarations.MetadataSource
import org.jetbrains.kotlin.ir.expressions.IrBody
import org.jetbrains.kotlin.ir.symbols.IrPropertySymbol
import org.jetbrains.kotlin.ir.types.IrType

interface FunctionCarrier : FunctionBaseCarrier<FunctionCarrier> {
    var correspondingPropertySymbolField: IrPropertySymbol?

    override fun eq(other: FunctionCarrier): Boolean {
        return parentField === other.parentField &&
                originField === other.originField &&
                returnTypeFieldField === other.returnTypeFieldField &&
                dispatchReceiverParameterField === other.dispatchReceiverParameterField &&
                extensionReceiverParameterField === other.extensionReceiverParameterField &&
                bodyField === other.bodyField &&
                metadataField === other.metadataField &&
                correspondingPropertySymbolField === other.correspondingPropertySymbolField
    }

    override fun clone(): FunctionCarrier {
        return FunctionCarrierImpl(
            parentField,
            originField,
            returnTypeFieldField,
            dispatchReceiverParameterField,
            extensionReceiverParameterField,
            bodyField,
            metadataField,
            correspondingPropertySymbolField
        )
    }
}

class FunctionCarrierImpl(
    override var parentField: IrDeclarationParent?,
    override var originField: IrDeclarationOrigin,
    override var returnTypeFieldField: IrType,
    override var dispatchReceiverParameterField: IrValueParameter?,
    override var extensionReceiverParameterField: IrValueParameter?,
    override var bodyField: IrBody?,
    override var metadataField: MetadataSource?,
    override var correspondingPropertySymbolField: IrPropertySymbol?
) : FunctionCarrier