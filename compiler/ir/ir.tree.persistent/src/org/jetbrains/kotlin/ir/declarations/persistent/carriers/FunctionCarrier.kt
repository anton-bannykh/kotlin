/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.declarations.persistent.carriers

import org.jetbrains.kotlin.descriptors.DescriptorVisibility
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.expressions.IrBody
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.symbols.IrPropertySymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.IrSymbol
import org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol
import org.jetbrains.kotlin.ir.symbols.IrValueParameterSymbol
import org.jetbrains.kotlin.ir.types.IrType

// Auto-generated by compiler/ir/ir.tree/src/org/jetbrains/kotlin/ir/persistentIrGenerator/Main.kt. DO NOT EDIT!

internal interface FunctionCarrier : DeclarationCarrier{
    var returnTypeFieldField: IrType
    var dispatchReceiverParameterField: IrValueParameterSymbol?
    var extensionReceiverParameterField: IrValueParameterSymbol?
    var bodyField: IrBody?
    var visibilityField: DescriptorVisibility
    var typeParametersField: List<IrTypeParameterSymbol>
    var valueParametersField: List<IrValueParameterSymbol>
    var correspondingPropertySymbolField: IrPropertySymbol?
    var overriddenSymbolsField: List<IrSimpleFunctionSymbol>

    override fun clone(): FunctionCarrier {
        return FunctionCarrierImpl(
            lastModified,
            parentSymbolField,
            originField,
            annotationsField,
            returnTypeFieldField,
            dispatchReceiverParameterField,
            extensionReceiverParameterField,
            bodyField,
            visibilityField,
            typeParametersField,
            valueParametersField,
            correspondingPropertySymbolField,
            overriddenSymbolsField
        )
    }
}

internal class FunctionCarrierImpl(
    override val lastModified: Int,
    override var parentSymbolField: IrSymbol?,
    override var originField: IrDeclarationOrigin,
    override var annotationsField: List<IrConstructorCall>,
    override var returnTypeFieldField: IrType,
    override var dispatchReceiverParameterField: IrValueParameterSymbol?,
    override var extensionReceiverParameterField: IrValueParameterSymbol?,
    override var bodyField: IrBody?,
    override var visibilityField: DescriptorVisibility,
    override var typeParametersField: List<IrTypeParameterSymbol>,
    override var valueParametersField: List<IrValueParameterSymbol>,
    override var correspondingPropertySymbolField: IrPropertySymbol?,
    override var overriddenSymbolsField: List<IrSimpleFunctionSymbol>
) : FunctionCarrier
