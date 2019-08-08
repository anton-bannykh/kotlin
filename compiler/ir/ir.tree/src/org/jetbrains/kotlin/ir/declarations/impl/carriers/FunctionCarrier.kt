/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.declarations.impl.carriers

import org.jetbrains.kotlin.ir.declarations.IrDeclarationParent
import org.jetbrains.kotlin.ir.declarations.IrTypeParameter
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.declarations.MetadataSource
import org.jetbrains.kotlin.ir.expressions.IrBody
import org.jetbrains.kotlin.ir.expressions.IrExpressionBody
import org.jetbrains.kotlin.ir.symbols.IrPropertySymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.types.IrType

interface FunctionCarrier : FunctionBaseCarrier<FunctionCarrier> {
    var correspondingPropertySymbolField: IrPropertySymbol?
    val overriddenSymbolsField: MutableList<IrSimpleFunctionSymbol>

    override fun eq(other: FunctionCarrier): Boolean {
        return parentField === other.parentField &&
                returnTypeFieldField === other.returnTypeFieldField &&
                dispatchReceiverParameterField === other.dispatchReceiverParameterField &&
                extensionReceiverParameterField === other.extensionReceiverParameterField &&
                bodyField === other.bodyField &&
                metadataField === other.metadataField &&
                correspondingPropertySymbolField === other.correspondingPropertySymbolField &&
                annotationsField.eq(other.annotationsField) &&
                typeParametersField.eq(other.typeParametersField) &&
                valueParametersField.eq(other.valueParametersField) &&
                overriddenSymbolsField.eq(other.overriddenSymbolsField)
    }

    override fun clone(): FunctionCarrier {
        return FunctionCarrierImpl(
            parentField,
            annotationsField.clone(),
            returnTypeFieldField,
            dispatchReceiverParameterField,
            extensionReceiverParameterField,
            bodyField,
            metadataField,
            typeParametersField.clone(),
            valueParametersField.clone(),
            correspondingPropertySymbolField,
            overriddenSymbolsField.clone()
        )
    }
}

class FunctionCarrierImpl(
    override var parentField: IrDeclarationParent?,
    override val annotationsField: MutableList<IrExpressionBody>,
    override var returnTypeFieldField: IrType,
    override var dispatchReceiverParameterField: IrValueParameter?,
    override var extensionReceiverParameterField: IrValueParameter?,
    override var bodyField: IrBody?,
    override var metadataField: MetadataSource?,
    override val typeParametersField: MutableList<IrTypeParameter>,
    override val valueParametersField: MutableList<IrValueParameter>,
    override var correspondingPropertySymbolField: IrPropertySymbol?,
    override val overriddenSymbolsField: MutableList<IrSimpleFunctionSymbol>
) : FunctionCarrier