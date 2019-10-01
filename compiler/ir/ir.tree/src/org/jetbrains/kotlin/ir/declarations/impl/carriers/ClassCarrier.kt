/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.declarations.impl.carriers

import org.jetbrains.kotlin.descriptors.Visibility
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrDeclarationParent
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.declarations.MetadataSource

interface ClassCarrier : DeclarationCarrier<ClassCarrier> {
    var thisReceiverField: IrValueParameter?
    var metadataField: MetadataSource?
    var visibilityField: Visibility

    override fun eq(other: ClassCarrier): Boolean {
        return parentField === other.parentField &&
                originField === other.originField &&
                thisReceiverField === other.thisReceiverField &&
                metadataField === other.metadataField &&
                visibilityField === other.visibilityField
    }

    override fun clone(): ClassCarrier {
        return ClassCarrierImpl(
            parentField,
            originField,
            thisReceiverField,
            metadataField,
            visibilityField
        )
    }
}

class ClassCarrierImpl(
    override var parentField: IrDeclarationParent?,
    override var originField: IrDeclarationOrigin,
    override var thisReceiverField: IrValueParameter?,
    override var metadataField: MetadataSource?,
    override var visibilityField: Visibility
) : ClassCarrier
