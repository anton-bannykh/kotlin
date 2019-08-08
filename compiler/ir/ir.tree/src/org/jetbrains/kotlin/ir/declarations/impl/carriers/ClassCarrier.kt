/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.declarations.impl.carriers

import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.IrExpressionBody
import org.jetbrains.kotlin.ir.types.IrType

interface ClassCarrier : CarrierBase<ClassCarrier> {
    var thisReceiverField: IrValueParameter?
    var metadataField: MetadataSource?
    val declarationsField: MutableList<IrDeclaration>
    val typeParametersField: MutableList<IrTypeParameter>
    val superTypesField: MutableList<IrType>

    override fun eq(other: ClassCarrier): Boolean {
        return parentField === other.parentField &&
                thisReceiverField === other.thisReceiverField &&
                metadataField === other.metadataField &&
                annotationsField.eq(other.annotationsField) &&
                declarationsField.eq(other.declarationsField) &&
                typeParametersField.eq(other.typeParametersField) &&
                superTypesField.eq(other.superTypesField)
    }

    override fun clone(): ClassCarrier {
        return ClassCarrierImpl(
            parentField,
            annotationsField.clone(),
            thisReceiverField,
            metadataField,
            declarationsField.clone(),
            typeParametersField.clone(),
            superTypesField.clone()
        )
    }
}

class ClassCarrierImpl(
    override var parentField: IrDeclarationParent?,
    override val annotationsField: MutableList<IrExpressionBody>,
    override var thisReceiverField: IrValueParameter?,
    override var metadataField: MetadataSource?,
    override val declarationsField: MutableList<IrDeclaration>,
    override val typeParametersField: MutableList<IrTypeParameter>,
    override val superTypesField: MutableList<IrType>
) : ClassCarrier
