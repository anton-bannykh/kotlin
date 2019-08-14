/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.declarations.impl.carriers

import org.jetbrains.kotlin.ir.declarations.IrDeclarationParent
import org.jetbrains.kotlin.ir.declarations.MetadataSource
import org.jetbrains.kotlin.ir.expressions.IrExpressionBody
import org.jetbrains.kotlin.ir.symbols.IrPropertySymbol

interface FieldCarrier : CarrierWithBody<IrExpressionBody, FieldCarrier> {
    var correspondingPropertySymbolField: IrPropertySymbol?
    var metadataField: MetadataSource.Property?

    override fun eq(other: FieldCarrier): Boolean {
        return parentField === other.parentField &&
                bodyField === other.bodyField &&
                correspondingPropertySymbolField === other.correspondingPropertySymbolField &&
                metadataField === other.metadataField
    }

    override fun clone(): FieldCarrier {
        return FieldCarrierImpl(
            parentField,
            bodyField,
            correspondingPropertySymbolField,
            metadataField
        )
    }
}

class FieldCarrierImpl(
    override var parentField: IrDeclarationParent?,
    override var bodyField: IrExpressionBody?,
    override var correspondingPropertySymbolField: IrPropertySymbol?,
    override var metadataField: MetadataSource.Property?
): FieldCarrier